package gwanbase.server

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class GwanServerJdbcTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database
    private lateinit var server: GwanServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        port = java.net.ServerSocket(0).use { it.localPort }
        server = GwanServer(db, port)
        server.start()
        Thread.sleep(100)
    }

    @AfterEach
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun connect(): java.sql.Connection {
        return DriverManager.getConnection(
            "jdbc:postgresql://localhost:$port/gwanbase?preferQueryMode=simple",
            "gwanbase",
            "",
        )
    }

    @Test
    fun `JDBC 연결 성공`() {
        connect().use { conn ->
            conn.isClosed shouldBe false
        }
    }

    @Test
    fun `DDL과 DML — CREATE TABLE, INSERT, SELECT`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INT NOT NULL, name VARCHAR(100))")
                stmt.execute("INSERT INTO users (id, name) VALUES (1, 'alice')")
                stmt.execute("INSERT INTO users (id, name) VALUES (2, 'bob')")

                val rs = stmt.executeQuery("SELECT * FROM users")
                val rows = mutableListOf<Pair<Int, String>>()
                while (rs.next()) {
                    rows.add(rs.getInt(1) to rs.getString(2))
                }
                rows.size shouldBe 2
                rows[0] shouldBe (1 to "alice")
                rows[1] shouldBe (2 to "bob")
            }
        }
    }

    @Test
    fun `UPDATE와 DELETE`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE t (id INT NOT NULL, val INT NOT NULL)")
                stmt.execute("INSERT INTO t (id, val) VALUES (1, 10)")
                stmt.execute("INSERT INTO t (id, val) VALUES (2, 20)")

                val updateCount = stmt.executeUpdate("UPDATE t SET val = 99 WHERE id = 1")
                updateCount shouldBe 1

                val deleteCount = stmt.executeUpdate("DELETE FROM t WHERE id = 2")
                deleteCount shouldBe 1

                val rs = stmt.executeQuery("SELECT * FROM t")
                rs.next() shouldBe true
                rs.getInt("id") shouldBe 1
                rs.getInt("val") shouldBe 99
                rs.next() shouldBe false
            }
        }
    }

    @Test
    fun `트랜잭션 — commit과 rollback`() {
        connect().use { conn ->
            conn.createStatement().execute("CREATE TABLE t (id INT NOT NULL)")
        }

        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("BEGIN")
                stmt.execute("INSERT INTO t (id) VALUES (1)")
                stmt.execute("COMMIT")
            }
        }

        connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM t")
            rs.next() shouldBe true
            rs.getInt(1) shouldBe 1
        }

        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("BEGIN")
                stmt.execute("INSERT INTO t (id) VALUES (2)")
                stmt.execute("ROLLBACK")
            }
        }

        connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM t")
            rs.next() shouldBe true
            rs.getInt(1) shouldBe 1
            rs.next() shouldBe false
        }
    }

    @Test
    fun `다중 연결 — 독립 트랜잭션`() {
        connect().use { conn ->
            conn.createStatement().execute("CREATE TABLE t (id INT NOT NULL)")
        }

        val conn1 = connect()
        val conn2 = connect()
        try {
            conn1.createStatement().execute("INSERT INTO t (id) VALUES (1)")
            conn2.createStatement().execute("INSERT INTO t (id) VALUES (2)")

            val rs = connect().use { conn ->
                val r = conn.createStatement().executeQuery("SELECT * FROM t")
                val ids = mutableListOf<Int>()
                while (r.next()) ids.add(r.getInt(1))
                ids
            }
            rs.sorted() shouldBe listOf(1, 2)
        } finally {
            conn1.close()
            conn2.close()
        }
    }

    @Test
    fun `대량 INSERT — 100건 삽입 후 SELECT 검증`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
                for (i in 1..100) {
                    stmt.execute("INSERT INTO t (id, name) VALUES ($i, 'name_$i')")
                }
                val rs = stmt.executeQuery("SELECT * FROM t")
                var count = 0
                while (rs.next()) count++
                count shouldBe 100
            }
        }
    }
}
