package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import gwanbase.txn.DatabaseSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 방문자 한 명의 [DatabaseSession]을 감싸 동시 요청을 직렬화하고 마지막 사용 시각을 기록한다.
 *
 * 트랜잭션 상태(I/T/E)와 실패한 블록의 25P02 거부는 `DatabaseSession`이 담당한다.
 */
class PlaygroundSession(private val session: DatabaseSession) : AutoCloseable {

    @Volatile
    var lastUsedAt: Long = System.currentTimeMillis()

    /** PG 프로토콜 ReadyForQuery 상태와 같은 의미: I(idle) / T(트랜잭션 중) / E(실패한 트랜잭션). */
    val txnStatus: Char
        @Synchronized get() = session.txnStatus

    /**
     * SQL 한 문장을 실행한다.
     * @throws gwanbase.txn.TransactionAbortedException 실패한 트랜잭션 안에서 ROLLBACK/COMMIT이 아닌 명령을 보낸 경우
     */
    @Synchronized
    fun execute(sql: String): ExecuteResult {
        lastUsedAt = System.currentTimeMillis()
        return session.executeSql(sql)
    }

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
