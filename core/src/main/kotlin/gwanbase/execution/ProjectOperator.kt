package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*

/**
 * 컬럼 선택 및 표현식 계산을 수행하는 프로젝션 연산자.
 *
 * SELECT 절의 항목(*, 컬럼 참조, 표현식)에 따라
 * 입력 튜플에서 필요한 값만 추출하여 새 Tuple을 생성한다.
 *
 * @param child 입력 연산자
 * @param projections SELECT 절 항목 목록
 */
class ProjectOperator(
    private val child: Operator,
    private val projections: List<SelectItem>,
) : Operator {

    private val isStar = projections.size == 1 && projections[0] is SelectItem.Star

    override val outputSchema: Schema by lazy {
        if (isStar) {
            child.outputSchema
        } else {
            val columns = projections.map { item ->
                when (item) {
                    is SelectItem.Star -> error("Star는 단독으로만 사용 가능")
                    is SelectItem.ExprItem -> buildOutputColumn(item.expr, child.outputSchema)
                }
            }
            Schema(columns)
        }
    }

    override fun open() {
        child.open()
    }

    override fun next(): Tuple? {
        val tuple = child.next() ?: return null
        if (isStar) return tuple

        val childSchema = child.outputSchema
        val values = Array<Any?>(projections.size) { i ->
            when (val item = projections[i]) {
                is SelectItem.Star -> error("Star는 단독으로만 사용 가능")
                is SelectItem.ExprItem -> ExpressionEvaluator.evaluate(childSchema, tuple, item.expr)
            }
        }
        return Tuple(outputSchema, values)
    }

    override fun close() {
        child.close()
    }

    companion object {
        /**
         * 표현식으로부터 출력 컬럼 정의를 생성한다.
         */
        fun buildOutputColumn(expr: Expression, childSchema: Schema): Column {
            return when (expr) {
                is Expression.ColumnRef -> {
                    val srcCol = childSchema.column(childSchema.columnIndex(expr.name))
                    srcCol.copy()
                }
                else -> {
                    val type = inferType(expr, childSchema)
                    val name = expressionName(expr)
                    Column(name, type, nullable = true)
                }
            }
        }

        /**
         * 표현식의 결과 데이터 타입을 추론한다.
         */
        private fun inferType(expr: Expression, childSchema: Schema): DataType {
            return when (expr) {
                is Expression.ColumnRef -> childSchema.column(childSchema.columnIndex(expr.name)).type
                is Expression.IntLiteral -> DataType.INT64
                is Expression.FloatLiteral -> DataType.FLOAT64
                is Expression.StringLiteral -> DataType.VARCHAR
                is Expression.BoolLiteral -> DataType.BOOLEAN
                is Expression.NullLiteral -> DataType.INT32
                is Expression.BinaryOp -> when (expr.op) {
                    BinaryOperator.ADD, BinaryOperator.SUB,
                    BinaryOperator.MUL, BinaryOperator.DIV -> {
                        val leftType = inferType(expr.left, childSchema)
                        val rightType = inferType(expr.right, childSchema)
                        if (leftType == DataType.FLOAT64 || rightType == DataType.FLOAT64) {
                            DataType.FLOAT64
                        } else {
                            DataType.INT64
                        }
                    }
                    else -> DataType.BOOLEAN
                }
                is Expression.UnaryOp -> when (expr.op) {
                    UnaryOperator.NEGATE -> inferType(expr.operand, childSchema)
                    UnaryOperator.NOT -> DataType.BOOLEAN
                }
                is Expression.IsNull, is Expression.IsNotNull -> DataType.BOOLEAN
            }
        }

        /**
         * 표현식의 이름을 생성한다 (컬럼 참조면 컬�� 이름, 그 외는 toString).
         */
        private fun expressionName(expr: Expression): String {
            return when (expr) {
                is Expression.ColumnRef -> expr.name
                else -> expr.toString()
            }
        }
    }
}
