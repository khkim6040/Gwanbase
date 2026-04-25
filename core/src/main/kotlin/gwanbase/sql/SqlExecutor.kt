package gwanbase.sql

import gwanbase.table.*

/**
 * SQL 실행 결과.
 */
sealed class ExecuteResult {
    /** CREATE TABLE 결과. */
    data class Created(val tableName: String) : ExecuteResult()

    /** DROP TABLE 결과. */
    data class Dropped(val tableName: String) : ExecuteResult()

    /** INSERT 결과. */
    data class Inserted(val rid: RID) : ExecuteResult()

    /** SELECT 결과. */
    data class Selected(val columns: List<String>, val rows: List<List<Any?>>) : ExecuteResult()

    /** UPDATE 결과. */
    data class Updated(val count: Int) : ExecuteResult()

    /** DELETE 결과. */
    data class Deleted(val count: Int) : ExecuteResult()
}

/**
 * SQL 실행 엔진.
 *
 * Lexer → Parser → Binder → 실행 파이프라인으로 SQL 문을 처리한다.
 *
 * @param database 대상 데이터베이스
 */
class SqlExecutor(private val database: Database) {

    /**
     * SQL 문을 실행한다.
     *
     * @param sql SQL 텍스트
     * @return 실행 결과
     * @throws ParseException 파싱 오류 시
     * @throws BindException 바인딩 오류 시
     */
    fun execute(sql: String): ExecuteResult {
        val tokens = Lexer(sql).tokenize()
        val statement = Parser(tokens).parse()
        val binder = Binder(database.getCatalog())
        binder.bind(statement)
        return executeStatement(statement)
    }

    // ── 문(statement) 실행 ──

    private fun executeStatement(stmt: Statement): ExecuteResult {
        return when (stmt) {
            is Statement.CreateTable -> executeCreateTable(stmt)
            is Statement.DropTable -> executeDropTable(stmt)
            is Statement.Insert -> executeInsert(stmt)
            is Statement.Select -> executeSelect(stmt)
            is Statement.Update -> executeUpdate(stmt)
            is Statement.Delete -> executeDelete(stmt)
        }
    }

    /**
     * CREATE TABLE 문을 실행한다.
     */
    private fun executeCreateTable(stmt: Statement.CreateTable): ExecuteResult.Created {
        val columns = stmt.columns.map { colDef ->
            Column(
                name = colDef.name,
                type = toDataType(colDef.dataType),
                maxLength = if (colDef.dataType is SqlDataType.VarcharType) colDef.dataType.maxLength else 0,
                nullable = colDef.nullable,
            )
        }
        val schema = Schema(columns)
        database.createTable(stmt.tableName, schema)
        return ExecuteResult.Created(stmt.tableName)
    }

    /**
     * DROP TABLE 문을 실행한다.
     */
    private fun executeDropTable(stmt: Statement.DropTable): ExecuteResult.Dropped {
        database.dropTable(stmt.tableName)
        return ExecuteResult.Dropped(stmt.tableName)
    }

    /**
     * INSERT 문을 실행한다.
     */
    private fun executeInsert(stmt: Statement.Insert): ExecuteResult.Inserted {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // 스키마 순서로 값 배열 구성
        val valuesArray = arrayOfNulls<Any?>(schema.columnCount)
        for ((i, colName) in stmt.columns.withIndex()) {
            val colIndex = schema.columnIndex(colName)
            val rawValue = evaluateLiteral(stmt.values[i])
            valuesArray[colIndex] = coerceValue(rawValue, schema.column(colIndex).type)
        }

        val tuple = Tuple(schema, valuesArray)
        val rid = database.insertTuple(stmt.tableName, tuple)
        return ExecuteResult.Inserted(rid)
    }

    /**
     * SELECT 문을 실행한다.
     */
    private fun executeSelect(stmt: Statement.Select): ExecuteResult.Selected {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // 1. 스캔
        val allTuples = mutableListOf<Pair<RID, Tuple>>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            allTuples.add(iter.next())
        }

