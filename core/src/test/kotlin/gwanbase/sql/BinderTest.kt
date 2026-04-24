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
}
