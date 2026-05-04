package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.optimizer.PlanNode
import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.table.Schema
import gwanbase.txn.DatabaseSession

/**
 * AST Statement 또는 PlanNode를 연산자 트리로 변환한다.
 *
 * Optimizer가 생성한 PlanNode를 물리 Operator로 변환하는 [toOperator]와,
 * 기존 호환용으로 AST에서 직접 Operator를 생성하는 [planSelect]를 모두 제공한다.
 *
 * SELECT 문에 대한 연산자 트리 구성 순서:
 * ```
 * SeqScan(table)
 *   └→ Filter(where)          -- WHERE가 있을 때만
 *        └→ Sort(orderBy)     -- ORDER BY가 있을 때만
 *              └→ Limit(n)    -- LIMIT가 있을 때만
 *                    └→ Project(columns)
 * ```
 *
 * Project를 마지막에 적용하는 이유: Sort가 정렬 키 컬럼에 접근해야 하므로
 * Project를 먼저 적용하면 정렬 키가 사라질 수 있다.
 *
 * @param database 대상 데이터베이스
 * @param session 잠금 획득용 세션 (없으면 잠금 없이 실행)
 */
class Planner(
    private val database: Database,
    private val session: DatabaseSession? = null,
) {

    /**
     * PlanNode를 물리 Operator 트리로 변환한다.
     *
     * Optimizer가 비용 기반으로 선택한 논리 계획을 실행 가능한 연산자로 매핑한다.
     *
     * @param plan 논리 실행 계획
     * @return 실행 가능한 Operator 트리
     */
    fun toOperator(plan: PlanNode): Operator = when (plan) {
        is PlanNode.SeqScan -> {
            val scan = SeqScanOperator(database, plan.tableName, session)
            if (plan.filter != null) FilterOperator(scan, plan.filter) else scan
        }
        is PlanNode.IndexScan -> {
            val tableInfo = database.getTable(plan.tableName)!!
            val indexInfo = database.getCatalog().getIndex(plan.indexName)!!
            val tree = BPlusTree(database.bpm, indexInfo.rootPageId)
            val schema = tableInfo.schema
            val colIndex = schema.columnIndex(plan.indexColumnName)
            val colType = schema.column(colIndex).type
            IndexScanOperator(
                database, plan.tableName, schema, tree,
                colIndex, colType, { evaluateLiteral(plan.lookupValue) },
                plan.remainingFilter, session,
            )
        }
        is PlanNode.NestedLoopJoin -> {
            val outer = toOperator(plan.outer)
            val inner = toOperator(plan.inner)
            val combinedSchema = buildJoinSchema(
                outer.outputSchema, inner.outputSchema,
                extractTableName(plan.outer), extractTableName(plan.inner),
            )
            NestedLoopJoinOperator(outer, inner, plan.condition, combinedSchema)
        }
        is PlanNode.Sort -> SortOperator(toOperator(plan.child), plan.column, plan.ascending)
        is PlanNode.Limit -> LimitOperator(toOperator(plan.child), plan.count)
        is PlanNode.Project -> ProjectOperator(toOperator(plan.child), plan.columns)
    }

    /**
     * 기존 호환: AST → Operator (Optimizer 미사용 경로).
     *
     * SELECT 문을 연산자 트리로 변환한다.
     */
    fun planSelect(stmt: Statement.Select): Operator {
        val tableName = (stmt.from as FromClause.Table).tableName
        var op: Operator = SeqScanOperator(database, tableName, session)

        // WHERE → Filter
        if (stmt.where != null) {
            op = FilterOperator(op, stmt.where)
        }

        // ORDER BY → Sort
        if (stmt.orderBy != null) {
            op = SortOperator(op, stmt.orderBy.column, stmt.orderBy.ascending)
        }

        // LIMIT → Limit
        if (stmt.limit != null) {
            op = LimitOperator(op, stmt.limit)
        }

        // Project (항상 마지막)
        op = ProjectOperator(op, stmt.columns)

        return op
    }

    /**
     * JOIN 결합 스키마를 생성한다. 동일 이름 컬럼은 "테이블명.컬럼명" 형식으로 변환한다.
     *
     * 예: outer(id, name) + inner(id, value) → (t1.id, name, t2.id, value)
     * 동일 이름이 아닌 컬럼은 기존 이름을 유지하여 하위 호환을 보장한다.
     */
    private fun buildJoinSchema(
        outerSchema: Schema,
        innerSchema: Schema,
        outerTable: String?,
        innerTable: String?,
    ): Schema {
        val outerNames = outerSchema.columns.map { it.name }.toSet()
        val innerNames = innerSchema.columns.map { it.name }.toSet()
        val duplicates = outerNames intersect innerNames

        val outerCols = outerSchema.columns.map { col ->
            if (col.name in duplicates && outerTable != null) {
                col.copy(name = "$outerTable.${col.name}")
            } else col
        }
        val innerCols = innerSchema.columns.map { col ->
            if (col.name in duplicates && innerTable != null) {
                col.copy(name = "$innerTable.${col.name}")
            } else col
        }
        return Schema(outerCols + innerCols)
    }

    /**
     * PlanNode에서 테이블 이름을 추출한다.
     *
     * 재귀적으로 탐색하여 리프 노드의 테이블 이름을 찾는다.
     * 중첩 JOIN의 경우 null을 반환한다 (이미 prefix가 적용되어 있을 수 있음).
     */
    private fun extractTableName(plan: PlanNode): String? = when (plan) {
        is PlanNode.SeqScan -> plan.tableName
        is PlanNode.IndexScan -> plan.tableName
        is PlanNode.NestedLoopJoin -> null
        is PlanNode.Sort -> extractTableName(plan.child)
        is PlanNode.Limit -> extractTableName(plan.child)
        is PlanNode.Project -> extractTableName(plan.child)
    }

    /**
     * 리터럴 표현식을 Kotlin 값으로 평가한다 (인덱스 lookup 키 용).
     */
    private fun evaluateLiteral(expr: Expression): Any? = when (expr) {
        is Expression.IntLiteral -> expr.value
        is Expression.StringLiteral -> expr.value
        is Expression.BoolLiteral -> expr.value
        is Expression.FloatLiteral -> expr.value
        is Expression.NullLiteral -> null
        else -> error("인덱스 lookup 키로 사용할 수 없는 표현식: $expr")
    }
}
