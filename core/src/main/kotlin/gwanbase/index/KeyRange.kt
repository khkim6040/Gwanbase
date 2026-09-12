package gwanbase.index

/**
 * 인덱스 스캔이 읽을 컬럼 값 범위.
 *
 * 경계가 null이면 그 방향은 무제한이다. 등가 조건은 [lower]와 [upper]가 같은 값이고
 * 양쪽 모두 포함인 범위로 표현한다.
 *
 * PostgreSQL nbtree는 시작 위치용 insertion scankey와 종료 판정용 search scankey를
 * 따로 두지만(`_bt_first()`, `_bt_checkkeys()`), Gwanbase는 B+Tree가 바이트 구간
 * `[startKey, endKey)`만 이해하므로 값 범위 하나로 충분하다.
 * - https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/README
 *
 * @param lower 하한 값 (null이면 없음)
 * @param lowerInclusive 하한 포함 여부 (`>=`이면 true, `>`이면 false)
 * @param upper 상한 값 (null이면 없음)
 * @param upperInclusive 상한 포함 여부 (`<=`이면 true, `<`이면 false)
 */
data class KeyRange(
    val lower: Any?,
    val lowerInclusive: Boolean,
    val upper: Any?,
    val upperInclusive: Boolean,
) {
    companion object {
        /** `col = value` 등가 조건 범위. */
        fun equal(value: Any): KeyRange = KeyRange(value, true, value, true)
    }
}