        // 2. WHERE 필터
        var filtered = if (stmt.where != null) {
            allTuples.filter { (_, tuple) -> evaluateCondition(schema, tuple, stmt.where) }
        } else {
            allTuples
        }

        // 3. ORDER BY
        if (stmt.orderBy != null) {
            val orderColIndex = schema.columnIndex(stmt.orderBy.column)
            val comparator = compareBy<Pair<RID, Tuple>, Comparable<Any>?>(nullsLast()) { (_, tuple) ->
                @Suppress("UNCHECKED_CAST")
                getTupleValue(tuple, orderColIndex, schema.column(orderColIndex).type) as? Comparable<Any>
            }
            filtered = if (stmt.orderBy.ascending) {
                filtered.sortedWith(comparator)
            } else {
                filtered.sortedWith(comparator.reversed())
            }
        }

        // 4. LIMIT
        if (stmt.limit != null) {
            filtered = filtered.take(stmt.limit)
        }

        // 5. 프로젝션
        val columns: List<String>
        val rows: List<List<Any?>>

        if (stmt.columns.size == 1 && stmt.columns[0] is SelectItem.Star) {
            columns = (0 until schema.columnCount).map { schema.column(it).name }
            rows = filtered.map { (_, tuple) ->
                (0 until schema.columnCount).map { i ->
                    getTupleValue(tuple, i, schema.column(i).type)
                }
            }
        } else {
            columns = stmt.columns.map { item ->
                when (item) {
                    is SelectItem.Star -> "*"
                    is SelectItem.ExprItem -> {
                        when (val expr = item.expr) {
                            is Expression.ColumnRef -> expr.name
                            else -> expr.toString()
                        }
                    }
                }
            }
            rows = filtered.map { (_, tuple) ->
                stmt.columns.map { item ->
                    when (item) {
                        is SelectItem.Star -> null
                        is SelectItem.ExprItem -> evaluateExpression(schema, tuple, item.expr)
                    }
                }
            }
        }

