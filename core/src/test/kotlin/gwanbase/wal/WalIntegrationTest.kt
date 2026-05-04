package gwanbase.wal

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WalIntegrationTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun `executeSql로 CREATE TABLE + INSERT 후 SELECT 성공`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        database.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")

        val result = database.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 1
        result.rows[0][1] shouldBe "Alice"
    }

    @Test
    fun `close 시 checkpoint가 기록된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (1)")
        database.close()

        val logManager = LogManager(tempDir.resolve("test.db.wal"))
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.any { it is LogRecord.Checkpoint } shouldBe true
        logManager.close()
    }

    @Test
    fun `정상 종료 후 재시작 시 데이터가 유지된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        database.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")
        database.executeSql("INSERT INTO t (id, name) VALUES (2, 'Bob')")
        database.close()

        val reopened = Database.open(tempDir.resolve("test.db"))
        val result = reopened.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 2
        reopened.close()
    }

    @Test
    fun `Recovery 후 커밋된 트랜잭션 데이터가 복구된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (42)")
        database.close()

        val reopened = Database.open(tempDir.resolve("test.db"))
        val result = reopened.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 42
        reopened.close()
    }

    @Test
    fun `executeSql — DML 실행 시 WAL 로그가 기록된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (1)")

        val logPath = tempDir.resolve("test.db.wal")
        logPath.toFile().exists() shouldBe true

        val logManager = LogManager(logPath)
        val records = logManager.forwardIterator(0).asSequence().toList()
        (records.size >= 4) shouldBe true
        records.any { it is LogRecord.Begin } shouldBe true
        records.any { it is LogRecord.Commit } shouldBe true
        records.any { it is LogRecord.Update } shouldBe true
        logManager.close()
    }
}
