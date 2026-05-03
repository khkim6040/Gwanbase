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
}
