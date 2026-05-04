package gwanbase.optimizer

import gwanbase.execution.ExpressionEvaluator
import gwanbase.table.*

/**
 * 테이블 및 컬럼 통계를 관리한다.
 * ANALYZE 실행 시 전체 스캔으로 컬럼 통계를 수집하고 Catalog에 저장한다.
 */
class StatisticsManager(private val catalog: Catalog) {

    /**
     * 테이블의 통계를 수집한다.
     *
     * 전체 스캔으로 각 컬럼의 고유값 수, 최솟값, 최댓값, NULL 수를 계산하고
     * Catalog에 저장한다.
     *
     * @param database 대상 데이터베이스
     * @param tableName 통계를 수집할 테이블 이름
     * @return 스캔한 행 수
     * @throws IllegalArgumentException 테이블이 존재하지 않을 때
     */
    fun analyze(database: Database, tableName: String): Long {
        val tableInfo = database.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val schema = tableInfo.schema
        val collectors = (0 until schema.columnCount).map { ColumnStatsCollector() }
        var rowCount = 0L
        val iter = database.scanTable(tableName)
        while (iter.hasNext()) {
            val (_, tuple) = iter.next()
            rowCount++
            for (i in 0 until schema.columnCount) {
                collectors[i].observe(ExpressionEvaluator.getTupleValue(tuple, i, schema.column(i).type))
            }
        }
        for (i in 0 until schema.columnCount) {
            catalog.updateColumnStats(tableName, schema.column(i).name, collectors[i].build())
        }
        return rowCount
    }
}

/**
 * 단일 컬럼의 통계를 수집하는 내부 헬퍼.
 *
 * observe()로 값을 관찰하고 build()로 최종 ColumnStats를 생성한다.
 */
private class ColumnStatsCollector {
    private val distinctValues = mutableSetOf<Any>()
    private var minValue: Long? = null
    private var maxValue: Long? = null
    private var nullCount = 0L

    /** 컬럼 값을 관찰하여 통계에 반영한다. */
    fun observe(value: Any?) {
        if (value == null) { nullCount++; return }
        distinctValues.add(value)
        val numeric = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
        if (numeric != null) {
            minValue = if (minValue == null) numeric else minOf(minValue!!, numeric)
            maxValue = if (maxValue == null) numeric else maxOf(maxValue!!, numeric)
        }
    }

    /** 수집된 통계로 ColumnStats를 생성한다. */
    fun build() = ColumnStats(distinctValues.size.toLong(), minValue, maxValue, nullCount)
}
