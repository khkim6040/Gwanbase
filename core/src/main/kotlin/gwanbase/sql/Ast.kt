package gwanbase.sql

/**
 * SQL 문(statement) AST.
 */
sealed class Statement {
    /** CREATE TABLE 문. */
    data class CreateTable(
        val tableName: String,
        val columns: List<ColumnDef>,
    ) : Statement()

    /** DROP TABLE 문. */
    data class DropTable(val tableName: String) : Statement()

    /** INSERT 문. */
    data class Insert(
        val tableName: String,
        val columns: List<String>,
        val values: List<Expression>,
    ) : Statement()

    /** SELECT 문. */
    data class Select(
        val columns: List<SelectItem>,
        val from: FromClause,
        val where: Expression?,
        val orderBy: OrderByClause?,
        val limit: Int?,
    ) : Statement()

    /** UPDATE 문. */
    data class Update(
        val tableName: String,
        val assignments: List<Assignment>,
        val where: Expression?,
    ) : Statement()

    /** DELETE 문. */
    data class Delete(
        val tableName: String,
        val where: Expression?,
    ) : Statement()

    /** 트랜잭션 시작. */
    data object Begin : Statement()

    /** 트랜잭션 커밋. */
    data object Commit : Statement()

    /** 트랜잭션 롤백. */
    data object Rollback : Statement()

    /** CREATE INDEX 문. */
    data class CreateIndex(val indexName: String, val tableName: String, val columnName: String) : Statement()

    /** DROP INDEX 문. */
    data class DropIndex(val indexName: String) : Statement()

    /** ANALYZE 문 (통계 수집). */
    data class Analyze(val tableName: String) : Statement()

    /** EXPLAIN 문 (실행 계획 표시). */
    data class Explain(val statement: Statement) : Statement()
}

/**
 * FROM 절 AST.
 */
sealed class FromClause {
    /** 단일 테이블 참조. */
    data class Table(val tableName: String, val alias: String? = null) : FromClause()

    /** JOIN 절. */
    data class Join(
        val left: FromClause,
        val right: FromClause,
        val condition: Expression,
    ) : FromClause()
}

/**
 * 컬럼 정의 (CREATE TABLE에서 사용).
 */
data class ColumnDef(
    val name: String,
    val dataType: SqlDataType,
    val nullable: Boolean = true,
)

/**
 * SQL 데이터 타입.
 */
sealed class SqlDataType {
    /** BOOLEAN 타입. */
    data object BooleanType : SqlDataType()

    /** INT 타입. */
    data object IntType : SqlDataType()

    /** BIGINT 타입. */
    data object BigIntType : SqlDataType()

    /** DOUBLE 타입. */
    data object DoubleType : SqlDataType()

    /** TIMESTAMP 타입. */
    data object TimestampType : SqlDataType()

    /** 가변 길이 문자열 타입. */
    data class VarcharType(val maxLength: Int) : SqlDataType()
}

/**
 * SELECT 절의 항목 (*, 또는 표현식).
 */
sealed class SelectItem {
    /** 모든 컬럼 선택 (*). */
    data object Star : SelectItem()

    /** 표현식 기반 컬럼 선택. */
    data class ExprItem(val expr: Expression) : SelectItem()
}

/**
 * ORDER BY 절.
 */
data class OrderByClause(val column: String, val ascending: Boolean = true)

/**
 * SET 절의 대입 (UPDATE에서 사용).
 */
data class Assignment(val column: String, val value: Expression)

/**
 * SQL 표현식 AST.
 */
sealed class Expression {
    /** 정수 리터럴. */
    data class IntLiteral(val value: Long) : Expression()

    /** 부동소수점 리터럴. */
    data class FloatLiteral(val value: Double) : Expression()

    /** 문자열 리터럴. */
    data class StringLiteral(val value: String) : Expression()

    /** 불리언 리터럴. */
    data class BoolLiteral(val value: Boolean) : Expression()

    /** NULL 리터럴. */
    data object NullLiteral : Expression()

    /** 컬럼 참조. table이 null이면 단일 테이블에서 해석한다. */
    data class ColumnRef(val table: String?, val name: String) : Expression()

    /** 이항 연산 (예: a + b, x = y). */
    data class BinaryOp(
        val left: Expression,
        val op: BinaryOperator,
        val right: Expression,
    ) : Expression()

    /** 단항 연산 (예: -x, NOT y). */
    data class UnaryOp(
        val op: UnaryOperator,
        val operand: Expression,
    ) : Expression()

    /** IS NULL 검사. */
    data class IsNull(val expr: Expression) : Expression()

    /** IS NOT NULL 검사. */
    data class IsNotNull(val expr: Expression) : Expression()
}

/**
 * 이항 연산자.
 */
enum class BinaryOperator {
    ADD, SUB, MUL, DIV,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR,
}

/**
 * 단항 연산자.
 */
enum class UnaryOperator {
    NEGATE, NOT,
}
