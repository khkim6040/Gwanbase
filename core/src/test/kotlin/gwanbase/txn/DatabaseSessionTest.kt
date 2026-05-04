package gwanbase.txn

import gwanbase.sql.ExecuteResult
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
}
