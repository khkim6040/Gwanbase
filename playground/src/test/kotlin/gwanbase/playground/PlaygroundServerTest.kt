package gwanbase.playground

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class PlaygroundServerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var engine: Engine
    private lateinit var server: PlaygroundServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        engine = Engine(tempDir.resolve("pg.db"))
        server = PlaygroundServer(engine, port = 0, lockTimeoutMillis = 300)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
        engine.close()
    }

    private fun url(path: String) = URI("http://localhost:${server.port}$path")

    private fun query(sql: String, cookie: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(url("/query")).POST(HttpRequest.BodyPublishers.ofString(sql))
        if (cookie != null) builder.header("Cookie", cookie)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(url(path)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())

    /** 응답의 Set-Cookie에서 다음 요청에 보낼 `gb_session=...` 부분만 꺼낸다. */
    private fun HttpResponse<String>.sessionCookie(): String =
        headers().firstValue("Set-Cookie").get().substringBefore(";")

    @Test
    fun `SELECT는 columns와 rows를 담아 200으로 응답한다`() {
        val res = query("SELECT name FROM users WHERE id = 1")
        res.statusCode() shouldBe 200
        res.body() shouldContain """"columns":["name"]"""
        res.body() shouldContain """"rows":[["Alice"]]"""
        res.body() shouldContain """"txn":"I""""
    }

    @Test
    fun `존재하지 않는 테이블은 400과 sqlState 42000으로 응답한다`() {
        val res = query("SELECT * FROM nope")
        res.statusCode() shouldBe 400
        res.body() shouldContain """"sqlState":"42000""""
    }

    @Test
    fun `첫 응답은 gb_session 쿠키를 발급하고 같은 쿠키로 트랜잭션이 이어진다`() {
        val begin = query("BEGIN")
        val cookie = begin.sessionCookie()
        cookie shouldContain "gb_session="
        begin.headers().firstValue("Set-Cookie").get() shouldContain "Secure"
        begin.body() shouldContain """"txn":"T""""

        query("SELECT * FROM nope", cookie).body() shouldContain """"txn":"E""""
        query("SELECT * FROM users", cookie).body() shouldContain """"sqlState":"25P02""""
        query("ROLLBACK", cookie).body() shouldContain """"txn":"I""""
    }

    @Test
    fun `다른 방문자의 미커밋 행을 갱신하면 락 타임아웃 55P03을 받는다`() {
        val a = query("BEGIN").sessionCookie()
        query("UPDATE users SET age = 31 WHERE id = 1", a)

        val res = query("UPDATE users SET age = 32 WHERE id = 1")
        res.statusCode() shouldBe 400
        res.body() shouldContain """"sqlState":"55P03""""
    }

    @Test
    fun `schema는 샘플 테이블과 인덱스를 담는다`() {
        val res = get("/schema")
        res.statusCode() shouldBe 200
        res.body() shouldContain """"name":"orders""""
        res.body() shouldContain """"name":"idx_orders_user""""
    }

    @Test
    fun `reset 후 schema가 샘플 상태로 돌아오고 기존 세션의 트랜잭션은 사라진다`() {
        val cookie = query("BEGIN").sessionCookie()
        query("DROP TABLE orders", cookie)

        post("/reset").statusCode() shouldBe 200

        get("/schema").body() shouldContain """"name":"orders""""
        query("SELECT * FROM users", cookie).body() shouldContain """"txn":"I""""
    }

    @Test
    fun `500행을 넘는 SELECT는 truncated true로 잘린다`() {
        engine.database.executeSql("CREATE TABLE big (n INT)")
        for (i in 1..501) engine.database.executeSql("INSERT INTO big (n) VALUES ($i)")

        val body = query("SELECT * FROM big").body()
        body shouldContain """"truncated":true"""
        body shouldContain """"count":501"""
    }

    @Test
    fun `모르는 경로는 404 잘못된 메서드는 405 큰 본문은 413이다`() {
        get("/nope").statusCode() shouldBe 404
        get("/query").statusCode() shouldBe 405
        query("SELECT * FROM users -- " + "x".repeat(65 * 1024)).statusCode() shouldBe 413
    }

    @Test
    fun `루트는 HTML을 돌려준다`() {
        val res = get("/")
        res.statusCode() shouldBe 200
        res.headers().firstValue("Content-Type").get() shouldContain "text/html"
        res.body() shouldContain "Gwanbase Playground"
        res.body() shouldContain "id=\"sql\""
        res.body() shouldContain "fetch('/query'"
        res.body() shouldContain "fetch('/schema')"
        res.body() shouldContain "fetch('/reset'"
        res.body() shouldContain "aria-label=\"SQL 입력\""
    }
}
