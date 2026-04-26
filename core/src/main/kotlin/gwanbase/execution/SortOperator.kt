package gwanbase.execution

import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * ORDER BY 정렬을 수행하는 연산자 (blocking operator).
 *
 * open() 시 child의 모든 튜플을 메모리에 수집하여 정렬한다.
 * Volcano 모델에서 정렬은 모든 입력을 봐야 하므로 불가피하게 materialization이 발생한다.
 *
 * @param child 입력 연산자
 * @param sortColumn 정렬 기준 컬럼 이름
 * @param ascending true면 오름차순, false면 내림차순
 */
class SortOperator(
    private val child: Operator,
    private val sortColumn: String,
    private val ascending: Boolean,
) : Operator {

    private var sorted: List<Tuple> = emptyList()
    private var index = 0

    override val outputSchema: Schema
        get() = child.outputSchema

    override fun open() {
        child.open()

        // child의 모든 튜플을 수집
        val buffer = mutableListOf<Tuple>()
        var tuple = child.next()
        while (tuple != null) {
            buffer.add(tuple)
            tuple = child.next()
        }

        // 정렬
        val schema = child.outputSchema
        val colIndex = schema.columnIndex(sortColumn)
        val colType = schema.column(colIndex).type

        val comparator = compareBy<Tuple, Comparable<Any>?>(nullsLast()) { t ->
            @Suppress("UNCHECKED_CAST")
            ExpressionEvaluator.getTupleValue(t, colIndex, colType) as? Comparable<Any>
        }

        sorted = if (ascending) {
            buffer.sortedWith(comparator)
        } else {
            buffer.sortedWith(comparator.reversed())
        }
        index = 0
    }

    override fun next(): Tuple? {
        if (index >= sorted.size) return null
        return sorted[index++]
    }

    override fun close() {
        sorted = emptyList()
        index = 0
        child.close()
    }
}