        return ExecuteResult.Selected(columns, rows)
    }

    /**
     * UPDATE 문을 실행한다.
     */
    private fun executeUpdate(stmt: Statement.Update): ExecuteResult.Updated {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // 스캔 + 필터 → 리스트로 수집 (반복 중 변경 방지)
        val matches = mutableListOf<Pair<RID, Tuple>>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || evaluateCondition(schema, tuple, stmt.where)) {
                matches.add(rid to tuple)
            }
        }

        // 각 매칭 행 업데이트
        for ((rid, tuple) in matches) {
            // 기존 값 복사
            val newValues = Array<Any?>(schema.columnCount) { i ->
                getTupleValue(tuple, i, schema.column(i).type)
            }
            // 대입 적용
            for (assignment in stmt.assignments) {
                val colIndex = schema.columnIndex(assignment.column)
                val rawValue = evaluateExpression(schema, tuple, assignment.value)
                newValues[colIndex] = coerceValue(rawValue, schema.column(colIndex).type)
            }
            val newTuple = Tuple(schema, newValues)
            database.updateTuple(stmt.tableName, rid, newTuple)
        }

        return ExecuteResult.Updated(matches.size)
    }

    /**
     * DELETE 문을 실행한다.
     */
    private fun executeDelete(stmt: Statement.Delete): ExecuteResult.Deleted {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // 스캔 + 필터 → RID 리스트로 수집 (반복 중 변경 방지)
        val toDelete = mutableListOf<RID>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || evaluateCondition(schema, tuple, stmt.where)) {
                toDelete.add(rid)
            }
        }

        for (rid in toDelete) {
            database.deleteTuple(stmt.tableName, rid)
        }

        return ExecuteResult.Deleted(toDelete.size)
    }

    // ── 표현식 평가 ──

    /**
     * 리터럴 표현식을 Kotlin 값으로 평가한다 (INSERT VALUES 용).
     */
    private fun evaluateLiteral(expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.NullLiteral -> null
            is Expression.UnaryOp -> {
                val operand = evaluateLiteral(expr.operand)
                when (expr.op) {
                    UnaryOperator.NEGATE -> when (operand) {
                        is Long -> -operand
                        is Double -> -operand
                        else -> error("NEGATE 연산 대상이 숫자가 아니다: $operand")
                    }
                    UnaryOperator.NOT -> when (operand) {
                        is Boolean -> !operand
                        else -> error("NOT 연산 대상이 Boolean이 아니다: $operand")
                    }
                }
            }
            else -> throw BindException("INSERT VALUES에서 지원하지 않는 표현식: $expr")
        }
    }

    /**
     * 튜플 컨텍스트에서 표현식을 평가한다.
     */
    private fun evaluateExpression(schema: Schema, tuple: Tuple, expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.NullLiteral -> null
            is Expression.ColumnRef -> {
                val index = schema.columnIndex(expr.name)
                getTupleValue(tuple, index, schema.column(index).type)
            }
            is Expression.BinaryOp -> {
                val left = evaluateExpression(schema, tuple, expr.left)
                val right = evaluateExpression(schema, tuple, expr.right)
                evaluateBinaryOp(left, expr.op, right)
            }
            is Expression.UnaryOp -> {
                val operand = evaluateExpression(schema, tuple, expr.operand)
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
                val value = evaluateExpression(schema, tuple, expr.expr)
                value == null
            }
            is Expression.IsNotNull -> {
                val value = evaluateExpression(schema, tuple, expr.expr)
                value != null
            }
        }
    }

    /**
     * 조건 표현식을 Boolean으로 평가한다.
     *
     * NULL은 false로 취급한다 (SQL 3-value logic).
     */
    private fun evaluateCondition(schema: Schema, tuple: Tuple, expr: Expression): Boolean {
        val result = evaluateExpression(schema, tuple, expr)
        return result == true
    }

    /**
     * 이항 연산을 평가한다.
     */
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

    /**
     * 숫자 산술 연산을 수행한다.
     *
     * 양쪽 모두 정수 타입이면 Long, 아니면 Double로 연산한다.
     */
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

    /**
     * 두 값을 비교한다.
     *
     * 양쪽 모두 숫자이면 Double로 승격하여 비교한다.
     */
    private fun compareValues(left: Any, right: Any): Int {
        // 숫자 승격
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

    /**
     * 튜플에서 컬럼 값을 타입에 맞게 꺼낸다.
     */
    private fun getTupleValue(tuple: Tuple, index: Int, type: DataType): Any? {
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
     * 값을 대상 데이터 타입에 맞게 변환한다.
     *
     * 파서가 정수 리터럴을 Long으로 생성하므로 INT32 컬럼에는 toInt() 변환이 필요하다.
     */
    private fun coerceValue(value: Any?, targetType: DataType): Any? {
        if (value == null) return null
        return when (targetType) {
            DataType.INT32 -> when (value) {
                is Long -> value.toInt()
                is Int -> value
                else -> value
            }
            DataType.INT64 -> when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> value
            }
            DataType.FLOAT64 -> when (value) {
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is Double -> value
                else -> value
            }
            DataType.TIMESTAMP -> when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> value
            }
            else -> value
        }
    }

    /**
     * SQL 데이터 타입을 스토리지 데이터 타입으로 변환한다.
     */
    private fun toDataType(sqlType: SqlDataType): DataType {
        return when (sqlType) {
            is SqlDataType.BooleanType -> DataType.BOOLEAN
            is SqlDataType.IntType -> DataType.INT32
            is SqlDataType.BigIntType -> DataType.INT64
            is SqlDataType.DoubleType -> DataType.FLOAT64
            is SqlDataType.TimestampType -> DataType.TIMESTAMP
            is SqlDataType.VarcharType -> DataType.VARCHAR
        }
    }
}
