package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExpressionEvaluatorTest {

    private val schema = Schema(
        listOf(
            Column("id", DataType.INT32, nullable = false),
            Column("name", DataType.VARCHAR, maxLength = 50, nullable = true),
            Column("score", DataType.INT32, nullable = true),
            Column("rate", DataType.FLOAT64, nullable = true),
            Column("active", DataType.BOOLEAN, nullable = true),
        )
    )

    /** id=1, name="Alice", score=90, rate=3.14, active=true */
    private val tuple = Tuple(schema, arrayOf(1, "Alice", 90, 3.14, true))

    /** id=2, name=null, score=null, rate=null, active=null */
    private val nullTuple = Tuple(schema, arrayOf(2, null, null, null, null))

    // ── 리터럴 평가 ──

    @Test
    fun `정수 리터럴 평가`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.IntLiteral(42)) shouldBe 42L
    }

    @Test
    fun `실수 리터럴 평가`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.FloatLiteral(2.5)) shouldBe 2.5
    }

    @Test
    fun `문자열 리터럴 평가`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.StringLiteral("hello")) shouldBe "hello"
    }

    @Test
    fun `불리언 리터럴 평가`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.BoolLiteral(true)) shouldBe true
    }

    @Test
    fun `NULL 리터럴 평가`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.NullLiteral) shouldBe null
    }

    // ── 컬럼 참조 ──

    @Test
    fun `컬럼 참조로 INT 값 꺼내기`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.ColumnRef(null,"id")) shouldBe 1
    }

    @Test
    fun `컬럼 참조로 VARCHAR 값 꺼내기`() {
        ExpressionEvaluator.evaluate(schema, tuple, Expression.ColumnRef(null,"name")) shouldBe "Alice"
    }

    @Test
    fun `NULL 컬럼 참조`() {
        ExpressionEvaluator.evaluate(schema, nullTuple, Expression.ColumnRef(null,"name")) shouldBe null
    }

    // ── 산술 연산 ──

    @Test
    fun `정수 덧셈`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.ADD,
            Expression.IntLiteral(10),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe 100L
    }

    @Test
    fun `정수 나눗셈`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.DIV,
            Expression.IntLiteral(3),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe 30L
    }

    @Test
    fun `실수 곱셈`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"rate"),
            BinaryOperator.MUL,
            Expression.FloatLiteral(2.0),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe 6.28
    }

    @Test
    fun `NULL과 산술 연산 시 NULL 반환`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.ADD,
            Expression.IntLiteral(10),
        )
        ExpressionEvaluator.evaluate(schema, nullTuple, expr) shouldBe null
    }

    // ── 비교 연산 ──

    @Test
    fun `정수 비교 EQ`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.EQ,
            Expression.IntLiteral(90),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `정수 비교 GTE`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(80),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `문자열 비교`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"name"),
            BinaryOperator.EQ,
            Expression.StringLiteral("Alice"),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `NULL과 비교 시 NULL 반환`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.EQ,
            Expression.IntLiteral(90),
        )
        ExpressionEvaluator.evaluate(schema, nullTuple, expr) shouldBe null
    }

    // ── 논리 연산 (3-value logic) ──

    @Test
    fun `AND - 둘 다 true`() {
        val expr = Expression.BinaryOp(
            Expression.BoolLiteral(true),
            BinaryOperator.AND,
            Expression.BoolLiteral(true),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `AND - 하나가 false면 false`() {
        val expr = Expression.BinaryOp(
            Expression.BoolLiteral(false),
            BinaryOperator.AND,
            Expression.NullLiteral,
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe false
    }

    @Test
    fun `AND - NULL과 true면 NULL`() {
        val expr = Expression.BinaryOp(
            Expression.NullLiteral,
            BinaryOperator.AND,
            Expression.BoolLiteral(true),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe null
    }

    @Test
    fun `OR - 하나가 true면 true`() {
        val expr = Expression.BinaryOp(
            Expression.BoolLiteral(true),
            BinaryOperator.OR,
            Expression.NullLiteral,
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `OR - NULL과 false면 NULL`() {
        val expr = Expression.BinaryOp(
            Expression.NullLiteral,
            BinaryOperator.OR,
            Expression.BoolLiteral(false),
        )
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe null
    }

    // ── 단항 연산 ──

    @Test
    fun `NEGATE 정수`() {
        val expr = Expression.UnaryOp(UnaryOperator.NEGATE, Expression.IntLiteral(5))
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe -5L
    }

    @Test
    fun `NOT 불리언`() {
        val expr = Expression.UnaryOp(UnaryOperator.NOT, Expression.BoolLiteral(true))
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe false
    }

    @Test
    fun `NOT NULL이면 NULL`() {
        val expr = Expression.UnaryOp(UnaryOperator.NOT, Expression.NullLiteral)
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe null
    }

    @Test
    fun `NEGATE NULL이면 NULL`() {
        val expr = Expression.UnaryOp(UnaryOperator.NEGATE, Expression.NullLiteral)
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe null
    }

    // ── IS NULL / IS NOT NULL ──

    @Test
    fun `IS NULL - NULL 값이면 true`() {
        val expr = Expression.IsNull(Expression.ColumnRef(null,"name"))
        ExpressionEvaluator.evaluate(schema, nullTuple, expr) shouldBe true
    }

    @Test
    fun `IS NULL - 값이 있으면 false`() {
        val expr = Expression.IsNull(Expression.ColumnRef(null,"name"))
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe false
    }

    @Test
    fun `IS NOT NULL - 값이 있으면 true`() {
        val expr = Expression.IsNotNull(Expression.ColumnRef(null,"name"))
        ExpressionEvaluator.evaluate(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `IS NOT NULL - NULL이면 false`() {
        val expr = Expression.IsNotNull(Expression.ColumnRef(null,"name"))
        ExpressionEvaluator.evaluate(schema, nullTuple, expr) shouldBe false
    }

    // ── evaluateCondition ──

    @Test
    fun `evaluateCondition - true 결과는 true`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(80),
        )
        ExpressionEvaluator.evaluateCondition(schema, tuple, expr) shouldBe true
    }

    @Test
    fun `evaluateCondition - false 결과는 false`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.LT,
            Expression.IntLiteral(80),
        )
        ExpressionEvaluator.evaluateCondition(schema, tuple, expr) shouldBe false
    }

    @Test
    fun `evaluateCondition - NULL 결과는 false`() {
        val expr = Expression.BinaryOp(
            Expression.ColumnRef(null,"score"),
            BinaryOperator.EQ,
            Expression.IntLiteral(90),
        )
        ExpressionEvaluator.evaluateCondition(schema, nullTuple, expr) shouldBe false
    }
}
