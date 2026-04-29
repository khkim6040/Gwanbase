package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.txn.DatabaseSession

/**
 * AST Statement를 연산자 트리로 변환한다.
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
 */
class Planner(
    private val database: Database,
    private val session: DatabaseSession? = null,
) {

    /**
     * SELECT 문을 연산자 트리로 변환한다.
     */
    fun planSelect(stmt: Statement.Select): Operator {
        var op: Operator = SeqScanOperator(database, stmt.tableName, session)

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

}
