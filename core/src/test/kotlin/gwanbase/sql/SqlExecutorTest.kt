package gwanbase.sql

import gwanbase.table.*
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqlExecutorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var executor: SqlExecutor

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        executor = SqlExecutor(database)
    }

    @AfterEach
    fun tearDown() { database.close() }

    // ── 1. CREATE TABLE ──

    @Test
    fun `CREATE TABLE 후 테이블이 생성된다`() {
        val result = executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50), score INT)")
        result shouldBe ExecuteResult.Created("students")
        // 테이블이 실제로 존재하는지 확인
        val tableInfo = database.getTable("students")
        tableInfo.shouldNotBeNull()
        tableInfo.schema.columnCount shouldBe 3
    }

    // ── 2. DROP TABLE ──

    @Test
    fun `DROP TABLE 후 테이블이 삭제된다`() {
        executor.execute("CREATE TABLE temp (id INT NOT NULL)")
        val result = executor.execute("DROP TABLE temp")
        result shouldBe ExecuteResult.Dropped("temp")
        database.getTable("temp") shouldBe null
    }

    // ── 3. INSERT ──

    @Test
    fun `INSERT 후 행이 삽입된다`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")
        val result = executor.execute("INSERT INTO students (id, name) VALUES (1, 'Alice')")
        result as ExecuteResult.Inserted

        val selectResult = executor.execute("SELECT * FROM students") as ExecuteResult.Selected
        selectResult.rows.size shouldBe 1
        selectResult.rows[0][0] shouldBe 1
        selectResult.rows[0][1] shouldBe "Alice"
    }

    // ── 4. INSERT NULL ──

    @Test
    fun `INSERT NULL 값`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")
        executor.execute("INSERT INTO students (id, name) VALUES (1, NULL)")

        val result = executor.execute("SELECT * FROM students") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 1
        result.rows[0][1] shouldBe null
    }

    // ── 5. SELECT WHERE 비교 필터링 ──

    @Test
    fun `SELECT WHERE 비교 필터링`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50), score INT)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (1, 'Alice', 90)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (2, 'Bob', 70)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (3, 'Charlie', 85)")

        val result = executor.execute("SELECT name, score FROM students WHERE score >= 80") as ExecuteResult.Selected
        result.columns shouldBe listOf("name", "score")
        result.rows.size shouldBe 2
        val names = result.rows.map { it[0] }.toSet()
        names shouldBe setOf("Alice", "Charlie")
    }

    // ── 6. SELECT WHERE AND OR 복합 조건 ──

    @Test
    fun `SELECT WHERE AND OR 복합 조건`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50), score INT)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (1, 'Alice', 90)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (2, 'Bob', 70)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (3, 'Charlie', 85)")

        val result = executor.execute(
            "SELECT name FROM students WHERE score >= 85 OR name = 'Bob'"
        ) as ExecuteResult.Selected
        result.rows.size shouldBe 3
    }

    // ── 7. SELECT WHERE IS NULL ──

    @Test
    fun `SELECT WHERE IS NULL`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")
        executor.execute("INSERT INTO students (id, name) VALUES (1, 'Alice')")
        executor.execute("INSERT INTO students (id, name) VALUES (2, NULL)")

        val result = executor.execute("SELECT id FROM students WHERE name IS NULL") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 2
    }

    // ── 8. SELECT ORDER BY ASC ──

    @Test
    fun `SELECT ORDER BY ASC`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")
        executor.execute("INSERT INTO students (id, name) VALUES (1, 'Charlie')")
        executor.execute("INSERT INTO students (id, name) VALUES (2, 'Alice')")
        executor.execute("INSERT INTO students (id, name) VALUES (3, 'Bob')")

        val result = executor.execute("SELECT name FROM students ORDER BY name ASC") as ExecuteResult.Selected
        result.rows.map { it[0] } shouldBe listOf("Alice", "Bob", "Charlie")
    }

    // ── 9. SELECT ORDER BY DESC ──

    @Test
    fun `SELECT ORDER BY DESC`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, score INT)")
        executor.execute("INSERT INTO students (id, score) VALUES (1, 70)")
        executor.execute("INSERT INTO students (id, score) VALUES (2, 90)")
        executor.execute("INSERT INTO students (id, score) VALUES (3, 80)")

        val result = executor.execute("SELECT score FROM students ORDER BY score DESC") as ExecuteResult.Selected
        result.rows.map { it[0] } shouldBe listOf(90, 80, 70)
    }

    // ── 10. SELECT LIMIT ──

    @Test
    fun `SELECT LIMIT`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")
        executor.execute("INSERT INTO students (id, name) VALUES (1, 'Alice')")
        executor.execute("INSERT INTO students (id, name) VALUES (2, 'Bob')")
        executor.execute("INSERT INTO students (id, name) VALUES (3, 'Charlie')")

        val result = executor.execute("SELECT * FROM students LIMIT 2") as ExecuteResult.Selected
        result.rows.size shouldBe 2
    }

    // ── 11. 빈 테이블 SELECT ──

    @Test
    fun `빈 테이블 SELECT 시 빈 결과`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50))")

        val result = executor.execute("SELECT * FROM students") as ExecuteResult.Selected
        result.rows.size shouldBe 0
        result.columns shouldBe listOf("id", "name")
    }

    // ── 12. UPDATE WHERE 조건 ──

    @Test
    fun `UPDATE WHERE 조건으로 부분 업데이트`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, name VARCHAR(50), score INT)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (1, 'Alice', 80)")
        executor.execute("INSERT INTO students (id, name, score) VALUES (2, 'Bob', 70)")

        val result = executor.execute("UPDATE students SET score = 95 WHERE name = 'Alice'") as ExecuteResult.Updated
        result.count shouldBe 1

        val select = executor.execute("SELECT name, score FROM students WHERE name = 'Alice'") as ExecuteResult.Selected
        select.rows[0][1] shouldBe 95
    }

    // ── 13. UPDATE 전체 ──

    @Test
    fun `UPDATE WHERE 없이 전체 업데이트`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, score INT)")
        executor.execute("INSERT INTO students (id, score) VALUES (1, 80)")
        executor.execute("INSERT INTO students (id, score) VALUES (2, 70)")

        val result = executor.execute("UPDATE students SET score = 100") as ExecuteResult.Updated
        result.count shouldBe 2

        val select = executor.execute("SELECT score FROM students") as ExecuteResult.Selected
        select.rows.forEach { it[0] shouldBe 100 }
    }

    // ── 14. DELETE WHERE 조건 ──

    @Test
    fun `DELETE WHERE 조건으로 부분 삭제`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, score INT)")
        executor.execute("INSERT INTO students (id, score) VALUES (1, 80)")
        executor.execute("INSERT INTO students (id, score) VALUES (2, 70)")

        val result = executor.execute("DELETE FROM students WHERE score < 80") as ExecuteResult.Deleted
        result.count shouldBe 1

        val select = executor.execute("SELECT * FROM students") as ExecuteResult.Selected
        select.rows.size shouldBe 1
    }

    // ── 15. DELETE 전체 ──

    @Test
    fun `DELETE WHERE 없이 전체 삭제`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL, score INT)")
        executor.execute("INSERT INTO students (id, score) VALUES (1, 80)")
        executor.execute("INSERT INTO students (id, score) VALUES (2, 70)")

        val result = executor.execute("DELETE FROM students") as ExecuteResult.Deleted
        result.count shouldBe 2

        val select = executor.execute("SELECT * FROM students") as ExecuteResult.Selected
        select.rows.size shouldBe 0
    }

    // ── 16. DROP TABLE 후 SELECT 시 에러 ──

    @Test
    fun `DROP TABLE 후 SELECT 시 에러`() {
        executor.execute("CREATE TABLE students (id INT NOT NULL)")
        executor.execute("DROP TABLE students")

        assertThrows<BindException> {
            executor.execute("SELECT * FROM students")
        }
    }

    // ── 17. 모든 DataType 라운드트립 ──

    @Test
    fun `모든 DataType INSERT 후 SELECT 라운드트립`() {
        executor.execute(
            """CREATE TABLE all_types (
                b BOOLEAN,
                i INT,
                bi BIGINT,
                d DOUBLE,
                ts TIMESTAMP,
                v VARCHAR(100)
            )"""
        )
        executor.execute(
            "INSERT INTO all_types (b, i, bi, d, ts, v) VALUES (true, 42, 9999999999, 3.14, 1700000000000, 'hello')"
        )

        val result = executor.execute("SELECT * FROM all_types") as ExecuteResult.Selected
        result.columns shouldBe listOf("b", "i", "bi", "d", "ts", "v")
        val row = result.rows[0]
        row[0] shouldBe true
        row[1] shouldBe 42
        row[2] shouldBe 9999999999L
        row[3] shouldBe 3.14
        row[4] shouldBe 1700000000000L
        row[5] shouldBe "hello"
    }

    // ── 18. 엔드투엔드 ──

    @Test
    fun `엔드투엔드 - 테이블 생성부터 CRUD까지`() {
        // CREATE
        executor.execute("CREATE TABLE items (id INT NOT NULL, name VARCHAR(50), price INT)")

        // INSERT 3건
        executor.execute("INSERT INTO items (id, name, price) VALUES (1, 'Apple', 1000)")
        executor.execute("INSERT INTO items (id, name, price) VALUES (2, 'Banana', 500)")
        executor.execute("INSERT INTO items (id, name, price) VALUES (3, 'Cherry', 2000)")

        // SELECT WHERE + ORDER + LIMIT
        val select1 = executor.execute(
            "SELECT name, price FROM items WHERE price >= 1000 ORDER BY price DESC LIMIT 2"
        ) as ExecuteResult.Selected
        select1.rows.size shouldBe 2
        select1.rows[0][0] shouldBe "Cherry"
        select1.rows[0][1] shouldBe 2000
        select1.rows[1][0] shouldBe "Apple"
        select1.rows[1][1] shouldBe 1000

        // UPDATE
        val updated = executor.execute("UPDATE items SET price = 1500 WHERE name = 'Banana'") as ExecuteResult.Updated
        updated.count shouldBe 1

        // verify UPDATE
        val select2 = executor.execute(
            "SELECT price FROM items WHERE name = 'Banana'"
        ) as ExecuteResult.Selected
        select2.rows[0][0] shouldBe 1500

        // DELETE
        val deleted = executor.execute("DELETE FROM items WHERE name = 'Apple'") as ExecuteResult.Deleted
        deleted.count shouldBe 1

        // verify DELETE
        val select3 = executor.execute("SELECT * FROM items") as ExecuteResult.Selected
        select3.rows.size shouldBe 2

        // DROP
        executor.execute("DROP TABLE items")

        // verify DROP → error
        assertThrows<BindException> {
            executor.execute("SELECT * FROM items")
        }
    }
}
