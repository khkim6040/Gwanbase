package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.Catalog

/**
 * AST를 받아 최적의 논리 실행 계획(PlanNode)을 반환한다.
 *
 * 단일 테이블에서는 인덱스 존재 여부와 비용 비교로 접근 경로를 결정하고,
 * 다중 테이블에서는 PlanEnumerator를 통해 조인 순서를 결정한다.
 *
 * @param catalog 테이블/인덱스 메타데이터를 제공하는 카탈로그
 */
class Optimizer(private val catalog: Catalog) {

    private val enumerator = PlanEnumerator(catalog)

    /**
     * SELECT 문을 최적의 논리 실행 계획으로 변환한다.
     *
     * @param stmt SELECT AST
     * @return 최적 PlanNode 트리
     */
    fun optimize(stmt: Statement.Select): PlanNode {
        val tables = collectTables(stmt.from)

        var plan: PlanNode = if (tables.size == 1) {
            val tableName = tables[0].first
            enumerator.bestAccessPath(tableName, stmt.where)
        } else {
            val joinConditions = collectJoinConditions(stmt.from)
            val tableNames = tables.map { it.first }
            enumerator.bestJoinOrder(tableNames, joinConditions)
        }

        if (stmt.orderBy != null) {
            val sortCost = CostEstimator.sortCost(plan.estimatedCost, plan.estimatedRows)
            plan = PlanNode.Sort(plan, stmt.orderBy.column, stmt.orderBy.ascending, plan.estimatedRows, sortCost)
        }

        if (stmt.limit != null) {
            val limitRows = minOf(plan.estimatedRows, stmt.limit.toLong())
            plan = PlanNode.Limit(plan, stmt.limit, limitRows, plan.estimatedCost)
        }

        plan = PlanNode.Project(plan, stmt.columns, plan.estimatedRows, plan.estimatedCost)
        return plan
    }

    /**
     * FROM 절에서 테이블 목록(이름, 별칭)을 수집한다.
     */
    private fun collectTables(from: FromClause): List<Pair<String, String?>> = when (from) {
        is FromClause.Table -> listOf(from.tableName to from.alias)
        is FromClause.Join -> collectTables(from.left) + collectTables(from.right)
    }

    /**
     * FROM 절에서 모든 JOIN 조건을 재귀적으로 수집한다.
     *
     * 3개 이상 테이블의 JOIN에서 각 JOIN 절의 ON 조건을 빠짐없이 수집한다.
     */
    private fun collectJoinConditions(from: FromClause): List<Expression> = when (from) {
        is FromClause.Table -> emptyList()
        is FromClause.Join -> collectJoinConditions(from.left) + collectJoinConditions(from.right) + from.condition
    }
}
