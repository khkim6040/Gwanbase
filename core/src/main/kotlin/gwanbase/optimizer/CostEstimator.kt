package gwanbase.optimizer

import gwanbase.table.ColumnStats
import kotlin.math.ln
import kotlin.math.max

/**
 * 실행 계획의 I/O 비용과 선택도를 추정한다.
 *
 * 비용 모델은 디스크 I/O 횟수를 기준으로 하며,
 * 선택도(selectivity)는 조건이 통과시키는 행의 비율(0.0~1.0)이다.
 */
object CostEstimator {

    /** 통계 없을 때 등가 조건 기본 선택도. */
    const val DEFAULT_EQUALITY_SELECTIVITY = 0.1

    /**
     * 통계 없을 때 단방향 범위 조건(`col > v`)의 기본 선택도.
     * PostgreSQL `DEFAULT_INEQ_SEL`과 같은 값:
     * https://github.com/postgres/postgres/blob/master/src/include/utils/selfuncs.h
     */
    const val DEFAULT_RANGE_SELECTIVITY = 1.0 / 3

    /**
     * 통계 없을 때 양방향 범위 조건(`col > a AND col < b`)의 기본 선택도.
     * PostgreSQL `DEFAULT_RANGE_INEQ_SEL`과 같은 값.
     */
    const val DEFAULT_TWO_SIDED_RANGE_SELECTIVITY = 0.005

    /** 기타 조건의 기본 선택도. */
    const val DEFAULT_OTHER_SELECTIVITY = 0.5

    /**
     * 등가 조건(col = value)의 선택도를 추정한다.
     *
     * 통계가 있으면 1/distinctCount, 없으면 기본값을 반환한다.
     */
    fun equalitySelectivity(stats: ColumnStats?): Double {
        if (stats == null || stats.distinctCount <= 0) return DEFAULT_EQUALITY_SELECTIVITY
        return 1.0 / stats.distinctCount
    }

    /**
     * 범위 조건의 선택도를 추정한다.
     *
     * 통계의 min/max 사이에 값이 균등 분포한다고 가정하고
     * `(min(upper, max) - max(lower, min)) / (max - min)`을 [0, 1]로 clamp한다.
     * 경계 포함 여부는 무시한다. PostgreSQL은 히스토그램으로 각 경계의 선택도를 구한 뒤
     * `hisel + losel - 1`로 결합한다(`clauselist_selectivity_ext()`):
     * https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/clausesel.c
     *
     * @param lower 하한 (null이면 없음)
     * @param upper 상한 (null이면 없음)
     * @throws IllegalArgumentException 경계가 둘 다 없을 때
     */
    fun rangeSelectivity(stats: ColumnStats?, lower: Long?, upper: Long?): Double {
        require(lower != null || upper != null) { "범위 조건에는 경계가 하나 이상 있어야 한다" }
        val default = if (lower != null && upper != null) DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
        else DEFAULT_RANGE_SELECTIVITY
        val min = stats?.minValue as? Long ?: return default
        val max = stats.maxValue as? Long ?: return default
        if (max == min) return default
        val lo = max(lower ?: min, min)
        val hi = minOf(upper ?: max, max)
        return ((hi - lo).toDouble() / (max - min).toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 순차 스캔 비용을 추정한다.
     *
     * @param rowCount 테이블 전체 행 수
     * @param pagesPerRow 행 하나당 평균 페이지 수
     */
    fun seqScanCost(rowCount: Long, pagesPerRow: Double = 0.01): Double =
        max(1.0, rowCount * pagesPerRow)

    /**
     * 인덱스 스캔 비용을 추정한다.
     *
     * B+Tree 높이만큼 탐색 후 매칭된 행 수만큼 랜덤 I/O가 발생한다.
     *
     * @param matchedRows 인덱스로 매칭되는 예상 행 수
     * @param treeHeight B+Tree 높이 (기본 3)
     */
    fun indexScanCost(matchedRows: Long, treeHeight: Int = 3): Double =
        treeHeight.toDouble() + matchedRows.toDouble()

    /**
     * Nested Loop Join 비용을 추정한다.
     *
     * outer를 한 번 스캔하고, outer의 각 행마다 inner를 한 번씩 스캔한다.
     */
    fun nestedLoopJoinCost(outerCost: Double, outerRows: Long, innerCost: Double): Double =
        outerCost + outerRows * innerCost

    /**
     * 정렬 비용을 추정한다.
     *
     * 자식 비용에 N * ln(N) 정렬 비용을 추가한다.
     */
    fun sortCost(childCost: Double, rowCount: Long): Double {
        if (rowCount <= 1) return childCost
        return childCost + rowCount * ln(rowCount.toDouble())
    }
}
