package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.Catalog
import kotlin.math.max

/**
 * 실행 계획을 열거하고 최적을 선택한다.
 *
 * 단일 테이블에서는 인덱스 존재 여부와 비용 비교로 접근 경로를 결정하고,
 * 다중 테이블에서는 조인 순서를 결정한다.
 */
class PlanEnumerator(private val catalog: Catalog) {

    /**
     * 단일 테이블의 최적 접근 경로를 선택한다.
     *
     * 필터가 등가 조건이고 해당 컬럼에 인덱스가 있으면 IndexScan과 SeqScan의
     * 비용을 비교하여 더 저렴한 쪽을 선택한다.
     *
     * @param tableName 대상 테이블
     * @param filter WHERE 조건 (null이면 전체 스캔)
     * @return 최적 PlanNode
     */
    fun bestAccessPath(tableName: String, filter: Expression?): PlanNode {
        val rowCount = catalog.getRowCount(tableName)
        val seqCost = CostEstimator.seqScanCost(rowCount)

        if (filter == null) return PlanNode.SeqScan(tableName, null, rowCount, seqCost)

        val eqColumn = extractEqualityColumn(filter)
        if (eqColumn != null) {
            val indexes = catalog.getIndexesForTable(tableName)
            val matchingIndex = indexes.find { it.columnName == eqColumn.first }
            if (matchingIndex != null) {
                val colStats = catalog.getColumnStats(tableName, eqColumn.first)
                val sel = CostEstimator.equalitySelectivity(colStats)
                val matchedRows = max(1, (rowCount * sel).toLong())
                val idxCost = CostEstimator.indexScanCost(matchedRows)
                if (idxCost < seqCost) {
                    return PlanNode.IndexScan(
                        tableName, matchingIndex.name, matchingIndex.columnName,
                        eqColumn.second, removeCondition(filter, eqColumn.first),
                        matchedRows, idxCost,
                    )
                }
            }
        }

        val filteredRows = estimateFilteredRows(tableName, filter, rowCount)
        return PlanNode.SeqScan(tableName, filter, filteredRows, seqCost)
    }

    /**
     * 다중 테이블의 최적 조인 순서를 결정한다.
     *
     * 2테이블: 양방향 비용 비교. 3+테이블: 행 수 기준 greedy 정렬.
     * 3개 이상 테이블에서는 모든 조인 조건을 AND로 결합하여 최상위 조인에 배치하고,
     * 내부 조인은 조건 없이(cross join) 수행한다. 정확성을 보장하되 최적화는 미래 과제로 남긴다.
     *
     * @param tables 조인 대상 테이블 목록
     * @param joinConditions 모든 조인 조건 목록
     * @return 최적 조인 계획
     */
    fun bestJoinOrder(tables: List<String>, joinConditions: List<Expression>): PlanNode {
        require(joinConditions.isNotEmpty()) { "조인 조건이 하나 이상 필요하다" }
        val combined = if (joinConditions.size == 1) joinConditions[0]
        else joinConditions.reduce { acc, expr ->
            Expression.BinaryOp(acc, BinaryOperator.AND, expr)
        }

        if (tables.size == 1) return bestAccessPath(tables[0], null)
        if (tables.size == 2) {
            val ab = buildJoin(tables[0], tables[1], combined)
            val ba = buildJoin(tables[1], tables[0], combined)
            return if (ab.estimatedCost <= ba.estimatedCost) ab else ba
        }
        // 3+ tables: greedy — 행 수가 작은 테이블부터 outer로 사용
        // 최상위 조인에만 결합된 전체 조건을 배치하고, 내부 조인은 pass-through
        val sorted = tables.sortedBy { catalog.getRowCount(it) }
        var plan: PlanNode = bestAccessPath(sorted[0], null)
        for (i in 1 until sorted.size) {
            val inner = bestAccessPath(sorted[i], null)
            val cost = CostEstimator.nestedLoopJoinCost(plan.estimatedCost, plan.estimatedRows, inner.estimatedCost)
            val rows = plan.estimatedRows * inner.estimatedRows / max(1, max(plan.estimatedRows, inner.estimatedRows))
            val cond = if (i == sorted.size - 1) combined else Expression.BoolLiteral(true)
            plan = PlanNode.NestedLoopJoin(plan, inner, cond, rows, cost)
        }
        return plan
    }

    /** 두 테이블의 NLJ 계획을 생성한다. */
    private fun buildJoin(outerTable: String, innerTable: String, condition: Expression): PlanNode.NestedLoopJoin {
        val outer = bestAccessPath(outerTable, null)
        val inner = bestAccessPath(innerTable, null)
        val cost = CostEstimator.nestedLoopJoinCost(outer.estimatedCost, outer.estimatedRows, inner.estimatedCost)
        val rows = outer.estimatedRows * inner.estimatedRows / max(1, max(outer.estimatedRows, inner.estimatedRows))
        return PlanNode.NestedLoopJoin(outer, inner, condition, rows, cost)
    }

    /**
     * 등가 조건 col = literal을 추출한다.
     *
     * @return (컬럼명, 리터럴 표현식) 쌍, 또는 등가 조건이 아니면 null
     */
    private fun extractEqualityColumn(expr: Expression): Pair<String, Expression>? {
        if (expr !is Expression.BinaryOp || expr.op != BinaryOperator.EQ) return null
        if (expr.left is Expression.ColumnRef && expr.right !is Expression.ColumnRef)
            return (expr.left as Expression.ColumnRef).name to expr.right
        if (expr.right is Expression.ColumnRef && expr.left !is Expression.ColumnRef)
            return (expr.right as Expression.ColumnRef).name to expr.left
        return null
    }

    /**
     * AND 조건에서 특정 컬럼의 등가 조건을 제거한다.
     *
     * 인덱스가 처리하는 조건을 제외하고 나머지 필터만 남긴다.
     */
    private fun removeCondition(expr: Expression, columnName: String): Expression? {
        val eq = extractEqualityColumn(expr)
        if (eq != null && eq.first == columnName) return null
        if (expr is Expression.BinaryOp && expr.op == BinaryOperator.AND) {
            val leftEq = extractEqualityColumn(expr.left)
            val rightEq = extractEqualityColumn(expr.right)
            if (leftEq != null && leftEq.first == columnName) return expr.right
            if (rightEq != null && rightEq.first == columnName) return expr.left
        }
        return expr
    }

    /** 필터 적용 후 예상 행 수를 추정한다. */
    private fun estimateFilteredRows(tableName: String, filter: Expression, totalRows: Long): Long {
        val eq = extractEqualityColumn(filter)
        if (eq != null) {
            val stats = catalog.getColumnStats(tableName, eq.first)
            return max(1, (totalRows * CostEstimator.equalitySelectivity(stats)).toLong())
        }
        return max(1, (totalRows * CostEstimator.DEFAULT_OTHER_SELECTIVITY).toLong())
    }
}
