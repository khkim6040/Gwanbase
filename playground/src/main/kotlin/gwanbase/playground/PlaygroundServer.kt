package gwanbase.playground

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gwanbase.server.ConnectionHandler
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

/**
 * 플레이그라운드 HTTP 서버.
 *
 * - `GET /` 화면, `POST /query` SQL 실행(본문 = SQL 텍스트), `GET /schema` 카탈로그, `POST /reset` 초기화.
 * - 쿠키 `gb_session`으로 방문자를 [SessionRegistry]의 세션에 대응시킨다.
 * - `/reset`은 write 락, 나머지는 read 락을 잡아 초기화 중 요청이 끼어들지 않게 한다.
 * - 1분마다 idle 세션을 회수한다.
 *
 * @param port 0이면 임의 포트. 실제 포트는 [port] 프로퍼티로 읽는다.
 * @param lockTimeoutMillis 세션별 잠금 대기 상한(ms)
 * @param idleMillis 이 시간 동안 요청이 없는 세션을 회수한다(ms)
 */
class PlaygroundServer(
    private val engine: Engine,
    port: Int = 8080,
    lockTimeoutMillis: Long = 5_000,
    idleMillis: Long = 10 * 60_000,
) : AutoCloseable {

    private val sessions = SessionRegistry({ engine.database }, lockTimeoutMillis, idleMillis)
    private val resetLock = ReentrantReadWriteLock()
    private val http: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val evictor = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    private val indexHtml: String = PlaygroundServer::class.java.getResource("/index.html")!!.readText()

    val port: Int get() = http.address.port

    init {
        http.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        http.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Exception) {
                logger.error(e) { "요청 처리 실패: ${exchange.requestMethod} ${exchange.requestURI}" }
                exchange.respond(500, Json.encode(mapOf("error" to (e.message ?: "internal error"))))
            }
        }
    }

    /** HTTP 수신을 시작하고 1분 주기 idle 세션 회수를 예약한다. */
    fun start() {
        http.start()
        evictor.scheduleAtFixedRate({
            try {
                resetLock.read { sessions.evictIdle() }
            } catch (e: Exception) {
                logger.error(e) { "idle 세션 회수 실패" }
            }
        }, 1, 1, TimeUnit.MINUTES)
        logger.info { "Playground 시작: port=$port" }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/" && method == "GET" -> ex.respond(200, indexHtml, "text/html; charset=utf-8")
            path == "/query" && method == "POST" -> query(ex)
            path == "/schema" && method == "GET" ->
                resetLock.read { ex.respond(200, Json.encode(Json.schema(engine.database.getCatalog()))) }
            path == "/reset" && method == "POST" -> resetLock.write {
                sessions.closeAll()
                engine.reset()
                ex.respond(200, """{"ok":true}""")
            }
            path in KNOWN_PATHS -> ex.respond(405, Json.encode(mapOf("error" to "method not allowed")))
            else -> ex.respond(404, Json.encode(mapOf("error" to "not found")))
        }
    }

    private fun query(ex: HttpExchange) {
        val requestBody = ex.requestBody
        val body = requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            // 남은 요청 본문을 다 읽지 않고 응답부터 쓰면 클라이언트가 413 대신 connection reset을
            // 본다 (JDK HttpServer가 다음 요청을 위해 스트림을 재사용하려다 어긋남). 응답 전에 같은
            // 스트림에서 나머지를 드레인한다 (이미 닫힌 스트림을 다시 읽으면 500이 난다).
            requestBody.readAllBytes()
            ex.respond(413, Json.encode(mapOf("error" to "본문이 ${MAX_BODY_BYTES}바이트를 넘는다")))
            return
        }
        val sql = String(body, Charsets.UTF_8)
        resetLock.read {
            val (id, session) = sessions.acquire(ex.cookie(COOKIE_NAME))
            ex.responseHeaders.add("Set-Cookie", "$COOKIE_NAME=$id; Path=/; HttpOnly; Secure; SameSite=Lax")
            try {
                val result = session.execute(sql)
                ex.respond(200, Json.encode(Json.result(result, session.txnStatus, MAX_ROWS)))
            } catch (e: Exception) {
                val sqlState = ConnectionHandler.sqlStateOf(e)
                ex.respond(400, Json.encode(mapOf(
                    "error" to (e.message ?: e::class.simpleName),
                    "sqlState" to sqlState,
                    "txn" to session.txnStatus.toString(),
                )))
            }
        }
    }

    private fun HttpExchange.cookie(name: String): String? =
        requestHeaders.getFirst("Cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** 회수 스케줄러와 HTTP 서버를 즉시 멈추고 모든 세션을 닫는다. 진행 중인 요청은 기다리지 않는다. */
    override fun close() {
        evictor.shutdownNow()
        http.stop(0)
        sessions.closeAll()
    }

    companion object {
        const val COOKIE_NAME = "gb_session"
        const val MAX_ROWS = 500
        const val MAX_BODY_BYTES = 64 * 1024
        private val KNOWN_PATHS = setOf("/", "/query", "/schema", "/reset")
    }
}
