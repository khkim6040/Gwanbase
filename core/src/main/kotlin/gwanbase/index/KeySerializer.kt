package gwanbase.index

import gwanbase.table.DataType
import gwanbase.table.RID
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테이블 컬럼 값을 B+Tree 키로 직렬화한다.
 *
 * unsigned lexicographic 비교에서 올바른 정렬 순서가 보존되도록
 * 부호 있는 정수 타입은 부호 비트를 반전시킨다.
 * VARCHAR는 뒤에 0x00 종단 바이트를 붙여 접두사 순서를 보존한다.
 */
object KeySerializer {

    /** VARCHAR 키 종단 바이트. 접두사 관계인 문자열들의 복합 키 순서를 보존한다. */
    private val VARCHAR_TERMINATOR = byteArrayOf(0)

    /**
     * 컬럼 값을 B+Tree 키 바이트 배열로 직렬화한다.
     *
     * @param value 직렬화할 값
     * @param dataType 값의 데이터 타입
     * @return unsigned lexicographic 비교 시 정렬 순서가 보존되는 바이트 배열
     */
    fun serializeKey(value: Any, dataType: DataType): ByteArray {
        return when (dataType) {
            DataType.INT32 -> {
                val intVal = when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    else -> error("INT32 직렬화에 지원하지 않는 타입: ${value::class.simpleName}")
                }
                val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(intVal xor Int.MIN_VALUE)
                buf.array()
            }

            DataType.INT64, DataType.TIMESTAMP -> {
                val longVal = when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> error("INT64/TIMESTAMP 직렬화에 지원하지 않는 타입: ${value::class.simpleName}")
                }
                val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                buf.putLong(longVal xor Long.MIN_VALUE)
                buf.array()
            }

            DataType.VARCHAR -> {
                val str = value as String
                // 종단 바이트가 없으면 'abc' + RID 와 'abcd' + RID 의 바이트 순서가 문자열 순서와
                // 어긋나 등가·범위 스캔 구간에 접두사를 공유하는 다른 값이 섞인다.
                // UTF-8은 NUL 문자 외에 0x00 바이트를 만들지 않으므로 0x00이 안전한 종단자다.
                // PostgreSQL도 text에 NUL을 허용하지 않는다:
                // https://www.postgresql.org/docs/current/datatype-character.html
                require('\u0000' !in str) { "VARCHAR 인덱스 키에 NUL 문자를 포함할 수 없다" }
                str.toByteArray(Charsets.UTF_8) + VARCHAR_TERMINATOR
            }

            DataType.BOOLEAN -> byteArrayOf(if (value as Boolean) 1 else 0)

            DataType.FLOAT64 -> error("FLOAT64 인덱스는 MVP에서 미지원")
        }
    }

    /**
     * RID를 6바이트 배열로 직렬화한다.
     *
     * 포맷: `[pageId: Int(4B)][slotId: Short(2B)]` (BIG_ENDIAN)
     *
     * @param rid 직렬화할 Record ID
     * @return 6바이트 배열
     */
    fun serializeRid(rid: RID): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(rid.pageId)
        buf.putShort(rid.slotId.toShort())
        return buf.array()
    }

    /**
     * 6바이트 배열에서 RID를 역직렬화한다.
     *
     * @param bytes 6바이트 배열
     * @return 역직렬화된 Record ID
     */
    fun deserializeRid(bytes: ByteArray): RID {
        require(bytes.size == 6) { "RID 바이트 배열 크기가 6이 아니다: ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return RID(buf.getInt(), buf.getShort().toInt() and 0xFFFF)
    }

    /**
     * 인덱스 키에 RID를 붙여 고유 키를 생성한다 (비고유 인덱스 지원).
     *
     * 동일한 컬럼 값을 가진 여러 행이 B+Tree에 각각 다른 키로 저장되도록
     * columnKey 뒤에 RID를 직렬화하여 붙인다.
     *
     * @param columnKey 컬럼 값의 직렬화 바이트 배열
     * @param rid 행 식별자
     * @return columnKey + serializedRid 복합 키
     */
    fun compositeKey(columnKey: ByteArray, rid: RID): ByteArray {
        return columnKey + serializeRid(rid)
    }

    /**
     * 등가 조건 스캔의 종료 키를 생성한다.
     *
     * columnKey 접두사를 공유하는 모든 복합 키를 포함하도록
     * columnKey의 다음 키(lexicographic successor)를 반환한다.
     *
     * @param columnKey 등가 조건의 컬럼 값 바이트 배열
     * @return columnKey보다 큰 가장 작은 접두사 바이트 배열. columnKey가 전부 0xFF라
     *   successor가 존재하지 않으면(타입의 최대값) null을 반환한다 — 상한 없이 끝까지 스캔하라는 뜻이다.
     */
    fun equalityScanEnd(columnKey: ByteArray): ByteArray? {
        val end = columnKey.copyOf()
        for (i in end.indices.reversed()) {
            val next = (end[i].toInt() and 0xFF) + 1
            if (next <= 0xFF) {
                end[i] = next.toByte()
                return end
            }
            end[i] = 0
        }
        // 전부 0xFF인 경우: 이 접두사보다 큰 바이트 배열은 존재하지 않는다 (unsigned lexicographic 순서에서 최댓값).
        return null
    }

    /**
     * 컬럼 값 범위를 B+Tree 복합 키 스캔 구간 `[startKey, endKey)`로 변환한다.
     *
     * 복합 키가 `컬럼값 + RID`이므로 컬럼값 자체의 successor([equalityScanEnd])가
     * "그 값을 가진 모든 행 다음" 위치가 된다.
     *
     * | 조건    | startKey        | endKey          |
     * |---------|-----------------|-----------------|
     * | `>= v`  | v               | -               |
     * | `> v`   | successor(v)    | -               |
     * | `< v`   | 빈 배열         | v               |
     * | `<= v`  | 빈 배열         | successor(v)    |
     * | `= v`   | v               | successor(v)    |
     *
     * v가 타입 최대값(전부 0xFF)이라 successor가 없으면, endKey는 null(상한 없음)이 되고
     * startKey(`> v`)는 그 무엇과도 매칭되지 않도록 가능한 가장 큰 복합 키보다 큰 값을 쓴다.
     *
     * @return (startKey, endKey). endKey가 null이면 상한 없음
     */
    fun scanBounds(range: KeyRange, dataType: DataType): Pair<ByteArray, ByteArray?> {
        val start = when {
            range.lower == null -> ByteArray(0)
            range.lowerInclusive -> serializeKey(range.lower, dataType)
            else -> {
                val lowerKey = serializeKey(range.lower, dataType)
                // successor가 없으면(하한이 타입 최대값) 그보다 큰 값은 존재하지 않는다.
                // RID까지 포함해 이론상 가능한 가장 큰 복합 키보다도 큰 시작점을 줘서 빈 스캔이 되게 한다.
                equalityScanEnd(lowerKey) ?: (lowerKey + ByteArray(6) { 0xFF.toByte() })
            }
        }
        val end = when {
            range.upper == null -> null
            range.upperInclusive -> equalityScanEnd(serializeKey(range.upper, dataType))
            else -> serializeKey(range.upper, dataType)
        }
        return start to end
    }
}
