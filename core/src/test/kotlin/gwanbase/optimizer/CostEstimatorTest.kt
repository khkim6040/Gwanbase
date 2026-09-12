package gwanbase.optimizer

import gwanbase.table.ColumnStats
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CostEstimatorTest {

    @Test
    fun `등가 선택도 - 통계 있을 때 1 나누기 distinctCount`() {
        val stats = ColumnStats(distinctCount = 10, minValue = 1L, maxValue = 100L, nullCount = 0)
        CostEstimator.equalitySelectivity(stats) shouldBe 0.1
    }

    @Test
    fun `등가 선택도 - 통계 없을 때 기본값 0점1`() {
        CostEstimator.equalitySelectivity(null) shouldBe CostEstimator.DEFAULT_EQUALITY_SELECTIVITY
    }

    @Test
    fun `등가 선택도 - distinctCount가 0이면 기본값`() {
        val stats = ColumnStats(distinctCount = 0, minValue = null, maxValue = null, nullCount = 0)
        CostEstimator.equalitySelectivity(stats) shouldBe CostEstimator.DEFAULT_EQUALITY_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - 하한만 있을 때 (max - lower) 나누기 (max - min)`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 50, null) shouldBe 0.5
    }

    @Test
    fun `범위 선택도 - 상한만 있을 때 (upper - min) 나누기 (max - min)`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, null, 20) shouldBe 0.2
    }

    @Test
    fun `범위 선택도 - 양방향은 구간 길이 비율`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 20, 30) shouldBe 0.1
    }

    @Test
    fun `범위 선택도 - 경계가 통계 범위 밖이면 0과 1로 clamp`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 200, null) shouldBe 0.0
        CostEstimator.rangeSelectivity(stats, -50, null) shouldBe 1.0
    }

    @Test
    fun `범위 선택도 - 통계 없을 때 단방향 기본값`() {
        CostEstimator.rangeSelectivity(null, 50, null) shouldBe CostEstimator.DEFAULT_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - 통계 없을 때 양방향 기본값`() {
        CostEstimator.rangeSelectivity(null, 20, 30) shouldBe CostEstimator.DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - min과 max가 같으면 기본값`() {
        val stats = ColumnStats(distinctCount = 1, minValue = 5L, maxValue = 5L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 5, null) shouldBe CostEstimator.DEFAULT_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - 경계가 둘 다 없으면 예외`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CostEstimator.rangeSelectivity(null, null, null)
        }
    }

    @Test
    fun `SeqScan 비용 - 행 수에 비례`() {
        val cost100 = CostEstimator.seqScanCost(100)
        val cost1000 = CostEstimator.seqScanCost(1000)
        cost1000 shouldBeGreaterThan cost100
    }

    @Test
    fun `SeqScan 비용 - 최소 1`() {
        CostEstimator.seqScanCost(0) shouldBe 1.0
    }

    @Test
    fun `IndexScan 비용이 낮은 선택도에서 SeqScan보다 작다`() {
        val totalRows = 10000L
        val seqCost = CostEstimator.seqScanCost(totalRows)
        // 등가 조건으로 10개 행만 매치
        val indexCost = CostEstimator.indexScanCost(10)
        indexCost shouldBeLessThan seqCost
    }

    @Test
    fun `NLJ 비용 = outer + outerRows 곱하기 inner`() {
        val outerCost = 10.0
        val outerRows = 100L
        val innerCost = 5.0
        val expected = outerCost + outerRows * innerCost
        CostEstimator.nestedLoopJoinCost(outerCost, outerRows, innerCost) shouldBe expected
    }

    @Test
    fun `Sort 비용 - 자식 비용에 N log N 추가`() {
        val childCost = 10.0
        val sortCost = CostEstimator.sortCost(childCost, 100)
        sortCost shouldBeGreaterThan childCost
    }

    @Test
    fun `Sort 비용 - 행이 1개면 추가 비용 없음`() {
        val childCost = 10.0
        CostEstimator.sortCost(childCost, 1) shouldBe childCost
    }
}
