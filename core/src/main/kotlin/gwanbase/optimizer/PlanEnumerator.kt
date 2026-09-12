package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.Catalog
import gwanbase.table.ColumnStats
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
     * WHERE의 AND 체인에서 `col OP literal` 조건을 모아 인덱스 컬럼별 하한·상한 경계를
     * 만들고, 각 인덱스 경로의 비용을 SeqScan과 비교해 가장 저렴한 계획을 고른다.
     * 인덱스 조건은 필터에서 제거하지 않는다. 실행기가 힙 튜플에서 전체 필터를 다시
     * 평가하므로(recheck) 경계가 넓어도 결과는 정확하다.
     *
     * PostgreSQL `match_opclause_to_indexcol()`은 `indexkey OP const` 꼴만 인덱스 조건으로
     * 받고 `const OP indexkey`는 commutator로 뒤집는다:
     * https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/indxpath.c
     *
     * @param tableName 대상 테이블
     * @param filter WHERE 조건 (null이면 전체 스캔)
     * @return 최적 PlanNode
     */
    fun bestAccessPath(tableName: String, filter: Expression?): PlanNode {
        val rowCount = catalog.getRowCount(tableName)
        val seqCost = CostEstimator.seqScanCost(rowCount)
        if (filter == null) return PlanNode.SeqScan(tableName, null, rowCount, seqCost)

        val conditions = collectIndexConditions(filter)
        var best: PlanNode = PlanNode.SeqScan(
            tableName, filter, estimateFilteredRows(tableName, conditions, rowCount), seqCost,
        )
        for (index in catalog.getIndexesForTable(tableName)) {
            val (lower, upper) = indexRange(conditions, index.columnName) ?: continue
            val stats = catalog.getColumnStats(tableName, index.columnName)
            val rows = max(1, (rowCount * rangeSelectivity(lower, upper, stats)).toLong())
            val cost = CostEstimator.indexScanCost(rows)
            if (cost < best.estimatedCost) {
                best = PlanNode.IndexScan(tableName, index.name, index.columnName, lower, upper, filter, rows, cost)
            }
        }
        return best
    }

    /** 인덱스 조건 후보. 컬럼이 왼쪽에 오도록 연산자를 정규화한 상태. */
    private data class IndexCondition(val column: String, val op: BinaryOperator, val literal: Expression)

    /** AND 체인에서 `col OP literal` 또는 `literal OP col` 꼴 조건을 모은다. OP는 =, <, <=, >, >=. */
    private fun collectIndexConditions(expr: Expression): List<IndexCondition> {
        if (expr !is Expression.BinaryOp) return emptyList()
        if (expr.op == BinaryOperator.AND) {
            return collectIndexConditions(expr.left) + collectIndexConditions(expr.right)
        }
        if (expr.op !in RANGE_OPERATORS) return emptyList()
        val left = expr.left
        val right = expr.right
        return when {
            left is Expression.ColumnRef && isLiteral(right) -> listOf(IndexCondition(left.name, expr.op, right))
            right is Expression.ColumnRef && isLiteral(left) -> listOf(IndexCondition(right.name, commute(expr.op), left))
            else -> emptyList()
        }
    }

    private fun isLiteral(expr: Expression): Boolean =
        expr is Expression.IntLiteral || expr is Expression.StringLiteral ||
            expr is Expression.BoolLiteral || expr is Expression.FloatLiteral

    /** `literal OP col`을 `col OP' literal`로 바꿀 때의 OP'. */
    private fun commute(op: BinaryOperator): BinaryOperator = when (op) {
        BinaryOperator.LT -> BinaryOperator.GT
        BinaryOperator.GT -> BinaryOperator.LT
        BinaryOperator.LTE -> BinaryOperator.GTE
        BinaryOperator.GTE -> BinaryOperator.LTE
        else -> op
    }

    /**
     * 컬럼의 하한·상한 경계를 만든다. 조건이 하나도 없으면 null.
     *
     * 같은 방향 경계가 여럿이면 첫 번째를 쓴다(재검사가 정확성을 보장). 등가는 항상
     * 가장 좁으므로 앞선 경계를 덮어쓴다. PostgreSQL `_bt_preprocess_keys()`는 더 좁은
     * 쪽을 고르지만 리터럴 비교 코드를 줄이기 위해 단순화했다:
     * https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtutils.c
     */
    private fun indexRange(conditions: List<IndexCondition>, column: String): Pair<Bound?, Bound?>? {
        var lower: Bound? = null
        var upper: Bound? = null
        for (cond in conditions) {
            if (cond.column != column) continue
            when (cond.op) {
                BinaryOperator.EQ -> {
                    lower = Bound(cond.literal, true)
                    upper = Bound(cond.literal, true)
                }
                BinaryOperator.GT -> lower = lower ?: Bound(cond.literal, false)
                BinaryOperator.GTE -> lower = lower ?: Bound(cond.literal, true)
                BinaryOperator.LT -> upper = upper ?: Bound(cond.literal, false)
                BinaryOperator.LTE -> upper = upper ?: Bound(cond.literal, true)
                else -> {}
            }
        }
        if (lower == null && upper == null) return null
        return lower to upper
    }

    /** 경계 쌍의 선택도. 등가면 등가 선택도, 정수 경계면 통계 기반, 그 외는 기본값. */
    private fun rangeSelectivity(lower: Bound?, upper: Bound?, stats: ColumnStats?): Double {
        val isEquality = lower != null && upper != null &&
            lower.inclusive && upper.inclusive && lower.value == upper.value
        if (isEquality) return CostEstimator.equalitySelectivity(stats)
        val lo = (lower?.value as? Expression.IntLiteral)?.value
        val hi = (upper?.value as? Expression.IntLiteral)?.value
        if (lo == null && hi == null) {
            // VARCHAR 등 비정수 경계: 통계가 없으므로 기본값
            return if (lower != null && upper != null) CostEstimator.DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
            else CostEstimator.DEFAULT_RANGE_SELECTIVITY
        }
        return CostEstimator.rangeSelectivity(stats, lo, hi)
    }

    /** 필터 적용 후 예상 행 수. 인덱스 조건이 있는 컬럼 중 가장 좁은 선택도를 쓴다. */
    private fun estimateFilteredRows(tableName: String, conditions: List<IndexCondition>, totalRows: Long): Long {
        val selectivities = conditions.map { it.column }.distinct().mapNotNull { column ->
            indexRange(conditions, column)?.let { (lower, upper) ->
                rangeSelectivity(lower, upper, catalog.getColumnStats(tableName, column))
            }
        }
        val sel = selectivities.minOrNull() ?: CostEstimator.DEFAULT_OTHER_SELECTIVITY
        return max(1, (totalRows * sel).toLong())
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

    private companion object {
        val RANGE_OPERATORS = setOf(
            BinaryOperator.EQ, BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE,
        )
    }
}
