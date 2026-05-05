package gwanbase.server

import gwanbase.table.Database
import mu.KotlinLogging
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Gwanbase TCP 서버.
 *
 * PostgreSQL Wire Protocol v3.0 Simple Query를 지원한다.
 * Thread-per-connection 모델로 동작하며, 각 연결에 독립 DatabaseSession을 바인딩한다.
 *
 * @param database 대상 데이터베이스
 * @param port 리스닝 포트 (기본 5432)
 */
class GwanServer(
    private val database: Database,
    private val port: Int = 5432,
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    /**
     * 서버를 시작한다.
     *
     * 별도 스레드에서 accept 루프를 실행한다.
     */
    fun start() {
        val ss = ServerSocket(port)
        serverSocket = ss
        logger.info { "GwanServer 시작: port=$port" }

        executor.submit {
            try {
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    connections.add(socket)
                    logger.debug { "클라이언트 연결: ${socket.remoteSocketAddress}" }
                    executor.submit {
                        try {
                            ConnectionHandler(socket, database).run()
                        } finally {
                            connections.remove(socket)
                        }
                    }
                }
            } catch (e: SocketException) {
                if (!ss.isClosed) logger.error(e) { "Accept 루프 오류" }
            }
        }
    }

    /**
     * 서버를 종료한다.
     *
     * ServerSocket을 닫고, 모든 활성 연결을 정리하며, 스레드 풀을 shutdown한다.
     */
    fun stop() {
        logger.info { "GwanServer 종료 중..." }
        serverSocket?.close()
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        logger.info { "GwanServer 종료 완료" }
    }

    override fun close() = stop()
}
