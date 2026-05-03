package gwanbase.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Parser 단위 테스트.
 *
 * TDD 원칙에 따라 구현 전에 작성한다.
 */
class ParserTest {

    private fun parse(sql: String): Statement {
        val tokens = Lexer(sql).tokenize()
        return Parser(tokens).parse()
    }

    private fun parseExpr(exprSql: String): Expression {
        val stmt = parse("SELECT $exprSql FROM dummy;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        val item = stmt.columns[0]
        item.shouldBeInstanceOf<SelectItem.ExprItem>()
        return item.expr
    }

    // ── DDL ──

    @Test
    fun `CREATE TABLE 단일 컬럼`() {
        val stmt = parse("CREATE TABLE users (id INT);")
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.tableName shouldBe "users"
        stmt.columns.size shouldBe 1
        stmt.columns[0].name shouldBe "id"
        stmt.columns[0].dataType.shouldBeInstanceOf<SqlDataType.IntType>()
        stmt.columns[0].nullable shouldBe true
    }

    @Test
    fun `CREATE TABLE 다중 컬럼 및 NOT NULL`() {
        val stmt = parse(
            "CREATE TABLE products (id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, price DOUBLE);"
        )
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.tableName shouldBe "products"
        stmt.columns.size shouldBe 3

        stmt.columns[0].name shouldBe "id"
        stmt.columns[0].dataType.shouldBeInstanceOf<SqlDataType.BigIntType>()
        stmt.columns[0].nullable shouldBe false

        stmt.columns[1].name shouldBe "name"
        val varcharType = stmt.columns[1].dataType.shouldBeInstanceOf<SqlDataType.VarcharType>()
        varcharType.maxLength shouldBe 100
        stmt.columns[1].nullable shouldBe false

        stmt.columns[2].name shouldBe "price"
        stmt.columns[2].dataType.shouldBeInstanceOf<SqlDataType.DoubleType>()
        stmt.columns[2].nullable shouldBe true
    }

    @Test
    fun `CREATE TABLE 모든 데이터 타입`() {
        val stmt = parse(
            """CREATE TABLE all_types (
                a BOOLEAN,
                b INT,
                c INTEGER,
                d BIGINT,
                e DOUBLE,
                f FLOAT,
                g TIMESTAMP,
                h VARCHAR(255)
            );"""
        )
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.columns.size shouldBe 8
        stmt.columns[0].dataType.shouldBeInstanceOf<SqlDataType.BooleanType>()
        stmt.columns[1].dataType.shouldBeInstanceOf<SqlDataType.IntType>()
        stmt.columns[2].dataType.shouldBeInstanceOf<SqlDataType.IntType>() // INTEGER → IntType
        stmt.columns[3].dataType.shouldBeInstanceOf<SqlDataType.BigIntType>()
        stmt.columns[4].dataType.shouldBeInstanceOf<SqlDataType.DoubleType>()
        stmt.columns[5].dataType.shouldBeInstanceOf<SqlDataType.DoubleType>() // FLOAT → DoubleType
        stmt.columns[6].dataType.shouldBeInstanceOf<SqlDataType.TimestampType>()
        val varchar = stmt.columns[7].dataType.shouldBeInstanceOf<SqlDataType.VarcharType>()
        varchar.maxLength shouldBe 255
    }

    @Test
    fun `DROP TABLE`() {
        val stmt = parse("DROP TABLE users;")
        stmt.shouldBeInstanceOf<Statement.DropTable>()
        stmt.tableName shouldBe "users"
    }

    // ── 표현식 ──

    @Test
    fun `리터럴 표현식`() {
        parseExpr("42").shouldBeInstanceOf<Expression.IntLiteral>().value shouldBe 42L
        parseExpr("3.14").shouldBeInstanceOf<Expression.FloatLiteral>().value shouldBe 3.14
        parseExpr("'hello'").shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "hello"
        parseExpr("TRUE").shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe true
        parseExpr("FALSE").shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe false
        parseExpr("NULL").shouldBeInstanceOf<Expression.NullLiteral>()
    }

    @Test
    fun `컬럼 참조`() {
        val expr = parseExpr("age")
        expr.shouldBeInstanceOf<Expression.ColumnRef>()
        expr.name shouldBe "age"
    }

    @Test
    fun `산술 연산자 우선순위 - 곱셈이 덧셈보다 높다`() {
        // a + b * c → BinaryOp(a, ADD, BinaryOp(b, MUL, c))
        val expr = parseExpr("a + b * c")
        expr.shouldBeInstanceOf<Expression.BinaryOp>()
        expr.op shouldBe BinaryOperator.ADD
        expr.left.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "a"
        val right = expr.right.shouldBeInstanceOf<Expression.BinaryOp>()
        right.op shouldBe BinaryOperator.MUL
        right.left.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "b"
        right.right.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "c"
    }

    @Test
    fun `괄호가 우선순위를 오버라이드한다`() {
        // (a + b) * c → BinaryOp(BinaryOp(a, ADD, b), MUL, c)
        val expr = parseExpr("(a + b) * c")
        expr.shouldBeInstanceOf<Expression.BinaryOp>()
        expr.op shouldBe BinaryOperator.MUL
        val left = expr.left.shouldBeInstanceOf<Expression.BinaryOp>()
        left.op shouldBe BinaryOperator.ADD
        left.left.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "a"
        left.right.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "b"
        expr.right.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "c"
    }

    @Test
    fun `비교 연산자`() {
        val expr = parseExpr("age >= 18")
        expr.shouldBeInstanceOf<Expression.BinaryOp>()
        expr.op shouldBe BinaryOperator.GTE
        expr.left.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "age"
        expr.right.shouldBeInstanceOf<Expression.IntLiteral>().value shouldBe 18L
    }

    @Test
    fun `AND OR 우선순위 - AND가 OR보다 높다`() {
        // a = 1 OR b = 2 AND c = 3 → OR( (a=1), AND( (b=2), (c=3) ) )
        val expr = parseExpr("a = 1 OR b = 2 AND c = 3")
        expr.shouldBeInstanceOf<Expression.BinaryOp>()
        expr.op shouldBe BinaryOperator.OR

        val left = expr.left.shouldBeInstanceOf<Expression.BinaryOp>()
        left.op shouldBe BinaryOperator.EQ

        val right = expr.right.shouldBeInstanceOf<Expression.BinaryOp>()
        right.op shouldBe BinaryOperator.AND
    }

    @Test
    fun `NOT 단항 연산자`() {
        val expr = parseExpr("NOT active")
        expr.shouldBeInstanceOf<Expression.UnaryOp>()
        expr.op shouldBe UnaryOperator.NOT
        expr.operand.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "active"
    }

    @Test
    fun `단항 부정`() {
        val expr = parseExpr("-price")
        expr.shouldBeInstanceOf<Expression.UnaryOp>()
        expr.op shouldBe UnaryOperator.NEGATE
        expr.operand.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "price"
    }

    @Test
    fun `IS NULL 표현식`() {
        val expr = parseExpr("name IS NULL")
        expr.shouldBeInstanceOf<Expression.IsNull>()
        expr.expr.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "name"
    }

    @Test
    fun `IS NOT NULL 표현식`() {
        val expr = parseExpr("name IS NOT NULL")
        expr.shouldBeInstanceOf<Expression.IsNotNull>()
        expr.expr.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "name"
    }

    // ── SELECT ──

    @Test
    fun `SELECT 전체 컬럼`() {
        val stmt = parse("SELECT * FROM users;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.columns.size shouldBe 1
        stmt.columns[0].shouldBeInstanceOf<SelectItem.Star>()
        stmt.from shouldBe FromClause.Table("users")
        stmt.where shouldBe null
        stmt.orderBy shouldBe null
        stmt.limit shouldBe null
    }

    @Test
    fun `SELECT WHERE 절`() {
        val stmt = parse("SELECT name, age FROM users WHERE age >= 18;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.columns.size shouldBe 2
        stmt.from shouldBe FromClause.Table("users")
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
    }

    @Test
    fun `SELECT ORDER BY 절`() {
        val stmt = parse("SELECT * FROM users ORDER BY name DESC;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.orderBy shouldBe OrderByClause("name", ascending = false)
    }

    @Test
    fun `SELECT LIMIT 절`() {
        val stmt = parse("SELECT * FROM users LIMIT 10;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.limit shouldBe 10
    }

    @Test
    fun `SELECT 전체 절 결합`() {
        val stmt = parse("SELECT name FROM users WHERE age > 20 ORDER BY name ASC LIMIT 5;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.from shouldBe FromClause.Table("users")
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
        stmt.orderBy shouldBe OrderByClause("name", ascending = true)
        stmt.limit shouldBe 5
    }

    // ── INSERT ──

    @Test
    fun `INSERT 기본`() {
        val stmt = parse("INSERT INTO users (id, name) VALUES (1, 'Alice');")
        stmt.shouldBeInstanceOf<Statement.Insert>()
        stmt.tableName shouldBe "users"
        stmt.columns shouldBe listOf("id", "name")
        stmt.values.size shouldBe 2
        stmt.values[0].shouldBeInstanceOf<Expression.IntLiteral>().value shouldBe 1L
        stmt.values[1].shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "Alice"
    }

    @Test
    fun `INSERT NULL 값 포함`() {
        val stmt = parse("INSERT INTO t (a, b) VALUES (NULL, TRUE);")
        stmt.shouldBeInstanceOf<Statement.Insert>()
        stmt.values[0].shouldBeInstanceOf<Expression.NullLiteral>()
        stmt.values[1].shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe true
    }

    // ── UPDATE ──

    @Test
    fun `UPDATE 단일 SET`() {
        val stmt = parse("UPDATE users SET name = 'Bob' WHERE id = 1;")
        stmt.shouldBeInstanceOf<Statement.Update>()
        stmt.tableName shouldBe "users"
        stmt.assignments.size shouldBe 1
        stmt.assignments[0].column shouldBe "name"
        stmt.assignments[0].value.shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "Bob"
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
    }

    @Test
    fun `UPDATE 다중 SET WHERE 없음`() {
        val stmt = parse("UPDATE t SET a = 1, b = 2;")
        stmt.shouldBeInstanceOf<Statement.Update>()
        stmt.assignments.size shouldBe 2
        stmt.where shouldBe null
    }

    // ── DELETE ──

    @Test
    fun `DELETE WHERE 있음`() {
        val stmt = parse("DELETE FROM users WHERE id = 1;")
        stmt.shouldBeInstanceOf<Statement.Delete>()
        stmt.tableName shouldBe "users"
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
    }

    @Test
    fun `DELETE WHERE 없음 - 전체 삭제`() {
        val stmt = parse("DELETE FROM users;")
        stmt.shouldBeInstanceOf<Statement.Delete>()
        stmt.where shouldBe null
    }

    // ── SELECT 추가 ──

    @Test
    fun `SELECT WHERE ORDER BY LIMIT 조합`() {
        val stmt = parse("SELECT * FROM users WHERE age > 18 ORDER BY name DESC LIMIT 10;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>().op shouldBe BinaryOperator.GT
        stmt.orderBy shouldBe OrderByClause("name", ascending = false)
        stmt.limit shouldBe 10
    }

    @Test
    fun `복합 WHERE 조건`() {
        val stmt = parse("SELECT * FROM t WHERE a > 1 AND b = 'x' OR c IS NULL;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        val where = stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
        where.op shouldBe BinaryOperator.OR
        where.left.shouldBeInstanceOf<Expression.BinaryOp>().op shouldBe BinaryOperator.AND
        where.right.shouldBeInstanceOf<Expression.IsNull>()
    }

    // ── 에러 케이스 ──

    @Test
    fun `불완전한 SQL 문은 에러`() {
        assertThrows<ParseException> { parse("SELECT") }
        assertThrows<ParseException> { parse("CREATE") }
        assertThrows<ParseException> { parse("INSERT INTO") }
    }

    @Test
    fun `SQL 문 뒤에 추가 토큰이 있으면 에러`() {
        assertThrows<ParseException> { parse("SELECT * FROM t; SELECT * FROM t;") }
    }

    @Test
    fun `세미콜론 없이도 파싱된다`() {
        val stmt = parse("SELECT * FROM users")
        stmt.shouldBeInstanceOf<Statement.Select>()
    }

    // ── 트랜잭션 제어 ──

    @Test
    fun `BEGIN 문을 파싱한다`() {
        val stmt = parse("BEGIN")
        stmt shouldBe Statement.Begin
    }

    @Test
    fun `COMMIT 문을 파싱한다`() {
        val stmt = parse("COMMIT")
        stmt shouldBe Statement.Commit
    }

    @Test
    fun `ROLLBACK 문을 파싱한다`() {
        val stmt = parse("ROLLBACK")
        stmt shouldBe Statement.Rollback
    }

    @Test
    fun `BEGIN 문 뒤에 세미콜론을 허용한다`() {
        val stmt = parse("BEGIN;")
        stmt shouldBe Statement.Begin
    }

    // ── CREATE INDEX / DROP INDEX / ANALYZE / EXPLAIN ──

    @Test
    fun `CREATE INDEX 파싱`() {
        val stmt = parse("CREATE INDEX idx_age ON users (age);")
        stmt shouldBe Statement.CreateIndex("idx_age", "users", "age")
    }

    @Test
    fun `CREATE INDEX 세미콜론 없이 파싱`() {
        val stmt = parse("CREATE INDEX idx_name ON products (name)")
        stmt shouldBe Statement.CreateIndex("idx_name", "products", "name")
    }

    @Test
    fun `DROP INDEX 파싱`() {
        val stmt = parse("DROP INDEX idx_age;")
        stmt shouldBe Statement.DropIndex("idx_age")
    }

    @Test
    fun `DROP INDEX 세미콜론 없이 파싱`() {
        val stmt = parse("DROP INDEX idx_name")
        stmt shouldBe Statement.DropIndex("idx_name")
    }

    @Test
    fun `ANALYZE 파싱`() {
        val stmt = parse("ANALYZE users;")
        stmt shouldBe Statement.Analyze("users")
    }

    @Test
    fun `ANALYZE 세미콜론 없이 파싱`() {
        val stmt = parse("ANALYZE products")
        stmt shouldBe Statement.Analyze("products")
    }

    @Test
    fun `EXPLAIN SELECT 파싱`() {
        val stmt = parse("EXPLAIN SELECT * FROM users;")
        stmt.shouldBeInstanceOf<Statement.Explain>()
        val inner = stmt.statement.shouldBeInstanceOf<Statement.Select>()
        inner.from shouldBe FromClause.Table("users")
    }

    @Test
    fun `EXPLAIN SELECT WHERE 파싱`() {
        val stmt = parse("EXPLAIN SELECT name FROM users WHERE age > 18;")
        stmt.shouldBeInstanceOf<Statement.Explain>()
        val inner = stmt.statement.shouldBeInstanceOf<Statement.Select>()
        inner.from shouldBe FromClause.Table("users")
        inner.where.shouldBeInstanceOf<Expression.BinaryOp>()
    }

    @Test
    fun `CREATE 뒤에 잘못된 토큰은 에러`() {
        assertThrows<ParseException> { parse("CREATE SOMETHING") }
    }

    @Test
    fun `DROP 뒤에 잘못된 토큰은 에러`() {
        assertThrows<ParseException> { parse("DROP SOMETHING") }
    }
}
