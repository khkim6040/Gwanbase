package gwanbase.sql

import gwanbase.execution.ExpressionEvaluator
import gwanbase.execution.Planner
import gwanbase.optimizer.Optimizer
import gwanbase.optimizer.StatisticsManager
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
                database.createIndex(stmt.indexName, stmt.tableName, stmt.columnName, stmt.unique)
                ExecuteResult.IndexCreated(stmt.indexName)
            }
            is Statement.DropIndex -> {
                database.dropIndex(stmt.indexName)
                ExecuteResult.IndexDropped(stmt.indexName)
            }
            is Statement.Analyze -> {
                val statsManager = StatisticsManager(database.getCatalog())
                val rowCount = statsManager.analyze(database, stmt.tableName)
                ExecuteResult.Analyzed(stmt.tableName, rowCount)
            }
            is Statement.Explain -> {
                val inner = stmt.statement
                if (inner !is Statement.Select) {
                    throw BindException("EXPLAIN은 SELECT 문만 지원한다")
                }
                val binder = Binder(database.getCatalog())
                binder.bind(inner)
                val optimizer = Optimizer(database.getCatalog())
                val plan = optimizer.optimize(inner)
                ExecuteResult.Explained(plan.explain())
            }
        }
    }

    /**
     * CREATE TABLE 문을 실행한다.
     *
     * PRIMARY KEY / UNIQUE 컬럼 제약은 유일 인덱스로 구현한다. 인덱스·제약 이름은 PostgreSQL 규칙
     * (`{table}_pkey`, `{table}_{column}_key`, `{table}_{column}_fkey`, `{table}_{column}_check`)을 따른다.
     * PostgreSQL은 CHECK 이름을 표현식이 실제 참조하는 컬럼으로 짓지만(`ChooseConstraintName`),
     * 여기서는 제약이 선언된 컬럼 이름을 쓴다.
     * - https://www.postgresql.org/docs/current/ddl-constraints.html
     * - https://github.com/postgres/postgres/blob/master/src/backend/commands/indexcmds.c (`ChooseConstraintName`)
     */
    private fun executeCreateTable(stmt: Statement.CreateTable): ExecuteResult.Created {
        val columns = stmt.columns.map { colDef ->
            Column(
                name = colDef.name,
                type = colDef.dataType.toDataType(),
                maxLength = if (colDef.dataType is SqlDataType.VarcharType) colDef.dataType.maxLength else 0,
                nullable = colDef.nullable,
            )
        }
        val schema = Schema(columns)
        database.createTable(stmt.tableName, schema)
        for (colDef in stmt.columns) {
            if (colDef.primaryKey) {
                database.createIndex("${stmt.tableName}_pkey", stmt.tableName, colDef.name, unique = true)
            } else if (colDef.unique) {
                database.createIndex("${stmt.tableName}_${colDef.name}_key", stmt.tableName, colDef.name, unique = true)
            }
        }
        val catalog = database.getCatalog()
        for (colDef in stmt.columns) {
            colDef.check?.let {
                catalog.createCheck("${stmt.tableName}_${colDef.name}_check", stmt.tableName, it.toSql())
            }
            colDef.references?.let { ref ->
                // Binder가 참조 컬럼 생략 시 PRIMARY KEY 존재를 이미 확인했다
                val refColumn = ref.column ?: catalog.getIndex("${ref.table}_pkey")!!.columnName
                catalog.createForeignKey("${stmt.tableName}_${colDef.name}_fkey", stmt.tableName, colDef.name, ref.table, refColumn)
            }
        }
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
            valuesArray[colIndex] = coerceValue(rawValue, schema.column(colIndex))
        }

        val tuple = Tuple(schema, valuesArray)
        rowConstraintChecker(stmt.tableName, schema)(tuple)
        val rid = session?.insertTupleWithLock(stmt.tableName, tuple)
            ?: database.insertTuple(stmt.tableName, tuple)
        return ExecuteResult.Inserted(rid)
    }

    /**
     * SELECT 문을 Optimizer + Volcano 모델로 실행한다.
     *
     * Optimizer가 비용 기반으로 최적 계획을 선택하고,
     * Planner가 계획을 연산자 트리로 변환하여 결과를 수집한다.
     */
    private fun executeSelect(stmt: Statement.Select): ExecuteResult.Selected {
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)
        val op = planner.toOperator(plan)

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

        val checkRow = rowConstraintChecker(stmt.tableName, schema)
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
                newValues[colIndex] = coerceValue(rawValue, schema.column(colIndex))
            }
            val newTuple = Tuple(schema, newValues)
            checkRow(newTuple)
            checkNoReferencingRows(stmt.tableName, schema, freshTuple, newTuple, rid)
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

        val referenced = database.getCatalog().getForeignKeysReferencing(stmt.tableName).isNotEmpty()
        for (rid in toDelete) {
            if (referenced) {
                // 부모 X 잠금을 먼저 잡아야 자식 삽입(부모 S 잠금)과 직렬화된다
                session?.acquireExclusiveLock(stmt.tableName, rid)
                val tuple = database.getTuple(stmt.tableName, rid) ?: continue
                checkNoReferencingRows(stmt.tableName, schema, tuple, null, rid)
            }
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
     * 값을 대상 컬럼 타입에 맞게 변환한다.
     *
     * 타입 범위를 벗어나는 값은 DataException(Class 22)으로 거부한다.
     */
    private fun coerceValue(value: Any?, column: Column): Any? {
        if (value == null) return null
        return when (column.type) {
            DataType.INT32 -> when (value) {
                is Long -> {
                    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
                        throw DataException("INT 범위 초과: $value", "22003")
                    }
                    value.toInt()
                }
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
            DataType.VARCHAR -> {
                if (value is String && column.maxLength > 0 && value.length > column.maxLength) {
                    throw DataException("VARCHAR(${column.maxLength}) 길이 초과: ${value.length}자", "22001")
                }
                value
            }
            else -> value
        }
    }

    // ── 제약 검사 ──

    /**
     * INSERT/UPDATE로 만들어질 행에 대한 CHECK·외래 키(자식 쪽) 검사 함수를 만든다.
     *
     * 힙을 변경하기 **전에** 호출하며, 위반 시 반쯤 쓰인 상태가 남지 않는다 (UNIQUE와 같은 이유).
     * PostgreSQL도 CHECK는 힙 삽입 전 `ExecConstraints()`에서 평가하고, 결과가 NULL이면 통과시킨다.
     * 외래 키는 AFTER 트리거 `RI_FKey_check_ins/upd`가 부모 행을 `FOR KEY SHARE`로 조회한다.
     * 여기서는 부모 유일 인덱스로 RID를 찾아 S 잠금을 건 뒤 존재를 재확인하는 것으로 대신한다
     * — Strict 2PL에서 S 잠금은 트랜잭션 종료까지 유지되므로 부모 삭제를 막는 효과가 같다.
     * 부모 행이 다른 트랜잭션에 의해 미커밋 삭제된 경우 MVCC가 없어 즉시 위반으로 본다.
     * - https://github.com/postgres/postgres/blob/master/src/backend/executor/execMain.c (`ExecConstraints`)
     * - https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/ri_triggers.c
     *   (`RI_FKey_check`, `ri_PerformCheck`)
     * - https://www.postgresql.org/docs/current/ddl-constraints.html (CHECK NULL 처리, MATCH SIMPLE)
     */
    private fun rowConstraintChecker(tableName: String, schema: Schema): (Tuple) -> Unit {
        val catalog = database.getCatalog()
        val checks = catalog.getChecksForTable(tableName).map { chk ->
            chk to Parser(Lexer(chk.exprSql).tokenize()).parseStandaloneExpression()
        }
        val foreignKeys = catalog.getForeignKeysForTable(tableName).map { fk ->
            val parentIndex = catalog.getIndexesForTable(fk.refTableName)
                .first { it.unique && it.columnName == fk.refColumnName }
            fk to parentIndex
        }
        return { tuple ->
            for ((chk, expr) in checks) {
                if (ExpressionEvaluator.evaluate(schema, tuple, expr) == false) {
                    throw ConstraintViolationException(
                        chk.name, "23514",
                        "new row for relation \"$tableName\" violates check constraint \"${chk.name}\"",
                    )
                }
            }
            for ((fk, parentIndex) in foreignKeys) {
                val colIndex = schema.columnIndex(fk.columnName)
                val value = ExpressionEvaluator.getTupleValue(tuple, colIndex, schema.column(colIndex).type)
                    ?: continue
                val parentRid = database.findRidByUniqueIndex(parentIndex, value)
                if (parentRid != null) session?.acquireSharedLock(fk.refTableName, parentRid)
                if (parentRid == null || database.getTuple(fk.refTableName, parentRid) == null) {
                    throw ConstraintViolationException(
                        fk.name, "23503",
                        "insert or update on table \"$tableName\" violates foreign key constraint \"${fk.name}\"",
                    )
                }
            }
        }
    }

    /**
     * 부모 행을 삭제하거나 키를 바꾸기 전에 그 행을 참조하는 자식 행이 없는지 검사한다 (RESTRICT).
     *
     * 호출 시점에는 부모 행에 X 잠금이 걸려 있어야 한다. 자식 삽입은 부모 행 S 잠금을 잡으므로,
     * X 잠금을 얻었다면 미커밋 자식 삽입은 없다. PostgreSQL의 `RI_FKey_noaction_del/upd`가
     * 자식 테이블을 `SELECT 1 ... FOR KEY SHARE`로 조회하는 것에 해당한다.
     * - https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/ri_triggers.c (`ri_restrict`)
     *
     * @param newTuple UPDATE면 변경 후 행. 참조 컬럼 값이 같으면 검사하지 않는다
     * @param selfRid 자기 참조 테이블에서 자기 자신을 자식으로 세지 않기 위한 RID
     */
    private fun checkNoReferencingRows(tableName: String, schema: Schema, oldTuple: Tuple, newTuple: Tuple?, selfRid: RID) {
        for (fk in database.getCatalog().getForeignKeysReferencing(tableName)) {
            val colIndex = schema.columnIndex(fk.refColumnName)
            val type = schema.column(colIndex).type
            val oldValue = ExpressionEvaluator.getTupleValue(oldTuple, colIndex, type) ?: continue
            if (newTuple != null && ExpressionEvaluator.getTupleValue(newTuple, colIndex, type) == oldValue) continue
            val excludeRid = if (fk.tableName == tableName) selfRid else null
            if (database.existsRowWithValue(fk.tableName, fk.columnName, oldValue, excludeRid)) {
                throw ConstraintViolationException(
                    fk.name, "23503",
                    "update or delete on table \"$tableName\" violates foreign key constraint \"${fk.name}\" on table \"${fk.tableName}\"",
                )
            }
        }
    }
}
