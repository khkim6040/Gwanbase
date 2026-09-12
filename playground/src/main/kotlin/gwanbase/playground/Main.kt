package gwanbase.playground

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import mu.KotlinLogging
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * 플레이그라운드 진입점.
 *
 * 환경변수: `PORT`(기본 8080), `GWANBASE_DB`(기본 `playground.db`).
 * core의 logback.xml이 `gwanbase`를 DEBUG로 두어 페이지 단위 로그가 쏟아지므로 INFO로 올린다.
 */
fun main() {
    (LoggerFactory.getLogger("gwanbase") as Logger).level = Level.INFO
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbPath = Paths.get(System.getenv("GWANBASE_DB") ?: "playground.db")

    val engine = Engine(dbPath)
    val server = PlaygroundServer(engine, port)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        engine.close()
    })
    server.start()
    logger.info { "http://localhost:${server.port}" }
}
