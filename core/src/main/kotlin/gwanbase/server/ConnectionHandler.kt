package gwanbase.server

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import gwanbase.txn.DatabaseSession
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

    private var inTransaction = false
    private var txnFailed = false

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
                    writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
                    writer.flush()
                }
            }
        }
    }

    private fun handleQuery(sql: String, session: DatabaseSession, writer: PgMessageWriter) {
        if (txnFailed && !isRollbackCommand(sql)) {
            writer.write(PgMessage.ErrorResponse(
                "ERROR",
                "current transaction is aborted, commands ignored until end of transaction block",
                "25P02",
            ))
            writer.write(PgMessage.ReadyForQuery('E'))
            writer.flush()
            return
        }

        try {
            val result = session.executeSql(sql)
            updateTxnState(result)
            val messages = ResultFormatter.format(result)
            for (m in messages) {
                writer.write(m)
            }
            writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
            writer.flush()
        } catch (e: Exception) {
            if (inTransaction) txnFailed = true
            writer.write(PgMessage.ErrorResponse(
                severity = "ERROR",
                message = e.message ?: "내부 오류",
                code = "XX000",
            ))
            writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
            writer.flush()
        }
    }

    /**
     * ExecuteResult 타입을 기반으로 트랜잭션 상태를 갱신한다.
     *
     * SQL 문자열 비교 대신 실행 결과 타입으로 판단하여
     * 세미콜론, 공백 변형 등에 안전하다.
     */
    private fun updateTxnState(result: ExecuteResult) {
        when (result) {
            is ExecuteResult.TransactionStarted -> {
                inTransaction = true
                txnFailed = false
            }
            is ExecuteResult.TransactionCommitted,
            is ExecuteResult.TransactionRolledBack -> {
                inTransaction = false
                txnFailed = false
            }
            else -> {}
        }
    }

    /**
     * txnFailed 상태에서 ROLLBACK 명령을 허용하기 위한 판별.
     *
     * 세미콜론, 대소문자, 앞뒤 공백을 정규화하여 비교한다.
     */
    private fun isRollbackCommand(sql: String): Boolean {
        val normalized = sql.trim().removeSuffix(";").trim().uppercase()
        return normalized == "ROLLBACK"
    }

    private fun currentTxnStatus(): Char = when {
        txnFailed -> 'E'
        inTransaction -> 'T'
        else -> 'I'
    }
}
