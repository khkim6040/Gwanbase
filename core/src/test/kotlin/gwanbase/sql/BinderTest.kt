package gwanbase.sql

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BinderTest {
    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var binder: Binder

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        database.createTable("users", Schema(listOf(
            Column("id", DataType.INT32, nullable = false),
            Column("name", DataType.VARCHAR, maxLength = 100),
            Column("age", DataType.INT32),
        )))
        binder = Binder(database.getCatalog())
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() { database.close() }

    private fun parse(sql: String): Statement {
        val tokens = Lexer(sql).tokenize()
        return Parser(tokens).parse()
    }

    @Test
    fun `정상 SELECT는 바인딩을 통과한다`() {
        val stmt = parse("SELECT id, name FROM users WHERE age > 10")
        val result = binder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `정상 INSERT는 바인딩을 통과한다`() {
        val stmt = parse("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        val result = binder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `존재하지 않는 테이블 SELECT 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("SELECT * FROM nonexistent"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `존재하지 않는 테이블 INSERT 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("INSERT INTO nonexistent (id) VALUES (1)"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `존재하지 않는 테이블 UPDATE 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("UPDATE nonexistent SET id = 1"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `존재하지 않는 테이블 DELETE 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("DELETE FROM nonexistent"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `존재하지 않는 컬럼 SELECT 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("SELECT unknown_col FROM users"))
        }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `존재하지 않는 컬럼 WHERE 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("SELECT * FROM users WHERE unknown_col = 1"))
        }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `INSERT 컬럼명이 스키마에 없으면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("INSERT INTO users (id, unknown_col) VALUES (1, 'x')"))
        }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `ORDER BY 컬럼이 스키마에 없으면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("SELECT * FROM users ORDER BY unknown_col"))
        }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `UPDATE SET 컬럼이 스키마에 없으면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("UPDATE users SET unknown_col = 1"))
        }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `NOT NULL 컬럼에 NULL 삽입 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("INSERT INTO users (id, name) VALUES (NULL, 'Alice')"))
        }
        ex.message shouldContain "id"
    }

    @Test
    fun `INSERT 컬럼 수와 값 수가 다르면 에러`() {
        val stmt = parse("INSERT INTO users (id, name) VALUES (1);")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `INSERT 중복 컬럼 지정 시 에러`() {
        val stmt = parse("INSERT INTO users (id, id) VALUES (1, 2);")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `INSERT 시 NOT NULL 컬럼 누락 에러`() {
        val stmt = parse("INSERT INTO users (name) VALUES ('Alice');")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `UPDATE SET에서 NOT NULL 컬럼에 NULL 대입 시 에러`() {
        val stmt = parse("UPDATE users SET id = NULL WHERE name = 'Alice';")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `중복 테이블명 CREATE 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("CREATE TABLE users (id INT)"))
        }
        ex.message shouldContain "users"
    }

    @Test
    fun `존재하지 않는 테이블 DROP 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("DROP TABLE nonexistent"))
        }
        ex.message shouldContain "nonexistent"
    }

    // ── JOIN 다중 테이블 바인딩 ──

    @Test
    fun `JOIN 양쪽 테이블 컬럼 검증 통과`() {
        database.createTable("orders", Schema(listOf(
            Column("order_id", DataType.INT32, nullable = false),
            Column("user_id", DataType.INT32, nullable = false),
            Column("amount", DataType.INT32),
        )))
        val freshBinder = Binder(database.getCatalog())
        val stmt = parse("SELECT u.id, o.amount FROM users u JOIN orders o ON u.id = o.user_id")
        val result = freshBinder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `JOIN에서 존재하지 않는 컬럼 참조 시 에러`() {
        database.createTable("orders", Schema(listOf(
            Column("order_id", DataType.INT32, nullable = false),
            Column("user_id", DataType.INT32, nullable = false),
        )))
        val freshBinder = Binder(database.getCatalog())
        val ex = assertThrows<BindException> {
            freshBinder.bind(parse("SELECT u.nonexistent FROM users u JOIN orders o ON u.id = o.user_id"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `비한정 컬럼이 한쪽 테이블에만 존재하면 통과`() {
        database.createTable("orders", Schema(listOf(
            Column("order_id", DataType.INT32, nullable = false),
            Column("user_id", DataType.INT32, nullable = false),
        )))
        val freshBinder = Binder(database.getCatalog())
        // 'name'은 users에만 존재 → 통과
        val stmt = parse("SELECT name FROM users u JOIN orders o ON u.id = o.user_id")
        val result = freshBinder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `비한정 컬럼이 양쪽 테이블에 존재하면 ambiguous 에러`() {
        database.createTable("orders", Schema(listOf(
            Column("id", DataType.INT32, nullable = false),
            Column("user_id", DataType.INT32, nullable = false),
        )))
        val freshBinder = Binder(database.getCatalog())
        // 'id'는 users와 orders 양쪽에 존재 → ambiguous
        val ex = assertThrows<BindException> {
            freshBinder.bind(parse("SELECT id FROM users u JOIN orders o ON u.id = o.user_id"))
        }
        ex.message shouldContain "모호"
    }

    @Test
    fun `JOIN에서 존재하지 않는 테이블 참조 시 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("SELECT * FROM users u JOIN nonexistent n ON u.id = n.user_id"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `JOIN에서 잘못된 테이블 한정자 사용 시 에러`() {
        database.createTable("orders", Schema(listOf(
            Column("order_id", DataType.INT32, nullable = false),
            Column("user_id", DataType.INT32, nullable = false),
        )))
        val freshBinder = Binder(database.getCatalog())
        val ex = assertThrows<BindException> {
            freshBinder.bind(parse("SELECT x.id FROM users u JOIN orders o ON u.id = o.user_id"))
        }
        ex.message shouldContain "x"
    }

    // ── CREATE INDEX / ANALYZE 바인딩 ──

    @Test
    fun `CREATE INDEX 존재하는 테이블과 컬럼이면 통과`() {
        val stmt = parse("CREATE INDEX idx_age ON users (age)")
        val result = binder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `CREATE INDEX 존재하지 않는 테이블이면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("CREATE INDEX idx ON nonexistent (col)"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `CREATE INDEX 존재하지 않는 컬럼이면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("CREATE INDEX idx ON users (nonexistent)"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `ANALYZE 존재하는 테이블이면 통과`() {
        val stmt = parse("ANALYZE users")
        val result = binder.bind(stmt)
        result shouldBe stmt
    }

    @Test
    fun `ANALYZE 존재하지 않는 테이블이면 에러`() {
        val ex = assertThrows<BindException> {
            binder.bind(parse("ANALYZE nonexistent"))
        }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `EXPLAIN 내부 문이 바인딩된다`() {
        val stmt = parse("EXPLAIN SELECT * FROM users")
        val result = binder.bind(stmt)
        result shouldBe stmt
    }
}
