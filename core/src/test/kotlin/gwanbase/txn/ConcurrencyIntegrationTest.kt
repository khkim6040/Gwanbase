package gwanbase.txn

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SQL 실행 경로에 행 수준 잠금이 올바르게 연동되는지 검증하는 통합 테스트.
 */
class ConcurrencyIntegrationTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        db.executeSql("CREATE TABLE t (id INT NOT NULL, val INT NOT NULL)")
        db.executeSql("INSERT INTO t (id, val) VALUES (1, 100)")
        db.executeSql("INSERT INTO t (id, val) VALUES (2, 200)")
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `두 세션이 같은 행을 동시에 SELECT 할 수 있다`() {
        val s1 = db.createSession()
        val s2 = db.createSession()
        s1.executeSql("BEGIN")
        s2.executeSql("BEGIN")

        val r1 = s1.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
        val r2 = s2.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected

        r1.rows.size shouldBe 1
        r2.rows.size shouldBe 1

        s1.executeSql("COMMIT")
        s2.executeSql("COMMIT")
        s1.close()
        s2.close()
    }

    @Test
    fun `두 세션이 다른 행을 동시에 UPDATE 할 수 있다`() {
        val s1Done = AtomicBoolean(false)
        val s2Done = AtomicBoolean(false)
        val s1Ready = CountDownLatch(1)
        val s2Ready = CountDownLatch(1)

        val t1 = Thread {
            db.createSession().use { s1 ->
                s1.executeSql("BEGIN")
                s1Ready.countDown()
                s2Ready.await()
                s1.executeSql("UPDATE t SET val = 111 WHERE id = 1")
                s1.executeSql("COMMIT")
                s1Done.set(true)
            }
        }
        val t2 = Thread {
            db.createSession().use { s2 ->
                s2.executeSql("BEGIN")
                s2Ready.countDown()
                s1Ready.await()
                s2.executeSql("UPDATE t SET val = 222 WHERE id = 2")
                s2.executeSql("COMMIT")
                s2Done.set(true)
            }
        }
        t1.start(); t2.start()
        t1.join(5000); t2.join(5000)

        s1Done.get() shouldBe true
        s2Done.get() shouldBe true

        val result = db.executeSql("SELECT * FROM t ORDER BY id") as ExecuteResult.Selected
        result.rows[0][1] shouldBe 111
        result.rows[1][1] shouldBe 222
    }

    @Test
    fun `Dirty Read 방지 - 미커밋 변경이 다른 세션에 보이지 않는다`() {
        val s2SawDirty = AtomicBoolean(false)
        val s1Updated = CountDownLatch(1)
        val s2Read = CountDownLatch(1)

        val t1 = Thread {
            db.createSession().use { s1 ->
                s1.executeSql("BEGIN")
                s1.executeSql("UPDATE t SET val = 999 WHERE id = 1")
                s1Updated.countDown()
                s2Read.await()
                s1.executeSql("ROLLBACK")
            }
        }
        val t2 = Thread {
            db.createSession().use { s2 ->
                s1Updated.await()
                s2.executeSql("BEGIN")
                val result = s2.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
                if (result.rows[0][1] == 999) s2SawDirty.set(true)
                s2Read.countDown()
                s2.executeSql("COMMIT")
            }
        }
        t1.start(); t2.start()
        t1.join(5000); t2.join(5000)

        s2SawDirty.get() shouldBe false
    }
}
