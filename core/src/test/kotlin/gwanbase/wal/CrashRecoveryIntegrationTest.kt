package gwanbase.wal

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Crash Recovery 통합 테스트.
 *
 * crash 시뮬레이션 전략: [Database.closeWithoutCheckpoint]를 호출하여
 * checkpoint 없이 리소스를 닫는다. WAL 로그는 커밋 시점에 이미 flush되었으므로
 * 디스크에 존재하지만, dirty page는 flush되지 않는다.
 * 재오픈 시 Recovery가 WAL을 replay하여 데이터 무결성을 복원한다.
 */
class CrashRecoveryIntegrationTest {

    @TempDir lateinit var tempDir: Path

    private fun dbPath() = tempDir.resolve("crash_test.db")

    @Test
    fun `커밋된 INSERT는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        db.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")
        db.executeSql("INSERT INTO t (id, name) VALUES (2, 'Bob')")
        db.closeWithoutCheckpoint()  // crash simulation

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 2
        recovered.close()
    }

    @Test
    fun `커밋된 UPDATE는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, value INT NOT NULL)")
        db.executeSql("INSERT INTO t (id, value) VALUES (1, 100)")
        db.executeSql("UPDATE t SET value = 200 WHERE id = 1")
        db.closeWithoutCheckpoint()

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT value FROM t WHERE id = 1") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 200
        recovered.close()
    }

    @Test
    fun `커밋된 DELETE는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL)")
        db.executeSql("INSERT INTO t (id) VALUES (1)")
        db.executeSql("INSERT INTO t (id) VALUES (2)")
        db.executeSql("DELETE FROM t WHERE id = 1")
        db.closeWithoutCheckpoint()

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 2
        recovered.close()
    }

    @Test
    fun `CREATE TABLE은 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE alpha (id INT NOT NULL)")
        db.executeSql("CREATE TABLE beta (name VARCHAR(100) NOT NULL)")
        db.closeWithoutCheckpoint()

        val recovered = Database.open(dbPath())
        recovered.executeSql("INSERT INTO alpha (id) VALUES (1)")
        recovered.executeSql("INSERT INTO beta (name) VALUES ('test')")
        val r1 = recovered.executeSql("SELECT * FROM alpha") as ExecuteResult.Selected
        val r2 = recovered.executeSql("SELECT * FROM beta") as ExecuteResult.Selected
        r1.rows.size shouldBe 1
        r2.rows.size shouldBe 1
        recovered.close()
    }

    @Test
    fun `Checkpoint 이후 추가 INSERT + crash 시 전체 데이터 무결성 유지`() {
        val db1 = Database.open(dbPath())
        db1.executeSql("CREATE TABLE t (id INT NOT NULL)")
        db1.executeSql("INSERT INTO t (id) VALUES (1)")
        db1.executeSql("INSERT INTO t (id) VALUES (2)")
        db1.close() // checkpoint 포함

        val db2 = Database.open(dbPath())
        db2.executeSql("INSERT INTO t (id) VALUES (3)")
        db2.closeWithoutCheckpoint() // crash

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 3
        recovered.close()
    }

    @Test
    fun `abort 중 CLR 기록 후 crash 시 복구 정합성`() {
        // 1. 테이블 생성 및 커밋된 데이터 삽입
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        db.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")

        // 2. 명시적 트랜잭션 내에서 INSERT 후 ROLLBACK → CLR이 기록되어야 한다
        val session = db.createSession()
        session.executeSql("BEGIN")
        session.executeSql("INSERT INTO t (id, name) VALUES (2, 'Bob')")
        session.executeSql("ROLLBACK")

        // 3. crash 시뮬레이션 (checkpoint 없이 닫기)
        db.closeWithoutCheckpoint()

        // 4. Recovery 수행 후 rollback된 행이 존재하지 않아야 한다
        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 1
        result.rows[0][1] shouldBe "Alice"
        recovered.close()
    }

    @Test
    fun `abort된 UPDATE는 crash 후 recovery에서 원래 값으로 복원된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, value INT NOT NULL)")
        db.executeSql("INSERT INTO t (id, value) VALUES (1, 100)")

        // 명시적 트랜잭션 내에서 UPDATE 후 ROLLBACK
        val session = db.createSession()
        session.executeSql("BEGIN")
        session.executeSql("UPDATE t SET value = 999 WHERE id = 1")
        session.executeSql("ROLLBACK")

        db.closeWithoutCheckpoint()

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT value FROM t WHERE id = 1") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 100
        recovered.close()
    }

    @Test
    fun `여러 트랜잭션 커밋 후 crash 시 모든 데이터 복구`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL)")
        for (i in 1..50) {
            db.executeSql("INSERT INTO t (id) VALUES ($i)")
        }
        db.closeWithoutCheckpoint()

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 50
        recovered.close()
    }
}
