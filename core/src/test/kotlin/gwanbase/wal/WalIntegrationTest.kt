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
