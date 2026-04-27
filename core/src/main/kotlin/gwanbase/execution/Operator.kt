package gwanbase.execution

import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * Volcano 모델의 연산자 인터페이스.
 *
 * 사용 프로토콜: open() → next() 반복 (null이면 종료) → close()
 */
interface Operator {

    /** 연산자를 초기화한다. 자식 연산자의 open()도 호출해야 한다. */
    fun open()

    /** 다음 튜플을 반환한다. 더 이상 튜플이 없으면 null. */
    fun next(): Tuple?

    /** 연산자를 정리한다. 자식 연산자의 close()도 호출해야 한다. */
    fun close()

    /** 이 연산자의 출력 스키마. */
    val outputSchema: Schema
}
