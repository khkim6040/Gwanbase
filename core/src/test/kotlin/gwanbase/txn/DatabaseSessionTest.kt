package gwanbase.txn

import gwanbase.sql.BindException
import gwanbase.sql.ExecuteResult
import gwanbase.sql.ParseException
import gwanbase.table.UniqueViolationException
import gwanbase.table.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DatabaseSessionTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `auto-commit 모드로 INSERT를 실행한다`() {
        val session = db.createSession()
        session.use {
            val result = it.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            result.shouldBeInstanceOf<ExecuteResult.Inserted>()
        }
        val session2 = db.createSession()
        session2.use {
            val result = it.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 1
        }
    }

    @Test
    fun `명시적 BEGIN - INSERT - COMMIT`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN").shouldBeInstanceOf<ExecuteResult.TransactionStarted>()
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            session.executeSql("COMMIT").shouldBeInstanceOf<ExecuteResult.TransactionCommitted>()
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 1
        }
    }

    @Test
    fun `명시적 BEGIN - INSERT - ROLLBACK 후 데이터 미존재`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            session.executeSql("ROLLBACK").shouldBeInstanceOf<ExecuteResult.TransactionRolledBack>()
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 0
        }
    }

    @Test
    fun `BEGIN 중복 호출 시 예외`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            shouldThrow<IllegalStateException> {
                session.executeSql("BEGIN")
            }
            session.executeSql("ROLLBACK")
        }
    }

    @Test
    fun `활성 트랜잭션 없이 COMMIT 시 예외`() {
        db.createSession().use { session ->
            shouldThrow<IllegalStateException> {
                session.executeSql("COMMIT")
            }
        }
    }

    @Test
    fun `활성 트랜잭션 없이 ROLLBACK 시 예외`() {
        db.createSession().use { session ->
            shouldThrow<IllegalStateException> {
                session.executeSql("ROLLBACK")
            }
        }
    }

    @Test
    fun `close 시 활성 트랜잭션이 자동 rollback된다`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 0
        }
    }

    /** 주어진 동작을 새 스레드에서 실행하고 끝날 때까지 기다린다. 스레드 내 예외는 호출 스레드로 전파한다. */
    private fun onNewThread(action: () -> Unit) {
        var err: Throwable? = null
        val th = Thread { try { action() } catch (e: Throwable) { err = e } }
        th.start(); th.join()
        err?.let { throw it }
    }

    @Test
    fun `BEGIN UPDATE ROLLBACK을 서로 다른 스레드에서 실행해도 롤백된다`() {
        db.executeSql("CREATE TABLE t2 (id INT PRIMARY KEY, v INT)")
        db.executeSql("INSERT INTO t2 (id, v) VALUES (1, 10)")
        val session = db.createSession()
        onNewThread { session.executeSql("BEGIN") }
        onNewThread { session.executeSql("UPDATE t2 SET v = 99 WHERE id = 1") }
        onNewThread { session.executeSql("ROLLBACK") }
        session.close()

        val r = db.executeSql("SELECT v FROM t2 WHERE id = 1") as ExecuteResult.Selected
        r.rows.single().single() shouldBe 10
    }

    @Test
    fun `다른 세션의 트랜잭션 중 UPDATE가 내 스레드에서 실행돼도 내 ROLLBACK이 그 커밋을 되돌리지 않는다`() {
        db.executeSql("CREATE TABLE t2 (id INT PRIMARY KEY, v INT)")
        db.executeSql("INSERT INTO t2 (id, v) VALUES (1, 10)")
        val a = db.createSession()
        val b = db.createSession()

        a.executeSql("BEGIN")                       // 메인 스레드 홀더 = A의 트랜잭션
        onNewThread { b.executeSql("BEGIN") }       // B의 홀더는 다른 스레드에만 남는다
        b.executeSql("UPDATE t2 SET v = 77 WHERE id = 1") // 메인 스레드: 버그 시 A의 로그 체인에 기록된다
        b.executeSql("COMMIT")
        a.executeSql("ROLLBACK")                    // 버그 시 B가 커밋한 77을 10으로 되돌린다
        a.close(); b.close()

        val r = db.executeSql("SELECT v FROM t2 WHERE id = 1") as ExecuteResult.Selected
        r.rows.single().single() shouldBe 77
    }

    @Test
    fun `lockTimeoutMillis 초과 시 LockTimeoutException`() {
        db.executeSql("INSERT INTO t (id, name) VALUES (1, 'a')")
        db.createSession().use { s1 ->
            db.createSession().use { s2 ->
                s1.executeSql("BEGIN")
                s1.executeSql("UPDATE t SET name = 'b' WHERE id = 1")

                s2.lockTimeoutMillis = 100
                shouldThrow<LockTimeoutException> {
                    s2.executeSql("UPDATE t SET name = 'c' WHERE id = 1")
                }
            }
        }
    }
}

class DatabaseSessionFailedStateTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        db.executeSql("CREATE TABLE t (id INT PRIMARY KEY)")
        db.executeSql("INSERT INTO t (id) VALUES (1)")
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `트랜잭션 중 실행 오류가 나면 E 상태가 되고 ROLLBACK으로 I로 돌아온다`() {
        db.createSession().use { session ->
            session.txnStatus shouldBe 'I'
            session.executeSql("BEGIN")
            session.txnStatus shouldBe 'T'
            shouldThrow<UniqueViolationException> { session.executeSql("INSERT INTO t (id) VALUES (1)") }
            session.txnStatus shouldBe 'E'
            session.executeSql("ROLLBACK").shouldBeInstanceOf<ExecuteResult.TransactionRolledBack>()
            session.txnStatus shouldBe 'I'
            (session.executeSql("SELECT * FROM t") as ExecuteResult.Selected).rows.size shouldBe 1
        }
    }

    @Test
    fun `E 상태에서 ROLLBACK 이외의 문장은 TransactionAbortedException`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            shouldThrow<UniqueViolationException> { session.executeSql("INSERT INTO t (id) VALUES (1)") }
            shouldThrow<TransactionAbortedException> { session.executeSql("SELECT * FROM t") }
            shouldThrow<TransactionAbortedException> { session.executeSql("BEGIN") }
            session.txnStatus shouldBe 'E'
            session.executeSql("ROLLBACK")
        }
    }

    @Test
    fun `E 상태에서 COMMIT은 오류 없이 ROLLBACK으로 처리된다`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            session.executeSql("INSERT INTO t (id) VALUES (2)")
            shouldThrow<UniqueViolationException> { session.executeSql("INSERT INTO t (id) VALUES (1)") }
            session.executeSql("COMMIT").shouldBeInstanceOf<ExecuteResult.TransactionRolledBack>()
            session.txnStatus shouldBe 'I'
            (session.executeSql("SELECT * FROM t") as ExecuteResult.Selected).rows.size shouldBe 1
        }
    }

    @Test
    fun `트랜잭션 중 파싱 오류와 바인딩 오류도 E 상태로 만든다`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            shouldThrow<BindException> { session.executeSql("SELECT * FROM nope") }
            session.txnStatus shouldBe 'E'
            session.executeSql("ROLLBACK")

            session.executeSql("BEGIN")
            shouldThrow<ParseException> { session.executeSql("SELEC 1") }
            session.txnStatus shouldBe 'E'
            session.executeSql("ROLLBACK")
        }
    }

    @Test
    fun `auto-commit 오류는 E 상태를 만들지 않는다`() {
        db.createSession().use { session ->
            shouldThrow<UniqueViolationException> { session.executeSql("INSERT INTO t (id) VALUES (1)") }
            session.txnStatus shouldBe 'I'
            session.executeSql("INSERT INTO t (id) VALUES (2)").shouldBeInstanceOf<ExecuteResult.Inserted>()
        }
    }

    @Test
    fun `E 상태의 트랜잭션은 잠금을 이미 해제했으므로 다른 세션이 대기 없이 진행한다`() {
        db.createSession().use { s1 ->
            s1.executeSql("BEGIN")
            s1.executeSql("UPDATE t SET id = 5 WHERE id = 1")
            shouldThrow<BindException> { s1.executeSql("SELECT * FROM nope") }

            db.createSession().use { s2 ->
                s2.lockTimeoutMillis = 500
                s2.executeSql("UPDATE t SET id = 7 WHERE id = 1").shouldBeInstanceOf<ExecuteResult.Updated>()
            }
            s1.executeSql("ROLLBACK")
        }
    }
}
