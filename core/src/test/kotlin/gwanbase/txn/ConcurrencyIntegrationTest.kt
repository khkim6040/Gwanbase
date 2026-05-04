package gwanbase.txn

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import gwanbase.table.RID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun `교차 UPDATE 시 데드락 감지 후 한쪽이 rollback된다`() {
        // LockManager를 직접 사용하여 결정론적 데드락 시나리오를 구성한다.
        // SQL 실행 경로를 우회하고, 잠금 그래프에서 사이클을 강제로 만든다.
        val lm = db.lockManager
        val tableName = "t"
        val rid1 = RID(3, 0) // row1의 RID (INSERT 순서상 pageId=3, slotId=0)
        val rid2 = RID(3, 1) // row2의 RID (INSERT 순서상 pageId=3, slotId=1)
        val target1 = LockTarget(tableName, rid1)
        val target2 = LockTarget(tableName, rid2)

        val deadlockOccurred = AtomicBoolean(false)
        val t1HoldsX1 = CountDownLatch(1)
        val t2HoldsX2 = CountDownLatch(1)
        val t2AboutToBlockOnX1 = CountDownLatch(1) // t2가 X(rid1) 요청 직전임을 알리는 래치
        val error = AtomicReference<Throwable?>(null)

        // t1: X(rid1) 획득 → t2가 X(rid1) 대기 큐에 진입할 때까지 기다린 후 → X(rid2) 요청 → 데드락 감지
        val t1 = Thread {
            try {
                lm.acquire(1, target1, LockMode.EXCLUSIVE) // X(rid1) 즉시 획득
                t1HoldsX1.countDown()
                t2AboutToBlockOnX1.await() // t2가 X(rid1)에 블록되기 직전임을 확인
                Thread.sleep(10) // t2가 실제로 대기 큐에 진입할 시간을 줌
                // 이 시점에: t2는 X(rid2) 보유 + X(rid1) 대기 중, t1은 X(rid1) 보유 → X(rid2) 요청 시 사이클
                lm.acquire(1, target2, LockMode.EXCLUSIVE) // X(rid2) → t2가 보유 중 + t2가 t1을 기다림 → 데드락
                lm.releaseAll(1)
            } catch (e: DeadlockException) {
                deadlockOccurred.set(true)
                lm.releaseAll(1)
            } catch (e: Throwable) {
                error.set(e)
                lm.releaseAll(1)
            }
        }

        // t2: X(rid2) 획득 → X(rid1) 요청 (t1이 보유 중이므로 블록)
        val t2 = Thread {
            try {
                lm.acquire(2, target2, LockMode.EXCLUSIVE) // X(rid2) 즉시 획득
                t2HoldsX2.countDown()
                t1HoldsX1.await() // t1이 X(rid1)를 보유할 때까지 대기
                t2AboutToBlockOnX1.countDown() // t1에게 "곧 X(rid1) 요청할 것"을 알림
                lm.acquire(2, target1, LockMode.EXCLUSIVE) // X(rid1) → t1이 보유 중 → 블록
                lm.releaseAll(2)
            } catch (e: DeadlockException) {
                deadlockOccurred.set(true)
                lm.releaseAll(2)
            } catch (e: Throwable) {
                error.set(e)
                lm.releaseAll(2)
            }
        }

        t1.start()
        t2.start()
        t1.join(5000)
        t2.join(5000)

        error.get()?.let { throw it }
        deadlockOccurred.get() shouldBe true
    }

    @Test
    fun `Lost Update 방지 - 두 세션의 동시 UPDATE가 직렬화된다`() {
        // 초기값: val = 100
        val t1Done = CountDownLatch(1)
        val t2Done = CountDownLatch(1)

        val t1 = Thread {
            db.createSession().use { s ->
                s.executeSql("UPDATE t SET val = val + 10 WHERE id = 1")
            }
            t1Done.countDown()
        }

        val t2 = Thread {
            db.createSession().use { s ->
                s.executeSql("UPDATE t SET val = val + 20 WHERE id = 1")
            }
            t2Done.countDown()
        }

        t1.start()
        t2.start()
        t1Done.await(5, TimeUnit.SECONDS)
        t2Done.await(5, TimeUnit.SECONDS)

        val result = db.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
        // 직렬화 보장: 100+10+20=130 또는 100+20+10=130
        result.rows[0][1] shouldBe 130
    }
}
