package gwanbase.table

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TupleTest {

    private val intOnlySchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("age", DataType.INT32),
        )
    )

    private val mixedSchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("name", DataType.VARCHAR, maxLength = 100),
            Column("score", DataType.FLOAT64),
            Column("active", DataType.BOOLEAN),
            Column("created", DataType.TIMESTAMP),
            Column("count", DataType.INT64),
        )
    )

    private val nullableSchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("name", DataType.VARCHAR, maxLength = 100, nullable = true),
            Column("score", DataType.FLOAT64, nullable = true),
        )
    )

    @Test
    fun `RID는 pageId와 slotId를 가진다`() {
        val rid = RID(1, 5)
        rid.pageId shouldBe 1
        rid.slotId shouldBe 5
    }

    @Test
    fun `고정 크기 타입만 있는 Tuple - serialize 후 deserialize 하면 값이 일치한다`() {
        val tuple = Tuple(intOnlySchema, arrayOf(42, 25))

        val bytes = tuple.serialize()
        val restored = Tuple.deserialize(intOnlySchema, bytes)

        restored.getInt(0) shouldBe 42
        restored.getInt(1) shouldBe 25
    }

    @Test
    fun `모든 타입 혼합 Tuple - 각 getter가 정확한 값을 반환한다`() {
        val values: Array<Any?> = arrayOf(1, "Alice", 95.5, true, 1700000000000L, 9999L)
        val tuple = Tuple(mixedSchema, values)

        val bytes = tuple.serialize()
        val restored = Tuple.deserialize(mixedSchema, bytes)

        restored.getInt(0) shouldBe 1
        restored.getString(1) shouldBe "Alice"
        restored.getDouble(2) shouldBe 95.5
        restored.getBoolean(3) shouldBe true
        restored.getTimestamp(4) shouldBe 1700000000000L
        restored.getLong(5) shouldBe 9999L
    }

    @Test
    fun `NULL 포함 Tuple - null bitmap이 정확하다`() {
        val tuple = Tuple(nullableSchema, arrayOf(1, null, null))

        val bytes = tuple.serialize()
        val restored = Tuple.deserialize(nullableSchema, bytes)

        restored.getInt(0) shouldBe 1
        restored.isNull(1) shouldBe true
        restored.getString(1) shouldBe null
        restored.isNull(2) shouldBe true
        restored.getDouble(2) shouldBe null
    }

    @Test
    fun `모든 필드가 NULL인 Tuple`() {
        val allNullableSchema = Schema(
            listOf(
                Column("a", DataType.INT32, nullable = true),
                Column("b", DataType.VARCHAR, maxLength = 50, nullable = true),
            )
        )
        val tuple = Tuple(allNullableSchema, arrayOf(null, null))

        val bytes = tuple.serialize()
        val restored = Tuple.deserialize(allNullableSchema, bytes)

        restored.isNull(0) shouldBe true
        restored.isNull(1) shouldBe true
    }

    @Test
    fun `빈 문자열 VARCHAR`() {
        val schema = Schema(listOf(Column("s", DataType.VARCHAR, maxLength = 100)))
        val tuple = Tuple(schema, arrayOf(""))

        val bytes = tuple.serialize()
        val restored = Tuple.deserialize(schema, bytes)

        restored.getString(0) shouldBe ""
    }

    @Test
    fun `non-nullable 컬럼에 NULL 삽입 시 예외`() {
        assertThrows<IllegalArgumentException> {
            Tuple(intOnlySchema, arrayOf(1, null))
        }
    }

    @Test
    fun `INT32 컬럼에 String 값 삽입 시 예외`() {
        assertThrows<IllegalArgumentException> {
            Tuple(intOnlySchema, arrayOf("not-a-number", 25))
        }
    }

    @Test
    fun `VARCHAR 컬럼에 Int 값 삽입 시 예외`() {
        val schema = Schema(listOf(Column("s", DataType.VARCHAR, maxLength = 100)))
        assertThrows<IllegalArgumentException> {
            Tuple(schema, arrayOf(42))
        }
    }

    @Test
    fun `BOOLEAN 컬럼에 String 값 삽입 시 예외`() {
        val schema = Schema(listOf(Column("b", DataType.BOOLEAN)))
        assertThrows<IllegalArgumentException> {
            Tuple(schema, arrayOf("true"))
        }
    }

    @Test
    fun `property-based - 랜덤 INT32 값 serialize 후 deserialize 일치`() {
        val schema = Schema(listOf(Column("v", DataType.INT32)))
        val random = java.util.Random(42)
        repeat(100) {
            val v = random.nextInt()
            val tuple = Tuple(schema, arrayOf(v))
            val restored = Tuple.deserialize(schema, tuple.serialize())
            restored.getInt(0) shouldBe v
        }
    }
}
