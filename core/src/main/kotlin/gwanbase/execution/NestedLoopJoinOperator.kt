package gwanbase.execution

import gwanbase.sql.Expression
import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * Nested Loop Join 연산자.
 *
 * outer의 각 행에 대해 inner를 전체 스캔하며 조건을 만족하는 쌍을 결합한다.
 * inner는 outer 행이 바뀔 때마다 close() → open()으로 재시작한다.
 */
class NestedLoopJoinOperator(
    private val outer: Operator,
    private val inner: Operator,
    private val condition: Expression,
    private val combinedSchema: Schema,
) : Operator {

    private var currentOuter: Tuple? = null
    private var innerOpened = false

    override val outputSchema: Schema get() = combinedSchema

    override fun open() {
        outer.open()
        currentOuter = outer.next()
        if (currentOuter != null) {
            inner.open()
            innerOpened = true
        }
    }

    override fun next(): Tuple? {
        while (currentOuter != null) {
            while (true) {
                val innerTuple = inner.next() ?: break
                val combined = combineTuples(currentOuter!!, innerTuple)
                if (ExpressionEvaluator.evaluateCondition(combinedSchema, combined, condition)) {
                    return combined
                }
            }
            inner.close()
            currentOuter = outer.next()
            if (currentOuter != null) {
                inner.open()
            }
        }
        return null
    }

    override fun close() {
        if (innerOpened) {
            inner.close()
            innerOpened = false
        }
        outer.close()
    }

    private fun combineTuples(outerTuple: Tuple, innerTuple: Tuple): Tuple {
        val outerSchema = outer.outputSchema
        val innerSchema = inner.outputSchema
        val values = Array<Any?>(combinedSchema.columnCount) { i ->
            if (i < outerSchema.columnCount) {
                ExpressionEvaluator.getTupleValue(outerTuple, i, outerSchema.column(i).type)
            } else {
                val innerIdx = i - outerSchema.columnCount
                ExpressionEvaluator.getTupleValue(innerTuple, innerIdx, innerSchema.column(innerIdx).type)
            }
        }
        return Tuple(combinedSchema, values)
    }
}
