package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*

/**
 * 튜플 컨텍스트에서 AST Expression을 평가한다.
 *
 * SqlExecutor에 있던 표현식 평가 로직을 분리하여
 * Filter, Project 등 여러 연산자에서 재사용한다.
 */
object ExpressionEvaluator {

    /**
     * 표현식을 평가하여 Kotlin 값(Any?)으로 반환한다.
     */
    fun evaluate(schema: Schema, tuple: Tuple, expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.NullLiteral -> null
            is Expression.ColumnRef -> {
                val index = resolveColumnIndex(schema, expr)
                getTupleValue(tuple, index, schema.column(index).type)
            }
            is Expression.BinaryOp -> {
                val left = evaluate(schema, tuple, expr.left)
                val right = evaluate(schema, tuple, expr.right)
                evaluateBinaryOp(left, expr.op, right)
            }
            is Expression.UnaryOp -> {
                val operand = evaluate(schema, tuple, expr.operand)
                when (expr.op) {
                    UnaryOperator.NEGATE -> when (operand) {
                        is Long -> -operand
                        is Int -> -operand
                        is Double -> -operand
                        null -> null
                        else -> error("NEGATE 연산 대상이 숫자가 아니다: $operand")
                    }
                    UnaryOperator.NOT -> when (operand) {
                        is Boolean -> !operand
                        null -> null
                        else -> error("NOT 연산 대상이 Boolean이 아니다: $operand")
                    }
                }
            }
            is Expression.IsNull -> {
                val value = evaluate(schema, tuple, expr.expr)
                value == null
            }
            is Expression.IsNotNull -> {
                val value = evaluate(schema, tuple, expr.expr)
                value != null
            }
        }
    }

    /**
     * 조건 표현식을 Boolean으로 평가한다.
     *
     * NULL은 false로 취급한다 (SQL 3-value logic).
     */
    fun evaluateCondition(schema: Schema, tuple: Tuple, expr: Expression): Boolean {
        val result = evaluate(schema, tuple, expr)
        return result == true
    }

    /**
     * 튜플에서 컬럼 값을 타입에 맞게 꺼낸다.
     */
    fun getTupleValue(tuple: Tuple, index: Int, type: DataType): Any? {
        if (tuple.isNull(index)) return null
        return when (type) {
            DataType.BOOLEAN -> tuple.getBoolean(index)
            DataType.INT32 -> tuple.getInt(index)
            DataType.INT64 -> tuple.getLong(index)
            DataType.FLOAT64 -> tuple.getDouble(index)
            DataType.TIMESTAMP -> tuple.getTimestamp(index)
            DataType.VARCHAR -> tuple.getString(index)
        }
    }

    /**
     * ColumnRef의 테이블 한정자를 고려하여 스키마에서 컬럼 인덱스를 찾는다.
     *
     * JOIN 결합 스키마에서는 동일 이름의 컬럼이 "테이블명.컬럼명" 형식으로 저장되므로,
     * 테이블 한정자가 있으면 "table.name" 형식을 먼저 시도하고,
     * 없으면 기존 방식(이름만)으로 조회한다.
     */
    fun resolveColumnIndex(schema: Schema, ref: Expression.ColumnRef): Int {
        if (ref.table != null) {
            // 테이블 한정 참조: "table.name" 형식으로 먼저 시도
            val qualifiedName = "${ref.table}.${ref.name}"
            val qualifiedIdx = schema.columnIndexOrNull(qualifiedName)
            if (qualifiedIdx != null) return qualifiedIdx
            // fallback: 비한정 이름으로 시도 (단일 테이블 등)
            return schema.columnIndex(ref.name)
        }
        return schema.columnIndex(ref.name)
    }

    // ── private ──

    private fun evaluateBinaryOp(left: Any?, op: BinaryOperator, right: Any?): Any? {
        // AND/OR NULL 전파
        if (op == BinaryOperator.AND) {
            if (left == false) return false
            if (right == false) return false
            if (left == null || right == null) return null
            return (left == true) && (right == true)
        }
        if (op == BinaryOperator.OR) {
            if (left == true) return true
            if (right == true) return true
            if (left == null || right == null) return null
            return false
        }

        // NULL 전파: NULL과의 비교/연산은 NULL
        if (left == null || right == null) return null

        // 산술 연산
        if (op in listOf(BinaryOperator.ADD, BinaryOperator.SUB, BinaryOperator.MUL, BinaryOperator.DIV)) {
            return numericOp(left, right, op)
        }

        // 비교 연산
        val cmp = compareValues(left, right)
        return when (op) {
            BinaryOperator.EQ -> cmp == 0
            BinaryOperator.NEQ -> cmp != 0
            BinaryOperator.LT -> cmp < 0
            BinaryOperator.GT -> cmp > 0
            BinaryOperator.LTE -> cmp <= 0
            BinaryOperator.GTE -> cmp >= 0
            else -> error("처리되지 않은 연산자: $op")
        }
    }

    private fun numericOp(left: Any, right: Any, op: BinaryOperator): Any {
        val bothInteger = (left is Int || left is Long) && (right is Int || right is Long)
        return if (bothInteger) {
            val l = (left as Number).toLong()
            val r = (right as Number).toLong()
            when (op) {
                BinaryOperator.ADD -> l + r
                BinaryOperator.SUB -> l - r
                BinaryOperator.MUL -> l * r
                BinaryOperator.DIV -> l / r
                else -> error("숫자 연산이 아니다: $op")
            }
        } else {
            val l = (left as Number).toDouble()
            val r = (right as Number).toDouble()
            when (op) {
                BinaryOperator.ADD -> l + r
                BinaryOperator.SUB -> l - r
                BinaryOperator.MUL -> l * r
                BinaryOperator.DIV -> l / r
                else -> error("숫자 연산이 아니다: $op")
            }
        }
    }

    private fun compareValues(left: Any, right: Any): Int {
        if (left is Number && right is Number) {
            return left.toDouble().compareTo(right.toDouble())
        }
        if (left is String && right is String) {
            return left.compareTo(right)
        }
        if (left is Boolean && right is Boolean) {
            return left.compareTo(right)
        }
        error("비교할 수 없는 타입: ${left::class.simpleName} vs ${right::class.simpleName}")
    }
}
