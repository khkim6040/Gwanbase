package gwanbase.table

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테이블의 한 행(row)을 나타낸다.
 *
 * 직렬화 레이아웃:
 * ```
 * [null bitmap: ceil(columnCount / 8) bytes]
 * [컬럼 0 값][컬럼 1 값]...[컬럼 N-1 값]  (스키마 순서)
 * ```
 *
 * - 고정 크기 타입: 해당 바이트 수만큼 기록 (NULL이면 0으로 채움)
 * - VARCHAR: (length: Short)(UTF-8 bytes) (NULL이면 length=0)
 * - ByteOrder: BIG_ENDIAN
 */
class Tuple(val schema: Schema, private val values: Array<Any?>) {

    init {
        require(values.size == schema.columnCount) {
            "값 개수(${values.size})가 컬럼 수(${schema.columnCount})와 일치하지 않는다"
        }
        for (i in values.indices) {
            val col = schema.column(i)
            val value = values[i]
            require(value != null || col.nullable) {
                "컬럼 '${col.name}'은 nullable이 아닌데 NULL 값이 주어졌다"
            }
            if (value != null) {
                val expectedType = when (col.type) {
                    DataType.BOOLEAN -> Boolean::class
                    DataType.INT32 -> Int::class
                    DataType.INT64 -> Long::class
                    DataType.FLOAT64 -> Double::class
                    DataType.TIMESTAMP -> Long::class
                    DataType.VARCHAR -> String::class
                }
                require(expectedType.isInstance(value)) {
                    "컬럼 '${col.name}'(${col.type})에 잘못된 타입 ${value::class.simpleName}이 주어졌다"
                }
            }
        }
    }

    /** 컬럼 값이 NULL인지 확인 */
    fun isNull(index: Int): Boolean = values[index] == null

    fun getBoolean(index: Int): Boolean? = values[index] as? Boolean
    fun getInt(index: Int): Int? = values[index] as? Int
    fun getLong(index: Int): Long? = values[index] as? Long
    fun getDouble(index: Int): Double? = values[index] as? Double
    fun getString(index: Int): String? = values[index] as? String
    fun getTimestamp(index: Int): Long? = values[index] as? Long

    /** 이 Tuple을 바이트 배열로 직렬화한다. */
    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(estimateSize()).order(ByteOrder.BIG_ENDIAN)

        // 1. null bitmap
        val bitmapSize = nullBitmapSize(schema.columnCount)
        val bitmap = ByteArray(bitmapSize)
        for (i in values.indices) {
            if (values[i] == null) {
                bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        buf.put(bitmap)

        // 2. 각 컬럼 값을 스키마 순서대로 기록
        for (i in values.indices) {
            val col = schema.column(i)
            val value = values[i]
            when (col.type) {
                DataType.BOOLEAN -> buf.put(if (value as? Boolean == true) 1.toByte() else 0.toByte())
                DataType.INT32 -> buf.putInt(value as? Int ?: 0)
                DataType.INT64, DataType.TIMESTAMP -> buf.putLong(value as? Long ?: 0L)
                DataType.FLOAT64 -> buf.putDouble(value as? Double ?: 0.0)
                DataType.VARCHAR -> {
                    val bytes = (value as? String)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    buf.putShort(bytes.size.toShort())
                    buf.put(bytes)
                }
            }
        }

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    private fun estimateSize(): Int {
        var size = nullBitmapSize(schema.columnCount)
        for (i in values.indices) {
            val col = schema.column(i)
            size += when (col.type) {
                DataType.BOOLEAN -> 1
                DataType.INT32 -> 4
                DataType.INT64 -> 8
                DataType.FLOAT64 -> 8
                DataType.TIMESTAMP -> 8
                DataType.VARCHAR -> {
                    val bytes = (values[i] as? String)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    2 + bytes.size
                }
            }
        }
        return size
    }

    companion object {
        /** null bitmap에 필요한 바이트 수 */
        fun nullBitmapSize(columnCount: Int): Int = (columnCount + 7) / 8

        /** 바이트 배열을 Tuple로 역직렬화한다. */
        fun deserialize(schema: Schema, data: ByteArray): Tuple {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            // 1. null bitmap 읽기
            val bitmapSize = nullBitmapSize(schema.columnCount)
            val bitmap = ByteArray(bitmapSize)
            buf.get(bitmap)

            // 2. 각 컬럼 값 읽기
            val values = Array<Any?>(schema.columnCount) { null }
            for (i in 0 until schema.columnCount) {
                val isNull = (bitmap[i / 8].toInt() and (1 shl (i % 8))) != 0
                val col = schema.column(i)
                when (col.type) {
                    DataType.BOOLEAN -> {
                        val v = buf.get()
                        values[i] = if (isNull) null else v != 0.toByte()
                    }
                    DataType.INT32 -> {
                        val v = buf.getInt()
                        values[i] = if (isNull) null else v
                    }
                    DataType.INT64 -> {
                        val v = buf.getLong()
                        values[i] = if (isNull) null else v
                    }
                    DataType.FLOAT64 -> {
                        val v = buf.getDouble()
                        values[i] = if (isNull) null else v
                    }
                    DataType.TIMESTAMP -> {
                        val v = buf.getLong()
                        values[i] = if (isNull) null else v
                    }
                    DataType.VARCHAR -> {
                        val len = buf.getShort().toInt() and 0xFFFF
                        if (isNull) {
                            if (len > 0) buf.position(buf.position() + len)
                            values[i] = null
                        } else {
                            val bytes = ByteArray(len)
                            buf.get(bytes)
                            values[i] = String(bytes, Charsets.UTF_8)
                        }
                    }
                }
            }

            return Tuple(schema, values)
        }
    }
}
