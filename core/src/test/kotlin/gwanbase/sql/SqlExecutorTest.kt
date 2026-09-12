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

    @Test
    fun `PRIMARY KEY 컬럼은 NOT NULL이며 table_pkey 유일 인덱스가 생성된다`() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
        database.getTable("users")!!.schema.column(0).nullable shouldBe false
        val idx = database.getCatalog().getIndex("users_pkey")
        idx.shouldNotBeNull()
        idx.columnName shouldBe "id"
        idx.unique shouldBe true
    }

    @Test
    fun `UNIQUE 컬럼은 table_col_key 유일 인덱스가 생성된다`() {
        executor.execute("CREATE TABLE users (id INT, email VARCHAR(50) UNIQUE)")
        val idx = database.getCatalog().getIndex("users_email_key")
        idx.shouldNotBeNull()
        idx.columnName shouldBe "email"
        idx.unique shouldBe true
    }

    @Test
    fun `PRIMARY KEY 중복 삽입 시 UniqueViolationException`() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
        executor.execute("INSERT INTO users (id, name) VALUES (1, 'a')")
        val e = assertThrows<UniqueViolationException> {
            executor.execute("INSERT INTO users (id, name) VALUES (1, 'b')")
        }
        e.indexName shouldBe "users_pkey"
    }

    @Test
    fun `UPDATE로 다른 행의 PRIMARY KEY 값과 충돌 시 UniqueViolationException`() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
        executor.execute("INSERT INTO users (id, name) VALUES (1, 'a')")
        executor.execute("INSERT INTO users (id, name) VALUES (2, 'b')")
        assertThrows<UniqueViolationException> {
            executor.execute("UPDATE users SET id = 1 WHERE id = 2")
        }
    }

    @Test
    fun `CREATE UNIQUE INDEX 후 중복 삽입 시 UniqueViolationException`() {
        executor.execute("CREATE TABLE users (id INT, email VARCHAR(50))")
        executor.execute("CREATE UNIQUE INDEX users_email_key ON users (email)")
        executor.execute("INSERT INTO users (id, email) VALUES (1, 'a@x.com')")
        assertThrows<UniqueViolationException> {
            executor.execute("INSERT INTO users (id, email) VALUES (2, 'a@x.com')")
        }
    }

    @Test
    fun `UNIQUE VARCHAR 컬럼에 접두사를 공유하는 다른 값은 삽입된다`() {
        executor.execute("CREATE TABLE tags (name VARCHAR(50) UNIQUE)")
        executor.execute("INSERT INTO tags (name) VALUES ('abcd')")
        // 'abcd'가 먼저 있어도 'abc'는 다른 값이므로 23505가 아니어야 한다.
        // 'abc' 삽입 시 등가 검사가 [abc, successor(abc)) 구간을 스캔하는데,
        // 종단 바이트가 없으면 이 구간에 'abcd'+RID가 잘못 포함되어 false positive가 난다.
        executor.execute("INSERT INTO tags (name) VALUES ('abc')")
        val result = executor.execute("SELECT name FROM tags") as ExecuteResult.Selected
        result.rows.size shouldBe 2
    }

    @Test
    fun `DROP TABLE 후 같은 이름의 PRIMARY KEY 테이블을 다시 만들 수 있다`() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY)")
        executor.execute("DROP TABLE users")
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY)")
        database.getCatalog().getIndex("users_pkey").shouldNotBeNull()
    }

    // ── 1b. CHECK 제약 ──

    @Test
    fun `CHECK 위반 시 ConstraintViolationException 23514`() {
        executor.execute("CREATE TABLE t (id INT, age INT CHECK (age >= 0))")
        val e = assertThrows<ConstraintViolationException> {
            executor.execute("INSERT INTO t (id, age) VALUES (1, -1)")
        }
        e.sqlState shouldBe "23514"
        e.constraintName shouldBe "t_age_check"
        e.message shouldBe "new row for relation \"t\" violates check constraint \"t_age_check\""
        (executor.execute("SELECT * FROM t") as ExecuteResult.Selected).rows.size shouldBe 0
    }

    @Test
    fun `CHECK 결과가 NULL이면 통과한다`() {
        executor.execute("CREATE TABLE t (id INT, age INT CHECK (age >= 0))")
        executor.execute("INSERT INTO t (id, age) VALUES (1, NULL)")
        (executor.execute("SELECT * FROM t") as ExecuteResult.Selected).rows.size shouldBe 1
    }

    @Test
    fun `CHECK는 같은 테이블의 다른 컬럼을 참조할 수 있고 UPDATE에도 적용된다`() {
        executor.execute("CREATE TABLE r (lo INT, hi INT CHECK (lo < hi))")
        executor.execute("INSERT INTO r (lo, hi) VALUES (1, 10)")
        assertThrows<ConstraintViolationException> {
            executor.execute("UPDATE r SET lo = 20 WHERE hi = 10")
        }
        (executor.execute("SELECT lo FROM r") as ExecuteResult.Selected).rows[0][0] shouldBe 1
    }

    @Test
    fun `CHECK 제약은 DB 재오픈 후에도 유지된다`() {
        executor.execute("CREATE TABLE t (id INT, age INT CHECK (age >= 0 AND age < 200))")
        database.close()
        database = Database.open(tempDir.resolve("test.db"))
        executor = SqlExecutor(database)
        assertThrows<ConstraintViolationException> {
            executor.execute("INSERT INTO t (id, age) VALUES (1, 300)")
        }
        executor.execute("INSERT INTO t (id, age) VALUES (1, 30)")
    }

    // ── 1c. FOREIGN KEY 제약 ──

    private fun setUpParentChild() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
        executor.execute("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id))")
        executor.execute("INSERT INTO users (id, name) VALUES (1, 'a')")
    }

    @Test
    fun `부모 행이 없는 자식 INSERT 시 ConstraintViolationException 23503`() {
        setUpParentChild()
        val e = assertThrows<ConstraintViolationException> {
            executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 999)")
        }
        e.sqlState shouldBe "23503"
        e.constraintName shouldBe "orders_user_id_fkey"
        e.message shouldBe "insert or update on table \"orders\" violates foreign key constraint \"orders_user_id_fkey\""
        (executor.execute("SELECT * FROM orders") as ExecuteResult.Selected).rows.size shouldBe 0
    }

    @Test
    fun `부모 행이 있거나 FK 값이 NULL이면 자식 INSERT 성공`() {
        setUpParentChild()
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        executor.execute("INSERT INTO orders (id, user_id) VALUES (11, NULL)")
        (executor.execute("SELECT * FROM orders") as ExecuteResult.Selected).rows.size shouldBe 2
    }

    @Test
    fun `자식 UPDATE로 없는 부모를 참조하면 23503`() {
        setUpParentChild()
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        assertThrows<ConstraintViolationException> {
            executor.execute("UPDATE orders SET user_id = 999 WHERE id = 10")
        }
    }

    @Test
    fun `자식이 참조하는 부모 DELETE 시 23503`() {
        setUpParentChild()
        executor.execute("INSERT INTO users (id, name) VALUES (2, 'b')")
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        val e = assertThrows<ConstraintViolationException> {
            executor.execute("DELETE FROM users WHERE id = 1")
        }
        e.sqlState shouldBe "23503"
        e.message shouldBe "update or delete on table \"users\" violates foreign key constraint \"orders_user_id_fkey\" on table \"orders\""
        (executor.execute("SELECT * FROM users") as ExecuteResult.Selected).rows.size shouldBe 2
        executor.execute("DELETE FROM users WHERE id = 2") shouldBe ExecuteResult.Deleted(1)
    }

    @Test
    fun `자식 행을 지운 뒤에는 부모 DELETE 성공`() {
        setUpParentChild()
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        executor.execute("DELETE FROM orders WHERE id = 10")
        executor.execute("DELETE FROM users WHERE id = 1") shouldBe ExecuteResult.Deleted(1)
    }

    @Test
    fun `자식이 참조하는 부모 키 UPDATE 시 23503, 같은 값이면 통과`() {
        setUpParentChild()
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        assertThrows<ConstraintViolationException> {
            executor.execute("UPDATE users SET id = 2 WHERE id = 1")
        }
        executor.execute("UPDATE users SET id = 1, name = 'z' WHERE id = 1") shouldBe ExecuteResult.Updated(1)
    }

    @Test
    fun `REFERENCES에 컬럼을 생략하면 부모 PRIMARY KEY를 참조한다`() {
        executor.execute("CREATE TABLE users (id INT PRIMARY KEY)")
        executor.execute("CREATE TABLE orders (id INT, user_id INT REFERENCES users)")
        database.getCatalog().getForeignKeysForTable("orders")[0].refColumnName shouldBe "id"
        assertThrows<ConstraintViolationException> {
            executor.execute("INSERT INTO orders (id, user_id) VALUES (1, 5)")
        }
    }

    @Test
    fun `자식 컬럼에 인덱스가 있어도 부모 DELETE 검사가 동작한다`() {
        setUpParentChild()
        executor.execute("CREATE INDEX orders_user_idx ON orders (user_id)")
        executor.execute("INSERT INTO orders (id, user_id) VALUES (10, 1)")
        assertThrows<ConstraintViolationException> {
            executor.execute("DELETE FROM users WHERE id = 1")
        }
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

    // ── 데이터 예외 (SQLSTATE Class 22) ──

    @Test
    fun `VARCHAR 길이 초과 INSERT 시 string_data_right_truncation 22001`() {
        executor.execute("CREATE TABLE t (name VARCHAR(3))")
        val e = assertThrows<DataException> { executor.execute("INSERT INTO t (name) VALUES ('abcd')") }
        e.sqlState shouldBe "22001"
    }

    @Test
    fun `VARCHAR 길이 이내 INSERT는 성공`() {
        executor.execute("CREATE TABLE t (name VARCHAR(3))")
        (executor.execute("INSERT INTO t (name) VALUES ('abc')") is ExecuteResult.Inserted) shouldBe true
    }

    @Test
    fun `INT 범위 초과 INSERT 시 numeric_value_out_of_range 22003`() {
        executor.execute("CREATE TABLE t (n INT)")
        val e = assertThrows<DataException> { executor.execute("INSERT INTO t (n) VALUES (2147483648)") }
        e.sqlState shouldBe "22003"
    }

    @Test
    fun `INT 범위 초과 UPDATE 시 numeric_value_out_of_range 22003`() {
        executor.execute("CREATE TABLE t (n INT)")
        executor.execute("INSERT INTO t (n) VALUES (1)")
        val e = assertThrows<DataException> { executor.execute("UPDATE t SET n = -2147483649") }
        e.sqlState shouldBe "22003"
    }

    @Test
    fun `SELECT에서 0으로 나누면 division_by_zero 22012`() {
        executor.execute("CREATE TABLE t (n INT)")
        executor.execute("INSERT INTO t (n) VALUES (1)")
        val e = assertThrows<DataException> { executor.execute("SELECT n / 0 FROM t") }
        e.sqlState shouldBe "22012"
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
