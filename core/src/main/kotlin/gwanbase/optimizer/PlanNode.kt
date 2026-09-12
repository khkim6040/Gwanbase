package gwanbase.optimizer

import gwanbase.sql.Expression
import gwanbase.sql.SelectItem

/**
 * 논리 실행 계획 트리.
 * Optimizer가 생성하고, Planner가 물리 Operator로 변환한다.
 */
sealed class PlanNode {
    /** 추정 결과 행 수. */
    abstract val estimatedRows: Long
    /** 추정 비용. */
    abstract val estimatedCost: Double

    /** 순차 전체 스캔. */
    data class SeqScan(
        val tableName: String,
        val filter: Expression?,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /**
     * 인덱스 스캔. [lowerBound]/[upperBound]가 컬럼 값 범위를 정하고,
     * [filter]는 힙 튜플에서 재검사할 전체 WHERE 조건이다 (인덱스 조건 포함).
     */
    data class IndexScan(
        val tableName: String,
        val indexName: String,
        val indexColumnName: String,
        val lowerBound: Bound?,
        val upperBound: Bound?,
        val filter: Expression?,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode() {
        /** 등가 조건 여부: 양쪽 경계가 같은 값이고 모두 포함. */
        val isEquality: Boolean
            get() = lowerBound != null && upperBound != null &&
                lowerBound.inclusive && upperBound.inclusive &&
                lowerBound.value == upperBound.value

        /** EXPLAIN용 범위 텍스트. */
        internal fun describeRange(): String {
            if (isEquality) return "key=${lowerBound!!.value.toSql()}"
            val lo = lowerBound?.let { (if (it.inclusive) "[" else "(") + it.value.toSql() } ?: "(-inf"
            val hi = upperBound?.let { it.value.toSql() + (if (it.inclusive) "]" else ")") } ?: "+inf)"
            return "range=$lo, $hi"
        }
    }

    /** Nested Loop Join. */
    data class NestedLoopJoin(
        val outer: PlanNode,
        val inner: PlanNode,
        val condition: Expression,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /** 정렬. */
    data class Sort(
        val child: PlanNode,
        val column: String,
        val ascending: Boolean,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /** 결과 행 수 제한. */
    data class Limit(
        val child: PlanNode,
        val count: Int,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /** 컬럼 프로젝션. */
    data class Project(
        val child: PlanNode,
        val columns: List<SelectItem>,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /**
     * EXPLAIN용 텍스트 출력.
     */
    fun explain(indent: Int = 0): String {
        val prefix = "    ".repeat(indent)
        val line = when (this) {
            is SeqScan -> "${prefix}SeqScan(table=$tableName${if (filter != null) ", filter=$filter" else ""})" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is IndexScan -> "${prefix}IndexScan(table=$tableName, index=$indexName, " +
                "${describeRange()}${if (filter != null) ", filter=$filter" else ""})" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is NestedLoopJoin -> "${prefix}NestedLoopJoin(on=$condition)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Sort -> "${prefix}Sort(column=$column, asc=$ascending)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Limit -> "${prefix}Limit(count=$count)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Project -> "${prefix}Project(columns=$columns)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
        }
        val children = when (this) {
            is SeqScan, is IndexScan -> emptyList()
            is NestedLoopJoin -> listOf(outer, inner)
            is Sort -> listOf(child)
            is Limit -> listOf(child)
            is Project -> listOf(child)
        }
        return if (children.isEmpty()) line
        else line + "\n" + children.joinToString("\n") { it.explain(indent + 1) }
    }
}

/**
 * 인덱스 스캔 경계.
 *
 * @param value 리터럴 표현식 (Planner가 실행 시점에 값으로 평가)
 * @param inclusive 경계 포함 여부 (`>=`/`<=`이면 true, `>`/`<`이면 false)
 */
data class Bound(val value: Expression, val inclusive: Boolean)
