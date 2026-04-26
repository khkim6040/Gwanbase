package gwanbase.execution

import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * 상위 N개 튜플만 반환하는 연산자.
 *
 * limit 횟수만큼 child.next()를 전달한 후 null을 반환한다.
 *
 * @param child 입력 연산자
 * @param limit 최대 반환 튜플 수
 */
class LimitOperator(
    private val child: Operator,
    private val limit: Int,
) : Operator {

    private var count = 0

    override val outputSchema: Schema
        get() = child.outputSchema

    override fun open() {
        child.open()
        count = 0
    }

    override fun next(): Tuple? {
        if (count >= limit) return null
        val tuple = child.next() ?: return null
        count++
        return tuple
    }

    override fun close() {
        child.close()
        count = 0
    }
}
