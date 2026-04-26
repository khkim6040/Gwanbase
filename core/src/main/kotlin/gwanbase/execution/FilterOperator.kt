package gwanbase.execution

import gwanbase.sql.Expression
import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * WHERE 조건으로 튜플을 필터링하는 연산자.
 *
 * child에서 받은 튜플 중 predicate를 만족하는 것만 통과시킨다.
 *
 * @param child 입력 연산자
 * @param predicate 필터 조건 (SQL WHERE 표현식)
 */
class FilterOperator(
    private val child: Operator,
    private val predicate: Expression,
) : Operator {

    override val outputSchema: Schema
        get() = child.outputSchema

    override fun open() {
        child.open()
    }

    override fun next(): Tuple? {
        while (true) {
            val tuple = child.next() ?: return null
            if (ExpressionEvaluator.evaluateCondition(child.outputSchema, tuple, predicate)) {
                return tuple
            }
        }
    }

    override fun close() {
        child.close()
    }
}
