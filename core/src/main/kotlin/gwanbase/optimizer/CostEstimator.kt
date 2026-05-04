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

    /** 통계 없을 때 범위 조건 기본 선택도. */
    const val DEFAULT_RANGE_SELECTIVITY = 0.33

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
     * 범위 조건(col > threshold)의 선택도를 추정한다.
     *
     * 균등 분포를 가정하여 (max - threshold) / (max - min)으로 계산한다.
     */
    fun rangeSelectivity(stats: ColumnStats?, threshold: Long): Double {
        if (stats == null || stats.minValue == null || stats.maxValue == null) return DEFAULT_RANGE_SELECTIVITY
        val min = stats.minValue as Long
        val max = stats.maxValue as Long
        if (max == min) return DEFAULT_RANGE_SELECTIVITY
        return max(0.0, (max - threshold).toDouble() / (max - min).toDouble())
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
