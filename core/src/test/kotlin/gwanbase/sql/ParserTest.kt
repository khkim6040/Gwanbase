package gwanbase.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

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
        stmt.tableName shouldBe "users"
        stmt.where shouldBe null
        stmt.orderBy shouldBe null
        stmt.limit shouldBe null
    }

    @Test
    fun `SELECT WHERE 절`() {
        val stmt = parse("SELECT name, age FROM users WHERE age >= 18;")
        stmt.shouldBeInstanceOf<Statement.Select>()
        stmt.columns.size shouldBe 2
        stmt.tableName shouldBe "users"
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
        stmt.tableName shouldBe "users"
        stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
        stmt.orderBy shouldBe OrderByClause("name", ascending = true)
        stmt.limit shouldBe 5
    }

    // ── 에러 케이스 ──

    @Test
    fun `INSERT 문은 아직 구현되지 않았다`() {
        shouldThrow<ParseException> {
            parse("INSERT INTO users VALUES (1);")
        }
    }

    @Test
    fun `UPDATE 문은 아직 구현되지 않았다`() {
        shouldThrow<ParseException> {
            parse("UPDATE users SET name = 'a';")
        }
    }

    @Test
    fun `DELETE 문은 아직 구현되지 않았다`() {
        shouldThrow<ParseException> {
            parse("DELETE FROM users;")
        }
    }

    @Test
    fun `세미콜론 없이도 파싱된다`() {
        val stmt = parse("SELECT * FROM users")
        stmt.shouldBeInstanceOf<Statement.Select>()
    }
}
