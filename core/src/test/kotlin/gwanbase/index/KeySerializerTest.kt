package gwanbase.index

import gwanbase.table.DataType
import gwanbase.table.RID
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    @Test
    fun `VARCHAR - 등가 스캔 구간이 접두사를 공유하는 더 긴 문자열을 포함하지 않는다`() {
        // 'abc' 등가 스캔 구간은 [abc, successor(abc)) 이다.
        // 'abcd' + RID 복합 키가 이 구간 밖(뒤)에 있어야 한다.
        val abc = KeySerializer.serializeKey("abc", DataType.VARCHAR)
        val end = KeySerializer.equalityScanEnd(abc)
        val abcdComposite = KeySerializer.compositeKey(
            KeySerializer.serializeKey("abcd", DataType.VARCHAR), RID(0, 0),
        )
        compareBytes(abcdComposite, end) shouldBeGreaterThan 0
    }

    @Test
    fun `VARCHAR - 접두사 복합 키가 더 긴 문자열 복합 키보다 앞에 온다`() {
        // RID 바이트가 문자열 바이트보다 클 수 있어도 순서가 뒤집히지 않아야 한다.
        val abc = KeySerializer.compositeKey(
            KeySerializer.serializeKey("abc", DataType.VARCHAR), RID(Int.MAX_VALUE, 0xFFFF),
        )
        val abcd = KeySerializer.compositeKey(
            KeySerializer.serializeKey("abcd", DataType.VARCHAR), RID(0, 0),
        )
        compareBytes(abc, abcd) shouldBeLessThan 0
    }

    @Test
    fun `VARCHAR - NUL 문자를 포함하면 예외`() {
        assertThrows<IllegalArgumentException> {
            KeySerializer.serializeKey("a\u0000b", DataType.VARCHAR)
        }
    }

    @Test
    fun `VARCHAR - 복합 키 순서가 문자열 순서와 일치한다 (property)`() {
        // 영숫자만 쓰면 UTF-16 비교(String.compareTo)와 UTF-8 바이트 순서가 같다.
        val strings = Arb.string(0..8, Codepoint.alphanumeric()).take(300).toList()
        val rid = RID(123, 45)
        for (a in strings) for (b in strings) {
            if (a == b) continue
            val ka = KeySerializer.compositeKey(KeySerializer.serializeKey(a, DataType.VARCHAR), rid)
            val kb = KeySerializer.compositeKey(KeySerializer.serializeKey(b, DataType.VARCHAR), rid)
            Integer.signum(compareBytes(ka, kb)) shouldBe Integer.signum(a.compareTo(b))
        }
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
