package gwanbase.index

import gwanbase.table.DataType
import gwanbase.table.RID
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KeySerializerTest {

    /** ByteArray를 unsigned lexicographic 순서로 비교한다. */
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    // --- INT32 정렬 순서 보존 ---

    @Test
    fun `INT32 - 음수가 0보다 앞에 정렬된다`() {
        val neg = KeySerializer.serializeKey(-1, DataType.INT32)
        val zero = KeySerializer.serializeKey(0, DataType.INT32)
        compareBytes(neg, zero) shouldBeLessThan 0
    }

    @Test
    fun `INT32 - 0이 양수보다 앞에 정렬된다`() {
        val zero = KeySerializer.serializeKey(0, DataType.INT32)
        val pos = KeySerializer.serializeKey(1, DataType.INT32)
        compareBytes(zero, pos) shouldBeLessThan 0
    }

    @Test
    fun `INT32 - MIN_VALUE가 MAX_VALUE보다 앞에 정렬된다`() {
        val min = KeySerializer.serializeKey(Int.MIN_VALUE, DataType.INT32)
        val max = KeySerializer.serializeKey(Int.MAX_VALUE, DataType.INT32)
        compareBytes(min, max) shouldBeLessThan 0
    }

    @Test
    fun `INT32 - 여러 값의 정렬 순서가 보존된다`() {
        val values = listOf(Int.MIN_VALUE, -1000, -1, 0, 1, 1000, Int.MAX_VALUE)
        val serialized = values.map { KeySerializer.serializeKey(it, DataType.INT32) }

        for (i in 0 until serialized.size - 1) {
            compareBytes(serialized[i], serialized[i + 1]) shouldBeLessThan 0
        }
    }

    @Test
    fun `INT32 - Long 타입 입력도 변환된다`() {
        val fromInt = KeySerializer.serializeKey(42, DataType.INT32)
        val fromLong = KeySerializer.serializeKey(42L, DataType.INT32)
        fromInt shouldBe fromLong
    }

    // --- INT64 정렬 순서 보존 ---

    @Test
    fun `INT64 - 음수가 0보다 앞에 정렬된다`() {
        val neg = KeySerializer.serializeKey(-1L, DataType.INT64)
        val zero = KeySerializer.serializeKey(0L, DataType.INT64)
        compareBytes(neg, zero) shouldBeLessThan 0
    }

    @Test
    fun `INT64 - MIN_VALUE가 MAX_VALUE보다 앞에 정렬된다`() {
        val min = KeySerializer.serializeKey(Long.MIN_VALUE, DataType.INT64)
        val max = KeySerializer.serializeKey(Long.MAX_VALUE, DataType.INT64)
        compareBytes(min, max) shouldBeLessThan 0
    }

    @Test
    fun `INT64 - 여러 값의 정렬 순서가 보존된다`() {
        val values = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        val serialized = values.map { KeySerializer.serializeKey(it, DataType.INT64) }

        for (i in 0 until serialized.size - 1) {
            compareBytes(serialized[i], serialized[i + 1]) shouldBeLessThan 0
        }
    }

    @Test
    fun `TIMESTAMP - INT64와 동일한 직렬화`() {
        val asInt64 = KeySerializer.serializeKey(123456789L, DataType.INT64)
        val asTimestamp = KeySerializer.serializeKey(123456789L, DataType.TIMESTAMP)
        asInt64 shouldBe asTimestamp
    }

    // --- VARCHAR ---

    @Test
    fun `VARCHAR - UTF-8 사전순 정렬`() {
        val a = KeySerializer.serializeKey("apple", DataType.VARCHAR)
        val b = KeySerializer.serializeKey("banana", DataType.VARCHAR)
        compareBytes(a, b) shouldBeLessThan 0
    }

    @Test
    fun `VARCHAR - 빈 문자열`() {
        val empty = KeySerializer.serializeKey("", DataType.VARCHAR)
        val nonEmpty = KeySerializer.serializeKey("a", DataType.VARCHAR)
        compareBytes(empty, nonEmpty) shouldBeLessThan 0
    }

    // --- BOOLEAN ---

    @Test
    fun `BOOLEAN - false가 true보다 앞에 정렬된다`() {
        val f = KeySerializer.serializeKey(false, DataType.BOOLEAN)
        val t = KeySerializer.serializeKey(true, DataType.BOOLEAN)
        compareBytes(f, t) shouldBeLessThan 0
    }

    // --- FLOAT64 미지원 ---

    @Test
    fun `FLOAT64 - 미지원 예외`() {
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            KeySerializer.serializeKey(1.0, DataType.FLOAT64)
        }
    }

    // --- RID 직렬화/역직렬화 ---

    @Test
    fun `RID 직렬화 후 역직렬화 시 동일한 값 반환`() {
        val rid = RID(42, 7)
        val bytes = KeySerializer.serializeRid(rid)
        bytes.size shouldBe 6

        val deserialized = KeySerializer.deserializeRid(bytes)
        deserialized shouldBe rid
    }

    @Test
    fun `RID - 경계값 왕복`() {
        val rid = RID(Int.MAX_VALUE, 65535)
        val bytes = KeySerializer.serializeRid(rid)
        val deserialized = KeySerializer.deserializeRid(bytes)
        deserialized shouldBe rid
    }

    @Test
    fun `RID - 잘못된 크기의 바이트 배열 시 예외`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            KeySerializer.deserializeRid(ByteArray(5))
        }
    }
}
