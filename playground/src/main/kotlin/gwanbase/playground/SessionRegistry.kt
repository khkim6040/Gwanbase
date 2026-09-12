package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import gwanbase.txn.DatabaseSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 실패한 트랜잭션 안에서 ROLLBACK 이외의 명령을 보냈을 때. PostgreSQL SQLSTATE 25P02. */
class TransactionAbortedException : RuntimeException(
    "current transaction is aborted, commands ignored until end of transaction block"
)

/**
 * 방문자 한 명의 [DatabaseSession]과 트랜잭션 상태(I/T/E)를 묶는다.
 *
 * `DatabaseSession`은 스레드 안전하지 않으므로 같은 방문자의 동시 요청은 이 객체로 직렬화한다.
 *
 * 상태기계는 `gwanbase.server.ConnectionHandler`와 같다 — 결과 타입으로 T/I를 갱신하고,
 * 트랜잭션 중 예외가 나면 E로 간다. E에서는 ROLLBACK만 받는다.
 * ponytail: 이 상태기계는 세션 계층(`DatabaseSession`)에 FAILED state가 들어오면 그쪽으로 옮기고
 * 여기와 ConnectionHandler 양쪽에서 제거한다.
 */
class PlaygroundSession(private val session: DatabaseSession) : AutoCloseable {

    @Volatile
    var lastUsedAt: Long = System.currentTimeMillis()

    private var inTransaction = false
    private var txnFailed = false

    /** PG 프로토콜 ReadyForQuery 상태와 같은 의미: I(idle) / T(트랜잭션 중) / E(실패한 트랜잭션). */
    val txnStatus: Char
        @Synchronized get() = when {
            txnFailed -> 'E'
            inTransaction -> 'T'
            else -> 'I'
        }

    /**
     * SQL 한 문장을 실행한다.
     * @throws TransactionAbortedException 실패한 트랜잭션 안에서 ROLLBACK이 아닌 명령을 보낸 경우
     */
    @Synchronized
    fun execute(sql: String): ExecuteResult {
        lastUsedAt = System.currentTimeMillis()
        if (txnFailed) {
            if (!isRollback(sql)) throw TransactionAbortedException()
            // ponytail: 실행 단계 오류(UNIQUE 위반 등)는 DatabaseSession이 이미 abort해 활성 트랜잭션이
            // 없고, 이때 ROLLBACK은 IllegalStateException을 던진다. 바인딩 오류는 abort되지 않아
            // ROLLBACK이 정상 동작한다. 두 경우 모두 방문자에게는 ROLLBACK 성공으로 보여야 한다.
            try {
                session.executeSql(sql)
            } catch (e: IllegalStateException) {
                // 활성 트랜잭션이 이미 abort된 경우 — 위 주석 참조
            }
            inTransaction = false
            txnFailed = false
            return ExecuteResult.TransactionRolledBack
        }
        try {
            val result = session.executeSql(sql)
            when (result) {
                ExecuteResult.TransactionStarted -> inTransaction = true
                ExecuteResult.TransactionCommitted, ExecuteResult.TransactionRolledBack -> inTransaction = false
                else -> {}
            }
            return result
        } catch (e: Exception) {
            if (inTransaction) txnFailed = true
            throw e
        }
    }

    private fun isRollback(sql: String): Boolean =
        sql.trim().removeSuffix(";").trim().equals("ROLLBACK", ignoreCase = true)

    /** 세션을 닫는다. 미커밋 트랜잭션은 abort되고 잠금이 풀린다. */
    @Synchronized
    override fun close() = session.close()
}

/**
 * 쿠키 ID → [PlaygroundSession] 매핑.
 *
 * 브라우저를 닫고 떠난 방문자가 미커밋 트랜잭션의 잠금을 영원히 쥐지 않도록
 * [evictIdle]로 idle 세션을 닫는다. 공유 DB 모델에서 가장 중요한 안전장치다.
 *
 * @param database 현재 Database를 돌려주는 함수. reset 후 새 인스턴스를 받기 위해 지연 참조한다.
 * @param lockTimeoutMillis 새 세션마다 설정할 잠금 대기 상한
 * @param idleMillis 이 시간 동안 요청이 없으면 회수 대상
 */
class SessionRegistry(
    private val database: () -> Database,
    private val lockTimeoutMillis: Long,
    private val idleMillis: Long,
) {

    private val sessions = ConcurrentHashMap<String, PlaygroundSession>()

    /** 현재 살아 있는 세션 수. */
    val size: Int get() = sessions.size

    /** [id]의 세션을 돌려주고, 없거나 회수됐으면 새로 만든다. first는 클라이언트에 줄 세션 ID다. */
    fun acquire(id: String?): Pair<String, PlaygroundSession> {
        if (id != null) sessions[id]?.let {
            it.lastUsedAt = System.currentTimeMillis()
            return id to it
        }
        val newId = UUID.randomUUID().toString()
        val dbSession = database().createSession().apply { lockTimeoutMillis = this@SessionRegistry.lockTimeoutMillis }
        val session = PlaygroundSession(dbSession)
        sessions[newId] = session
        return newId to session
    }

    /** [now] 기준 idle 초과 세션을 닫고 제거한다. 닫은 개수를 반환한다. */
    fun evictIdle(now: Long = System.currentTimeMillis()): Int {
        var closed = 0
        for ((id, session) in sessions) {
            if (now - session.lastUsedAt > idleMillis && sessions.remove(id, session)) {
                runCatching { session.close() }
                closed++
            }
        }
        return closed
    }

    /** 모든 세션을 닫는다. reset 전에 호출한다. */
    fun closeAll() {
        val all = sessions.values.toList()
        sessions.clear()
        all.forEach { runCatching { it.close() } }
    }
}
