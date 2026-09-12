package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.txn.DatabaseSession

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
        get() = when {
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
            runCatching { session.executeSql(sql) }
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

    @Synchronized
    override fun close() = session.close()
}
