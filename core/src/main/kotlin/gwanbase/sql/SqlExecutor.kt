package gwanbase.sql

import gwanbase.execution.ExpressionEvaluator
import gwanbase.execution.Planner
import gwanbase.table.*
import gwanbase.txn.DatabaseSession

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

    /** BEGIN 결과. */
    data object TransactionStarted : ExecuteResult()

    /** COMMIT 결과. */
    data object TransactionCommitted : ExecuteResult()

    /** ROLLBACK 결과. */
    data object TransactionRolledBack : ExecuteResult()

    /** CREATE INDEX 결과. */
    data class IndexCreated(val indexName: String) : ExecuteResult()

    /** DROP INDEX 결과. */
    data class IndexDropped(val indexName: String) : ExecuteResult()

    /** ANALYZE 결과. */
    data class Analyzed(val tableName: String, val rowCount: Long) : ExecuteResult()

    /** EXPLAIN 결과. */
    data class Explained(val planText: String) : ExecuteResult()
}

/**
 * SQL 실행 엔진.
 *
 * Lexer → Parser → Binder → Planner → 연산자 트리 실행 파이프라인으로 SQL 문을 처리한다.
 * Phase 4에서 Volcano (Iterator) 모델을 도입하여 SELECT를 연산자 트리로 실행한다.
 * UPDATE/DELETE는 RID가 필요하므로 직접 스캔하며, DDL과 INSERT도 직접 실행한다.
 *
 * @param database 대상 데이터베이스
 */
class SqlExecutor(
    private val database: Database,
    private val session: DatabaseSession? = null,
) {

    private val planner = Planner(database, session)

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

    internal fun executeStatement(stmt: Statement): ExecuteResult {
        return when (stmt) {
            is Statement.CreateTable -> executeCreateTable(stmt)
            is Statement.DropTable -> executeDropTable(stmt)
            is Statement.Insert -> executeInsert(stmt)
            is Statement.Select -> executeSelect(stmt)
            is Statement.Update -> executeUpdate(stmt)
            is Statement.Delete -> executeDelete(stmt)
            is Statement.Begin -> error("BEGIN은 DatabaseSession에서 처리한다")
            is Statement.Commit -> error("COMMIT은 DatabaseSession에서 처리한다")
            is Statement.Rollback -> error("ROLLBACK은 DatabaseSession에서 처리한다")
            is Statement.CreateIndex -> {
                database.createIndex(stmt.indexName, stmt.tableName, stmt.columnName)
                ExecuteResult.IndexCreated(stmt.indexName)
            }
            is Statement.DropIndex -> {
                database.dropIndex(stmt.indexName)
                ExecuteResult.IndexDropped(stmt.indexName)
            }
            is Statement.Analyze -> TODO("Task 13에서 구현")
            is Statement.Explain -> TODO("Task 17에서 구현")
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

        val valuesArray = arrayOfNulls<Any?>(schema.columnCount)
        for ((i, colName) in stmt.columns.withIndex()) {
            val colIndex = schema.columnIndex(colName)
            val rawValue = evaluateLiteral(stmt.values[i])
            valuesArray[colIndex] = coerceValue(rawValue, schema.column(colIndex).type)
        }

        val tuple = Tuple(schema, valuesArray)
        val rid = session?.insertTupleWithLock(stmt.tableName, tuple)
            ?: database.insertTuple(stmt.tableName, tuple)
        return ExecuteResult.Inserted(rid)
    }

    /**
     * SELECT 문을 Volcano 모델로 실행한다.
     *
     * Planner가 생성한 연산자 트리를 구동하여 결과를 수집한다.
     */
    private fun executeSelect(stmt: Statement.Select): ExecuteResult.Selected {
        val op = planner.planSelect(stmt)

        op.open()
        try {
            val outputSchema = op.outputSchema
            val columns = (0 until outputSchema.columnCount).map { outputSchema.column(it).name }

            val rows = mutableListOf<List<Any?>>()
            var current = op.next()
            while (current != null) {
                val t = current
                val row = (0 until outputSchema.columnCount).map { i ->
                    ExpressionEvaluator.getTupleValue(t, i, outputSchema.column(i).type)
                }
                rows.add(row)
                current = op.next()
            }

            return ExecuteResult.Selected(columns, rows)
        } finally {
            op.close()
        }
    }

    /**
     * UPDATE 문을 실행한다.
     *
     * RID가 필요하므로 Database.scanTable()로 직접 스캔하고,
     * ExpressionEvaluator로 WHERE 조건과 SET 표현식을 평가한다.
     */
    private fun executeUpdate(stmt: Statement.Update): ExecuteResult.Updated {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // 스캔 + 필터 → 리스트로 수집 (반복 중 변경 방지)
        // UPDATE/DELETE 스캔은 잠금 없이 수행하고, 변경 시점에 X 잠금을 획득한다.
        // 스캔 시 S 잠금을 걸면 이후 X 업그레이드에서 데드락이 발생할 수 있다.
        val matches = mutableListOf<Pair<RID, Tuple>>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || ExpressionEvaluator.evaluateCondition(schema, tuple, stmt.where)) {
                matches.add(rid to tuple)
            }
        }

        for ((rid, _) in matches) {
            // X 잠금 획득 후 최신 튜플을 다시 읽어 Lost Update를 방지한다.
            // 잠금 없이 스캔한 튜플은 stale할 수 있으므로, 잠금 획득 후 재조회한다.
            if (session != null) {
                session.acquireExclusiveLock(stmt.tableName, rid)
            }
            val freshTuple = database.getTuple(stmt.tableName, rid) ?: continue
            val newValues = Array<Any?>(schema.columnCount) { i ->
                ExpressionEvaluator.getTupleValue(freshTuple, i, schema.column(i).type)
            }
            for (assignment in stmt.assignments) {
                val colIndex = schema.columnIndex(assignment.column)
                val rawValue = ExpressionEvaluator.evaluate(schema, freshTuple, assignment.value)
                newValues[colIndex] = coerceValue(rawValue, schema.column(colIndex).type)
            }
            val newTuple = Tuple(schema, newValues)
            if (session != null) {
                session.updateTupleWithLockAlreadyHeld(stmt.tableName, rid, newTuple)
            } else {
                database.updateTuple(stmt.tableName, rid, newTuple)
            }
        }

        return ExecuteResult.Updated(matches.size)
    }

    /**
     * DELETE 문을 실행한다.
     *
     * RID가 필요하므로 Database.scanTable()로 직접 스캔하고,
     * ExpressionEvaluator로 WHERE 조건을 평가한다.
     */
    private fun executeDelete(stmt: Statement.Delete): ExecuteResult.Deleted {
        val tableInfo = database.getTable(stmt.tableName)!!
        val schema = tableInfo.schema

        // UPDATE와 동일한 이유로 스캔은 잠금 없이 수행하고, 삭제 시점에 X 잠금을 획득한다.
        val toDelete = mutableListOf<RID>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || ExpressionEvaluator.evaluateCondition(schema, tuple, stmt.where)) {
                toDelete.add(rid)
            }
        }

        for (rid in toDelete) {
            session?.deleteTupleWithLock(stmt.tableName, rid)
                ?: database.deleteTuple(stmt.tableName, rid)
        }

        return ExecuteResult.Deleted(toDelete.size)
    }

    // ── INSERT 전용 헬퍼 ──

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
     * 값을 대상 데이터 타입에 맞게 변환한다.
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
