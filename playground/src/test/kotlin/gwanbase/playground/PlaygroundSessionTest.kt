package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlaygroundSessionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var session: PlaygroundSession

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("t.db"))
        db.executeSql("CREATE TABLE t (id INT PRIMARY KEY)")
        session = PlaygroundSession(db.createSession())
    }

    @AfterEach
    fun tearDown() {
        session.close()
        db.close()
    }

    @Test
    fun `auto-commit 상태는 I이고 BEGIN 후 T COMMIT 후 다시 I다`() {
        session.txnStatus shouldBe 'I'
        session.execute("BEGIN")
        session.txnStatus shouldBe 'T'
        session.execute("INSERT INTO t (id) VALUES (1)")
        session.txnStatus shouldBe 'T'
        session.execute("COMMIT")
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `트랜잭션 중 바인딩 오류가 나면 E가 되고 ROLLBACK 전까지 명령을 거부한다`() {
        session.execute("BEGIN")
        assertThrows<gwanbase.sql.BindException> { session.execute("SELECT * FROM nope") }
        session.txnStatus shouldBe 'E'

        assertThrows<TransactionAbortedException> { session.execute("SELECT * FROM t") }
        session.txnStatus shouldBe 'E'

        session.execute("ROLLBACK") shouldBe ExecuteResult.TransactionRolledBack
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `트랜잭션 중 실행 오류(UNIQUE 위반)가 나도 ROLLBACK으로 I로 돌아온다`() {
        session.execute("INSERT INTO t (id) VALUES (1)")
        session.execute("BEGIN")
        assertThrows<gwanbase.table.UniqueViolationException> { session.execute("INSERT INTO t (id) VALUES (1)") }
        session.txnStatus shouldBe 'E'

        session.execute("ROLLBACK") shouldBe ExecuteResult.TransactionRolledBack
        session.txnStatus shouldBe 'I'
        session.execute("SELECT * FROM t") // 정상 실행되어야 한다
    }

    @Test
    fun `auto-commit 오류는 상태를 바꾸지 않는다`() {
        assertThrows<gwanbase.sql.BindException> { session.execute("SELECT * FROM nope") }
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `execute는 lastUsedAt을 갱신한다`() {
        session.lastUsedAt = 0L
        session.execute("SELECT * FROM t")
        (session.lastUsedAt > 0L) shouldBe true
    }
}
