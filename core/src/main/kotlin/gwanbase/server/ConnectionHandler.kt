package gwanbase.server

import gwanbase.sql.BindException
import gwanbase.sql.DataException
import gwanbase.sql.ParseException
import gwanbase.table.Database
import gwanbase.table.ConstraintViolationException
import gwanbase.table.UniqueViolationException
import gwanbase.txn.DatabaseSession
import gwanbase.txn.DeadlockException
import gwanbase.txn.LockTimeoutException
import gwanbase.txn.TransactionAbortedException
import mu.KotlinLogging
import java.io.EOFException
import java.net.Socket
import java.net.SocketException

private val logger = KotlinLogging.logger {}

/**
 * 단일 클라이언트 연결의 라이프사이클을 관리한다.
 *
 * Startup → Query Loop → Terminate 순서로 진행하며,
 * 연결당 하나의 DatabaseSession을 바인딩한다.
 */
class ConnectionHandler(
    private val socket: Socket,
    private val database: Database,
) : Runnable {

    override fun run() {
        try {
            socket.use { sock ->
                val reader = PgMessageReader(sock.getInputStream())
                val writer = PgMessageWriter(sock.getOutputStream())

                if (!handleStartup(reader, writer)) return

                database.createSession().use { session ->
                    queryLoop(session, reader, writer)
                }
            }
        } catch (e: EOFException) {
            logger.debug { "클라이언트 연결 종료 (EOF)" }
        } catch (e: SocketException) {
            logger.debug { "클라이언트 연결 종료: ${e.message}" }
        } catch (e: Exception) {
            logger.error(e) { "ConnectionHandler 오류" }
        }
    }

    private fun handleStartup(reader: PgMessageReader, writer: PgMessageWriter): Boolean {
        var startup = reader.readStartupMessage()
        while (startup == null) {
            socket.getOutputStream().write('N'.code)
            socket.getOutputStream().flush()
            startup = reader.readStartupMessage()
        }

        writer.write(PgMessage.AuthenticationOk)
        writer.write(PgMessage.ParameterStatus("server_version", "0.8.0"))
        writer.write(PgMessage.ParameterStatus("server_encoding", "UTF8"))
        writer.write(PgMessage.ParameterStatus("client_encoding", "UTF8"))
        writer.write(PgMessage.BackendKeyData(
            pid = Thread.currentThread().id.toInt(),
            secretKey = 0,
        ))
        writer.write(PgMessage.ReadyForQuery('I'))
        writer.flush()
        return true
    }

    private fun queryLoop(session: DatabaseSession, reader: PgMessageReader, writer: PgMessageWriter) {
        while (true) {
            val msg = reader.readMessage()
            when (msg) {
                is PgMessage.Terminate -> return
                is PgMessage.Query -> handleQuery(msg.sql, session, writer)
                else -> {
                    writer.write(PgMessage.ErrorResponse("ERROR", "지원하지 않는 메시지", "XX000"))
                    writer.write(PgMessage.ReadyForQuery(session.txnStatus))
                    writer.flush()
                }
            }
        }
    }

    /**
     * 쿼리 하나를 실행하고 결과 또는 오류를 쓴다.
     * 트랜잭션 상태(I/T/E)와 실패한 블록의 25P02 거부는 [DatabaseSession]이 담당하므로 여기서는 결과만 전달한다.
     */
    private fun handleQuery(sql: String, session: DatabaseSession, writer: PgMessageWriter) {
        try {
            val result = session.executeSql(sql)
            for (m in ResultFormatter.format(result)) {
                writer.write(m)
            }
        } catch (e: Exception) {
            writer.write(PgMessage.ErrorResponse(
                severity = "ERROR",
                message = e.message ?: "내부 오류",
                code = sqlStateOf(e),
            ))
        }
        writer.write(PgMessage.ReadyForQuery(session.txnStatus))
        writer.flush()
    }

    companion object {
        /**
         * 예외 타입을 PostgreSQL SQLSTATE 코드로 변환한다.
         *
         * 클라이언트(JDBC 등)가 `SQLException.getSQLState()`로 에러 종류를 분기할 수 있도록
         * PostgreSQL과 동일한 코드를 사용한다. 매핑되지 않은 예외는 internal_error(XX000)로 취급한다.
         */
        fun sqlStateOf(e: Throwable): String = when (e) {
            is ParseException -> "42601"    // syntax_error
            is BindException -> "42000"     // syntax_error_or_access_rule_violation
            is DeadlockException -> "40P01" // deadlock_detected
            is LockTimeoutException -> "55P03" // lock_not_available
            is TransactionAbortedException -> "25P02" // in_failed_sql_transaction
            is DataException -> e.sqlState  // Class 22: data_exception
            is UniqueViolationException -> e.sqlState // 23505: unique_violation
            is ConstraintViolationException -> e.sqlState // 23503 / 23514
            else -> "XX000"                 // internal_error
        }
    }
}
