package gwanbase.table

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaTest {

    @Test
    fun `DataType fixedSize - 고정 크기 타입은 정확한 바이트 수를 반환한다`() {
        DataType.BOOLEAN.fixedSize shouldBe 1
        DataType.INT32.fixedSize shouldBe 4
        DataType.INT64.fixedSize shouldBe 8
        DataType.FLOAT64.fixedSize shouldBe 8
        DataType.TIMESTAMP.fixedSize shouldBe 8
    }

    @Test
    fun `DataType fixedSize - VARCHAR은 가변 크기이다`() {
        DataType.VARCHAR.fixedSize shouldBe null
        DataType.VARCHAR.isFixedSize shouldBe false
        DataType.INT32.isFixedSize shouldBe true
    }

    @Test
    fun `Column 기본값 - nullable은 false, maxLength는 0`() {
        val col = Column("age", DataType.INT32)
        col.nullable shouldBe false
        col.maxLength shouldBe 0
    }

    @Test
    fun `Schema columnIndex - 이름으로 컬럼 인덱스 조회`() {
        val schema = Schema(
            listOf(
                Column("id", DataType.INT32),
                Column("name", DataType.VARCHAR, maxLength = 100),
                Column("active", DataType.BOOLEAN),
            )
        )

        schema.columnIndex("id") shouldBe 0
        schema.columnIndex("name") shouldBe 1
        schema.columnIndex("active") shouldBe 2
        schema.columnCount shouldBe 3
    }

    @Test
    fun `Schema columnIndex - 존재하지 않는 이름은 예외`() {
        val schema = Schema(listOf(Column("id", DataType.INT32)))
        assertThrows<IllegalArgumentException> {
            schema.columnIndex("nonexistent")
        }
    }

    @Test
    fun `Schema column - 인덱스로 컬럼 조회`() {
        val col = Column("name", DataType.VARCHAR, maxLength = 255, nullable = true)
        val schema = Schema(listOf(col))
        schema.column(0) shouldBe col
    }

    @Test
    fun `Schema 생성 시 중복 컬럼명은 예외`() {
        assertThrows<IllegalArgumentException> {
            Schema(
                listOf(
                    Column("id", DataType.INT32),
                    Column("id", DataType.INT64),
                )
            )
        }
    }
}
