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
 */
object KeySerializer {

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

            DataType.VARCHAR -> (value as String).toByteArray(Charsets.UTF_8)

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
     * @return columnKey보다 큰 가장 작은 접두사 바이트 배열
     */
    fun equalityScanEnd(columnKey: ByteArray): ByteArray {
        val end = columnKey.copyOf()
        for (i in end.indices.reversed()) {
            val next = (end[i].toInt() and 0xFF) + 1
            if (next <= 0xFF) {
                end[i] = next.toByte()
                return end
            }
            end[i] = 0
        }
        // 전부 0xFF인 경우: 한 바이트 더 긴 배열 반환
        return columnKey + byteArrayOf(0)
    }
}
