# Phase 2: Table Storage Engine 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 1의 storage 레이어 위에 Schema, Tuple, HeapFile, Catalog, Database를 구축하여 관계형 테이블 저장소를 완성한다.

**Architecture:** `gwanbase.table` 패키지를 새로 만들어 `gwanbase.storage`에만 의존한다. HeapFile은 SlottedPage를 감싸고, Free Page List로 빈 공간을 관리한다. 단일 DB 파일에 메타데이터(pageId=0), Catalog(pageId=1), 데이터 페이지(pageId=2+)가 공존한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions + property-based testing, 기존 `gwanbase.storage` 모듈

**스펙 문서:** `docs/specs/phase-2-table-storage.md`

---

## 파일 구조

### 신규 생성 파일

| 파일 | 책임 |
|---|---|
| `core/src/main/kotlin/gwanbase/table/DataType.kt` | 지원 타입 enum |
| `core/src/main/kotlin/gwanbase/table/Column.kt` | 컬럼 정의 data class |
| `core/src/main/kotlin/gwanbase/table/Schema.kt` | 테이블 스키마 (컬럼 목록, 인덱스 조회) |
| `core/src/main/kotlin/gwanbase/table/Tuple.kt` | 행 데이터 + 직렬화/역직렬화 |
| `core/src/main/kotlin/gwanbase/table/RID.kt` | 튜플 물리 위치 식별자 |
| `core/src/main/kotlin/gwanbase/table/HeapPage.kt` | SlottedPage를 감싸는 HeapFile 데이터 페이지 |
| `core/src/main/kotlin/gwanbase/table/HeapFile.kt` | 순서 없는 튜플 저장 + Free Page List |
| `core/src/main/kotlin/gwanbase/table/Catalog.kt` | 테이블 메타데이터 영속 저장 |
| `core/src/main/kotlin/gwanbase/table/Database.kt` | 진입점 (open/close, 테이블 CRUD) |
| `core/src/test/kotlin/gwanbase/table/SchemaTest.kt` | Schema/Column/DataType 테스트 |
| `core/src/test/kotlin/gwanbase/table/TupleTest.kt` | Tuple 직렬화/역직렬화 테스트 |
| `core/src/test/kotlin/gwanbase/table/HeapPageTest.kt` | HeapPage 단위 테스트 |
| `core/src/test/kotlin/gwanbase/table/HeapFileTest.kt` | HeapFile 통합 테스트 |
| `core/src/test/kotlin/gwanbase/table/CatalogTest.kt` | Catalog 영속성 테스트 |
| `core/src/test/kotlin/gwanbase/table/DatabaseTest.kt` | Database 통합 테스트 |

### 기존 파일 (수정 없음, 참조만)

| 파일 | 역할 |
|---|---|
| `core/src/main/kotlin/gwanbase/storage/SlottedPage.kt` | 가변 길이 레코드 페이지 — HeapPage가 감쌈 |
| `core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt` | 버퍼 풀 — HeapFile/Catalog/Database가 사용 |
| `core/src/main/kotlin/gwanbase/storage/DiskManager.kt` | 디스크 I/O — Database.open()에서 생성 |
| `core/src/main/kotlin/gwanbase/storage/Page.kt` | 메모리 페이지 프레임 |
| `core/src/main/kotlin/gwanbase/storage/ByteBufferExtensions.kt` | ByteBuffer 유틸리티 |

---

## Task 1: DataType, Column, Schema

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/DataType.kt`
- Create: `core/src/main/kotlin/gwanbase/table/Column.kt`
- Create: `core/src/main/kotlin/gwanbase/table/Schema.kt`
- Create: `core/src/test/kotlin/gwanbase/table/SchemaTest.kt`

### Step 1-1: 실패하는 테스트 작성

```kotlin
// core/src/test/kotlin/gwanbase/table/SchemaTest.kt
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
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 1-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.SchemaTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (DataType, Column, Schema 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 1-3: DataType 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/DataType.kt
package gwanbase.table

/**
 * 지원하는 데이터 타입.
 *
 * [fixedSize]가 null이면 가변 크기 타입이다 (예: VARCHAR).
 */
enum class DataType(val fixedSize: Int?) {
    BOOLEAN(1),
    INT32(4),
    INT64(8),
    FLOAT64(8),
    TIMESTAMP(8),
    VARCHAR(null);

    /** 고정 크기 타입 여부 */
    val isFixedSize: Boolean get() = fixedSize != null
}
```

- [ ] DataType.kt를 생성한다.

### Step 1-4: Column 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/Column.kt
package gwanbase.table

/**
 * 테이블 컬럼 정의.
 *
 * @param name 컬럼 이름
 * @param type 데이터 타입
 * @param maxLength VARCHAR 전용 최대 바이트 수 (다른 타입은 0)
 * @param nullable NULL 허용 여부 (기본 false)
 */
data class Column(
    val name: String,
    val type: DataType,
    val maxLength: Int = 0,
    val nullable: Boolean = false,
)
```

- [ ] Column.kt를 생성한다.

### Step 1-5: Schema 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/Schema.kt
package gwanbase.table

/**
 * 테이블 스키마. 컬럼 목록과 이름 기반 인덱스 조회를 제공한다.
 */
class Schema(val columns: List<Column>) {

    private val nameToIndex: Map<String, Int> =
        columns.mapIndexed { index, col -> col.name to index }.toMap()

    /** 컬럼 수 */
    val columnCount: Int get() = columns.size

    /**
     * 컬럼 이름으로 인덱스를 조회한다.
     * @throws IllegalArgumentException 존재하지 않는 이름
     */
    fun columnIndex(name: String): Int =
        requireNotNull(nameToIndex[name]) { "컬럼 '$name'이 스키마에 존재하지 않는다" }

    /** 인덱스로 컬럼을 조회한다. */
    fun column(index: Int): Column = columns[index]
}
```

- [ ] Schema.kt를 생성한다.

### Step 1-6: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.SchemaTest"`

Expected: 6 tests passed

- [ ] 모든 테스트가 통과하는 것을 확인한다.

### Step 1-7: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/DataType.kt \
       core/src/main/kotlin/gwanbase/table/Column.kt \
       core/src/main/kotlin/gwanbase/table/Schema.kt \
       core/src/test/kotlin/gwanbase/table/SchemaTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] DataType, Column, Schema 구현
EOF
)"
```

- [ ] 커밋한다.

---

## Task 2: RID + Tuple 직렬화/역직렬화

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/RID.kt`
- Create: `core/src/main/kotlin/gwanbase/table/Tuple.kt`
- Create: `core/src/test/kotlin/gwanbase/table/TupleTest.kt`

### Step 2-1: 실패하는 테스트 작성

```kotlin
// core/src/test/kotlin/gwanbase/table/TupleTest.kt
package gwanbase.table

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
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
    fun `property-based - 랜덤 INT32 값 serialize 후 deserialize 일치`() = runBlocking {
        val schema = Schema(listOf(Column("v", DataType.INT32)))
        checkAll(100, Arb.int()) { v ->
            val tuple = Tuple(schema, arrayOf(v))
            val restored = Tuple.deserialize(schema, tuple.serialize())
            restored.getInt(0) shouldBe v
        }
    }
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 2-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.TupleTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (RID, Tuple 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 2-3: RID 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/RID.kt
package gwanbase.table

/**
 * 튜플의 물리 위치를 가리키는 Record ID.
 *
 * HeapFile 내에서 [pageId] 페이지의 [slotId] 슬롯에 위치한 레코드를 식별한다.
 */
data class RID(val pageId: Int, val slotId: Int)
```

- [ ] RID.kt를 생성한다.

### Step 2-4: Tuple 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/Tuple.kt
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
        // non-nullable 컬럼에 null이 들어오면 예외
        for (i in values.indices) {
            if (values[i] == null && !schema.column(i).nullable) {
                require(false) {
                    "컬럼 '${schema.column(i).name}'은 nullable이 아닌데 NULL 값이 주어졌다"
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
                DataType.INT64 -> buf.putLong(value as? Long ?: 0L)
                DataType.FLOAT64 -> buf.putDouble(value as? Double ?: 0.0)
                DataType.TIMESTAMP -> buf.putLong(value as? Long ?: 0L)
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
                    2 + bytes.size  // Short length prefix + data
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
                        if (isNull || len == 0 && isNull) {
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
```

- [ ] RID.kt와 Tuple.kt를 생성한다.

### Step 2-5: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.TupleTest"`

Expected: 8 tests passed

- [ ] 모든 테스트가 통과하는 것을 확인한다.

### Step 2-6: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/RID.kt \
       core/src/main/kotlin/gwanbase/table/Tuple.kt \
       core/src/test/kotlin/gwanbase/table/TupleTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] RID와 Tuple 직렬화/역직렬화 구현
EOF
)"
```

- [ ] 커밋한다.

---

## Task 3: HeapPage — SlottedPage를 감싸는 데이터 페이지

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/HeapPage.kt`
- Create: `core/src/test/kotlin/gwanbase/table/HeapPageTest.kt`

**배경:** HeapFile의 각 데이터 페이지는 앞 4바이트를 `nextFreePageId`로 예약하고, 나머지 영역을 SlottedPage로 사용한다. SlottedPage 코드를 수정하지 않기 위해 ByteBuffer slice를 활용한다.

### Step 3-1: 실패하는 테스트 작성

```kotlin
// core/src/test/kotlin/gwanbase/table/HeapPageTest.kt
package gwanbase.table

import gwanbase.storage.DiskManager
import gwanbase.storage.newPageBuffer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HeapPageTest {

    private lateinit var heapPage: HeapPage

    @BeforeEach
    fun setUp() {
        val buffer = newPageBuffer()
        heapPage = HeapPage(buffer)
        heapPage.init()
    }

    @Test
    fun `초기 상태 - nextFreePageId는 INVALID, 레코드 없음`() {
        heapPage.nextFreePageId shouldBe HeapPage.INVALID_PAGE_ID
        heapPage.recordCount shouldBe 0
    }

    @Test
    fun `레코드 삽입 및 조회`() {
        val data = "Hello, Table!".toByteArray()
        val slotId = heapPage.insertRecord(data)

        slotId shouldBe 0
        heapPage.getRecord(slotId) shouldBe data
        heapPage.recordCount shouldBe 1
    }

    @Test
    fun `레코드 삭제 후 null 반환`() {
        val slotId = heapPage.insertRecord("delete-me".toByteArray())
        heapPage.deleteRecord(slotId) shouldBe true
        heapPage.getRecord(slotId).shouldBeNull()
    }

    @Test
    fun `nextFreePageId 설정 및 조회`() {
        heapPage.nextFreePageId = 42
        heapPage.nextFreePageId shouldBe 42
    }

    @Test
    fun `여유 공간 확인`() {
        val initialFree = heapPage.freeSpace
        val data = ByteArray(100)
        heapPage.insertRecord(data)
        heapPage.freeSpace shouldBe initialFree - 100 - 4  // 레코드 + 슬롯 엔트리
    }

    @Test
    fun `HeapPage 메타데이터는 SlottedPage 영역을 침범하지 않는다`() {
        // 4088바이트(PAGE_SIZE - HEAP_PAGE_HEADER_SIZE)보다 작은 레코드를 넣을 수 있어야 함
        // SlottedPage 헤더(4) + 슬롯(4) = 8바이트를 빼면 최대 레코드 크기는 4080
        val maxRecord = ByteArray(heapPage.freeSpace - 4) // 슬롯 엔트리 4바이트 제외
        val slotId = heapPage.insertRecord(maxRecord)
        slotId shouldBe 0
        heapPage.getRecord(slotId) shouldBe maxRecord
    }
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 3-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.HeapPageTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (HeapPage 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 3-3: HeapPage 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/HeapPage.kt
package gwanbase.table

import gwanbase.storage.DiskManager
import gwanbase.storage.SlottedPage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HeapFile의 데이터 페이지.
 *
 * 페이지 앞 [HEADER_SIZE]바이트는 [nextFreePageId]를 저장하고,
 * 나머지 영역은 [SlottedPage]로 위임한다.
 *
 * 레이아웃:
 * ```
 * [nextFreePageId: 4 bytes] [SlottedPage 영역: PAGE_SIZE - 4 bytes]
 * ```
 */
class HeapPage(private val buffer: ByteBuffer) {

    companion object {
        /** HeapPage 전용 헤더 크기 (nextFreePageId) */
        const val HEADER_SIZE = 4

        const val INVALID_PAGE_ID = -1
    }

    init {
        buffer.order(ByteOrder.BIG_ENDIAN)
    }

    /** Free Page List에서 다음 페이지의 pageId. 없으면 [INVALID_PAGE_ID]. */
    var nextFreePageId: Int
        get() = buffer.getInt(0)
        set(value) { buffer.putInt(0, value) }

    /**
     * SlottedPage를 offset [HEADER_SIZE]부터 시작하는 slice로 생성한다.
     * buffer의 position 4 ~ limit 사이가 SlottedPage 영역이 된다.
     */
    private val slottedPage: SlottedPage = run {
        buffer.position(HEADER_SIZE)
        buffer.limit(DiskManager.PAGE_SIZE)
        val slice = buffer.slice().order(ByteOrder.BIG_ENDIAN)
        // 원본 buffer를 복원
        buffer.position(0)
        buffer.limit(DiskManager.PAGE_SIZE)
        SlottedPage(slice)
    }

    /** 빈 페이지로 초기화 */
    fun init() {
        nextFreePageId = INVALID_PAGE_ID
        slottedPage.init()
    }

    /** 레코드 삽입. 공간 부족 시 -1 반환. */
    fun insertRecord(record: ByteArray): Int = slottedPage.insertRecord(record)

    /** 슬롯 ID로 레코드 조회 */
    fun getRecord(slotId: Int): ByteArray? = slottedPage.getRecord(slotId)

    /** 슬롯 삭제 */
    fun deleteRecord(slotId: Int): Boolean = slottedPage.deleteRecord(slotId)

    /** 유효한 레코드 수 */
    val recordCount: Int get() = slottedPage.allRecords().size

    /** 여유 공간 바이트 수 */
    val freeSpace: Int get() = slottedPage.freeSpace

    /** 모든 유효한 (slotId, record) 쌍 반환 */
    fun allRecords(): List<Pair<Int, ByteArray>> = slottedPage.allRecords()
}
```

- [ ] HeapPage.kt를 생성한다.

### Step 3-4: SlottedPage init()이 slice에서 동작하도록 확인

SlottedPage의 `init()`은 `freeSpaceOffset = DiskManager.PAGE_SIZE`를 설정한다. 그런데 HeapPage의 slice는 `PAGE_SIZE - 4` 크기이다. SlottedPage가 slice 크기를 기준으로 동작하도록 `init()`을 수정해야 한다.

`SlottedPage.init()`에서 `DiskManager.PAGE_SIZE` 대신 `buffer.capacity()`를 사용하도록 수정한다:

```kotlin
// core/src/main/kotlin/gwanbase/storage/SlottedPage.kt
// 기존:
fun init() {
    slotCount = 0
    freeSpaceOffset = DiskManager.PAGE_SIZE
}

// 변경:
fun init() {
    slotCount = 0
    freeSpaceOffset = buffer.capacity()
}
```

- [ ] SlottedPage.kt의 `init()` 메서드를 수정한다.

### Step 3-5: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.HeapPageTest"`

Expected: 6 tests passed

기존 테스트도 깨지지 않는지 확인:

Run: `./gradlew :core:test`

Expected: 전체 통과

- [ ] HeapPageTest 통과 및 기존 테스트 미파손 확인.

### Step 3-6: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/HeapPage.kt \
       core/src/main/kotlin/gwanbase/storage/SlottedPage.kt \
       core/src/test/kotlin/gwanbase/table/HeapPageTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] HeapPage 구현 (SlottedPage slice 위임)
EOF
)"
```

- [ ] 커밋한다.

---

## Task 4: HeapFile — 튜플 저장소 + Free Page List

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/HeapFile.kt`
- Create: `core/src/test/kotlin/gwanbase/table/HeapFileTest.kt`

**배경:** HeapFile은 헤더 페이지 1개 + 데이터 페이지 N개로 구성된다. 헤더 페이지에 `firstFreePageId`와 `pageCount`를 저장하고, 데이터 페이지는 HeapPage로 관리한다.

### Step 4-1: 실패하는 테스트 작성 (기본 CRUD)

```kotlin
// core/src/test/kotlin/gwanbase/table/HeapFileTest.kt
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HeapFileTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createHeapFile(poolSize: Int = 64): Pair<HeapFile, BufferPoolManager> {
        val dm = DiskManager(tempDir.resolve("heap_test.db"))
        val bpm = BufferPoolManager(dm, poolSize)
        val heapFile = HeapFile.createNew(bpm)
        return heapFile to bpm
    }

    @Test
    fun `빈 HeapFile - scan 결과 없음`() {
        val (heapFile, _) = createHeapFile()
        val results = heapFile.scan().asSequence().toList()
        results shouldHaveSize 0
    }

    @Test
    fun `단일 삽입 및 조회`() {
        val (heapFile, _) = createHeapFile()
        val data = "Hello, HeapFile!".toByteArray()

        val rid = heapFile.insertTuple(data)
        rid.shouldNotBeNull()

        val result = heapFile.getTuple(rid)
        result shouldBe data
    }

    @Test
    fun `여러 건 삽입 후 모두 조회`() {
        val (heapFile, _) = createHeapFile()
        val entries = (0 until 100).map { "record-$it".toByteArray() }
        val rids = entries.map { heapFile.insertTuple(it) }

        rids.zip(entries).forEach { (rid, expected) ->
            heapFile.getTuple(rid) shouldBe expected
        }
    }

    @Test
    fun `삭제 후 getTuple은 null`() {
        val (heapFile, _) = createHeapFile()
        val rid = heapFile.insertTuple("delete-me".toByteArray())

        heapFile.deleteTuple(rid) shouldBe true
        heapFile.getTuple(rid).shouldBeNull()
    }

    @Test
    fun `삭제 후 재삽입 - 공간 재활용`() {
        val (heapFile, _) = createHeapFile()

        // 페이지를 채운 후 일부 삭제
        val rids = (0 until 50).map { heapFile.insertTuple(ByteArray(50) { it.toByte() }) }
        rids.take(10).forEach { heapFile.deleteTuple(it) }

        // 재삽입 시 기존 페이지의 빈 공간을 사용해야 함
        val newRids = (0 until 10).map { heapFile.insertTuple(ByteArray(50) { it.toByte() }) }
        newRids.forEach { rid ->
            heapFile.getTuple(rid).shouldNotBeNull()
        }
    }

    @Test
    fun `여러 페이지에 걸친 scan`() {
        val (heapFile, _) = createHeapFile()
        // 각 레코드 200바이트 × 100건 ≈ 20KB → 여러 페이지 필요
        val entries = (0 until 100).map { i ->
            ByteArray(200) { (i % 128).toByte() }
        }
        val rids = entries.map { heapFile.insertTuple(it) }

        val scanned = heapFile.scan().asSequence().toList()
        scanned shouldHaveSize 100
        scanned.map { it.first }.toSet() shouldBe rids.toSet()
    }

    @Test
    fun `대량 삽입 10000건 - 전체 scan 일치`() {
        val (heapFile, _) = createHeapFile()
        val count = 10_000
        val rids = (0 until count).map { i ->
            heapFile.insertTuple("row-$i".toByteArray())
        }

        val scanned = heapFile.scan().asSequence().toList()
        scanned shouldHaveSize count
    }

    @Test
    fun `updateTuple - 같은 크기 데이터`() {
        val (heapFile, _) = createHeapFile()
        val rid = heapFile.insertTuple("old-value".toByteArray())

        val newRid = heapFile.updateTuple(rid, "new-value".toByteArray())
        heapFile.getTuple(newRid) shouldBe "new-value".toByteArray()
    }

    @Test
    fun `updateTuple - 더 큰 크기 데이터는 delete 후 insert`() {
        val (heapFile, _) = createHeapFile()
        val rid = heapFile.insertTuple("short".toByteArray())

        val newRid = heapFile.updateTuple(rid, ByteArray(200) { 42 })
        // 원래 RID의 데이터는 삭제됨
        heapFile.getTuple(rid).shouldBeNull()
        // 새 RID에서 조회 가능
        heapFile.getTuple(newRid) shouldBe ByteArray(200) { 42 }
    }

    @Test
    fun `Buffer Pool 압박 - poolSize 4로 정상 동작`() {
        val dm = DiskManager(tempDir.resolve("small_pool.db"))
        val bpm = BufferPoolManager(dm, 4)
        val heapFile = HeapFile.createNew(bpm)

        val count = 500
        val rids = (0 until count).map { i ->
            heapFile.insertTuple("row-$i".toByteArray())
        }

        rids.forEach { rid ->
            heapFile.getTuple(rid).shouldNotBeNull()
        }
    }
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 4-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.HeapFileTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (HeapFile 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 4-3: HeapFile 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/HeapFile.kt
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.Page
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 순서 없는 튜플 저장소 (Heap File).
 *
 * 헤더 페이지 1개와 데이터 페이지 N개로 구성된다.
 * Free Page List로 빈 공간이 있는 데이터 페이지를 관리한다.
 *
 * 헤더 페이지 레이아웃:
 * ```
 * offset  size  field
 * 0       4     firstFreePageId
 * 4       4     pageCount (데이터 페이지 수)
 * ```
 *
 * @param bpm BufferPoolManager
 * @param firstPageId 헤더 페이지의 pageId
 */
class HeapFile(
    private val bpm: BufferPoolManager,
    val firstPageId: Int,
) {

    companion object {
        private const val OFFSET_FIRST_FREE_PAGE_ID = 0
        private const val OFFSET_PAGE_COUNT = 4

        /**
         * 새 HeapFile을 생성한다. 헤더 페이지를 할당하고 초기화한다.
         */
        fun createNew(bpm: BufferPoolManager): HeapFile {
            val headerPage = bpm.newPage() ?: error("HeapFile 헤더 페이지 할당 실패")
            val pageId = headerPage.pageId
            try {
                val buf = headerPage.data
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putInt(OFFSET_FIRST_FREE_PAGE_ID, HeapPage.INVALID_PAGE_ID)
                buf.putInt(OFFSET_PAGE_COUNT, 0)
                headerPage.isDirty = true
            } finally {
                bpm.unpinPage(pageId, isDirty = true)
            }
            return HeapFile(bpm, pageId)
        }
    }

    /**
     * 튜플을 삽입하고 RID를 반환한다.
     */
    fun insertTuple(data: ByteArray): RID {
        // 1. Free List에서 공간 있는 페이지 찾기
        var freePageId = getFirstFreePageId()

        while (freePageId != HeapPage.INVALID_PAGE_ID) {
            val page = bpm.fetchPage(freePageId) ?: error("페이지 $freePageId 조회 실패")
            try {
                val heapPage = HeapPage(page.data)
                val slotId = heapPage.insertRecord(data)
                if (slotId >= 0) {
                    page.isDirty = true
                    return RID(freePageId, slotId)
                }
                // 이 페이지는 가득 참 → Free List에서 제거하고 다음으로
                val nextFree = heapPage.nextFreePageId
                setFirstFreePageId(nextFree)
                freePageId = nextFree
            } finally {
                bpm.unpinPage(page.pageId, isDirty = page.isDirty)
            }
        }

        // 2. Free List가 비었으면 새 페이지 할당
        return allocateAndInsert(data)
    }

    /**
     * RID로 튜플을 조회한다.
     */
    fun getTuple(rid: RID): ByteArray? {
        val page = bpm.fetchPage(rid.pageId) ?: return null
        try {
            val heapPage = HeapPage(page.data)
            return heapPage.getRecord(rid.slotId)
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = false)
        }
    }

    /**
     * RID의 튜플을 삭제한다.
     */
    fun deleteTuple(rid: RID): Boolean {
        val page = bpm.fetchPage(rid.pageId) ?: return false
        try {
            val heapPage = HeapPage(page.data)
            val deleted = heapPage.deleteRecord(rid.slotId)
            if (deleted) {
                page.isDirty = true
                // 삭제로 공간이 생겼으면 Free List에 추가
                addToFreeList(rid.pageId, heapPage)
            }
            return deleted
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = page.isDirty)
        }
    }

    /**
     * 튜플을 갱신한다. 같은 슬롯에 들어가면 제자리 갱신, 아니면 delete + insert.
     * @return 갱신된 튜플의 RID (제자리 갱신이면 기존 RID, 아니면 새 RID)
     */
    fun updateTuple(rid: RID, data: ByteArray): RID {
        val page = bpm.fetchPage(rid.pageId) ?: error("페이지 ${rid.pageId} 조회 실패")
        try {
            val heapPage = HeapPage(page.data)
            // 기존 레코드 삭제
            heapPage.deleteRecord(rid.slotId)
            page.isDirty = true
            // 같은 페이지에 재삽입 시도
            val slotId = heapPage.insertRecord(data)
            if (slotId >= 0) {
                return RID(rid.pageId, slotId)
            }
            // 공간 부족 → Free List에 추가 후 다른 페이지에 삽입
            addToFreeList(rid.pageId, heapPage)
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = page.isDirty)
        }
        return insertTuple(data)
    }

    /**
     * 전체 튜플을 순회하는 iterator를 반환한다.
     */
    fun scan(): Iterator<Pair<RID, ByteArray>> = HeapFileIterator()

    // --- Header 접근 ---

    private fun getFirstFreePageId(): Int {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            return page.data.getInt(OFFSET_FIRST_FREE_PAGE_ID)
        } finally {
            bpm.unpinPage(firstPageId, isDirty = false)
        }
    }

    private fun setFirstFreePageId(pageId: Int) {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            page.data.putInt(OFFSET_FIRST_FREE_PAGE_ID, pageId)
            page.isDirty = true
        } finally {
            bpm.unpinPage(firstPageId, isDirty = true)
        }
    }

    private fun getPageCount(): Int {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            return page.data.getInt(OFFSET_PAGE_COUNT)
        } finally {
            bpm.unpinPage(firstPageId, isDirty = false)
        }
    }

    private fun incrementPageCount() {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            val current = page.data.getInt(OFFSET_PAGE_COUNT)
            page.data.putInt(OFFSET_PAGE_COUNT, current + 1)
            page.isDirty = true
        } finally {
            bpm.unpinPage(firstPageId, isDirty = true)
        }
    }

    /** 데이터 페이지 pageId 목록을 수집한다 (scan용). */
    private fun collectDataPageIds(): List<Int> {
        val result = mutableListOf<Int>()
        // 헤더 페이지 다음부터 pageCount개의 데이터 페이지가 연속 할당됨
        val count = getPageCount()
        val headerPage = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        bpm.unpinPage(firstPageId, isDirty = false)
        // 데이터 페이지는 firstPageId + 1부터 순차적으로 할당된다고 가정하지 않음.
        // 대신 dataPageIds를 따로 관리: 단순화를 위해 연속 할당 가정.
        // (HeapFile.createNew에서 firstPageId 다음에 연속으로 할당)
        for (i in 1..count) {
            result.add(firstPageId + i)
        }
        return result
    }

    // --- Private helpers ---

    private fun allocateAndInsert(data: ByteArray): RID {
        val newPage = bpm.newPage() ?: error("데이터 페이지 할당 실패")
        val newPageId = newPage.pageId
        try {
            val heapPage = HeapPage(newPage.data)
            heapPage.init()
            val slotId = heapPage.insertRecord(data)
            check(slotId >= 0) { "새 페이지에 레코드 삽입 실패: 레코드가 페이지 크기를 초과" }
            newPage.isDirty = true

            // Free List에 추가 (아직 공간이 남아있을 수 있으므로)
            addToFreeList(newPageId, heapPage)

            incrementPageCount()
            return RID(newPageId, slotId)
        } finally {
            bpm.unpinPage(newPageId, isDirty = newPage.isDirty)
        }
    }

    private fun addToFreeList(dataPageId: Int, heapPage: HeapPage) {
        val currentFirst = getFirstFreePageId()
        if (dataPageId == currentFirst) return  // 이미 리스트에 있음
        // 간단한 중복 체크: 리스트 순회는 비용이 크므로, nextFreePageId가 INVALID가 아니면
        // 이미 리스트에 있다고 간주 (신규 추가 or 삭제 직후만 호출하므로 안전)
        if (heapPage.nextFreePageId != HeapPage.INVALID_PAGE_ID && dataPageId != currentFirst) return
        heapPage.nextFreePageId = currentFirst
        setFirstFreePageId(dataPageId)
    }

    private inner class HeapFileIterator : Iterator<Pair<RID, ByteArray>> {
        private val dataPageIds = collectDataPageIds()
        private var pageIndex = 0
        private var currentRecords: List<Pair<Int, ByteArray>> = emptyList()
        private var recordIndex = 0
        private var currentDataPageId = -1

        init {
            advanceToNextNonEmptyPage()
        }

        override fun hasNext(): Boolean = recordIndex < currentRecords.size

        override fun next(): Pair<RID, ByteArray> {
            if (!hasNext()) throw NoSuchElementException()
            val (slotId, data) = currentRecords[recordIndex]
            val rid = RID(currentDataPageId, slotId)
            recordIndex++
            if (recordIndex >= currentRecords.size) {
                advanceToNextNonEmptyPage()
            }
            return rid to data
        }

        private fun advanceToNextNonEmptyPage() {
            while (pageIndex < dataPageIds.size) {
                val pageId = dataPageIds[pageIndex]
                pageIndex++
                val page = bpm.fetchPage(pageId) ?: continue
                try {
                    val heapPage = HeapPage(page.data)
                    val records = heapPage.allRecords()
                    if (records.isNotEmpty()) {
                        currentRecords = records
                        recordIndex = 0
                        currentDataPageId = pageId
                        return
                    }
                } finally {
                    bpm.unpinPage(pageId, isDirty = false)
                }
            }
            currentRecords = emptyList()
            recordIndex = 0
        }
    }
}
```

- [ ] HeapFile.kt를 생성한다.

### Step 4-4: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.HeapFileTest"`

Expected: 9 tests passed

Run: `./gradlew :core:test`

Expected: 전체 통과

- [ ] HeapFileTest 통과 및 전체 테스트 미파손 확인.

### Step 4-5: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/HeapFile.kt \
       core/src/test/kotlin/gwanbase/table/HeapFileTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] HeapFile 구현 (Free Page List 기반 튜플 저장)
EOF
)"
```

- [ ] 커밋한다.

---

## Task 5: Catalog — 테이블 메타데이터 영속 저장

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/Catalog.kt`
- Create: `core/src/test/kotlin/gwanbase/table/CatalogTest.kt`

**배경:** Catalog는 전용 페이지에 테이블 목록을 바이너리로 직렬화한다. 변경 시 전체를 다시 쓴다.

### Step 5-1: 실패하는 테스트 작성

```kotlin
// core/src/test/kotlin/gwanbase/table/CatalogTest.kt
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CatalogTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createCatalog(): Triple<Catalog, BufferPoolManager, DiskManager> {
        val dm = DiskManager(tempDir.resolve("catalog_test.db"))
        val bpm = BufferPoolManager(dm, 64)
        val catalog = Catalog.createNew(bpm)
        return Triple(catalog, bpm, dm)
    }

    private val userSchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("name", DataType.VARCHAR, maxLength = 100),
            Column("email", DataType.VARCHAR, maxLength = 200, nullable = true),
            Column("active", DataType.BOOLEAN),
        )
    )

    @Test
    fun `createTable 후 getTable로 조회`() {
        val (catalog, _, _) = createCatalog()
        val info = catalog.createTable("users", userSchema)

        info.name shouldBe "users"
        info.schema.columnCount shouldBe 4
        info.schema.column(0).name shouldBe "id"
        info.schema.column(1).type shouldBe DataType.VARCHAR
        info.schema.column(2).nullable shouldBe true

        val retrieved = catalog.getTable("users")
        retrieved.shouldNotBeNull()
        retrieved.tableId shouldBe info.tableId
        retrieved.name shouldBe "users"
    }

    @Test
    fun `중복 테이블 이름 생성 시 예외`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)

        assertThrows<IllegalArgumentException> {
            catalog.createTable("users", userSchema)
        }
    }

    @Test
    fun `listTables - 빈 상태`() {
        val (catalog, _, _) = createCatalog()
        catalog.listTables() shouldHaveSize 0
    }

    @Test
    fun `listTables - 여러 테이블`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)
        catalog.createTable("posts", Schema(listOf(Column("id", DataType.INT32))))

        catalog.listTables() shouldHaveSize 2
    }

    @Test
    fun `dropTable 후 getTable은 null`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)

        catalog.dropTable("users") shouldBe true
        catalog.getTable("users").shouldBeNull()
    }

    @Test
    fun `dropTable - 존재하지 않는 이름`() {
        val (catalog, _, _) = createCatalog()
        catalog.dropTable("nonexistent") shouldBe false
    }

    @Test
    fun `tableId 자동 증가`() {
        val (catalog, _, _) = createCatalog()
        val t1 = catalog.createTable("t1", Schema(listOf(Column("id", DataType.INT32))))
        val t2 = catalog.createTable("t2", Schema(listOf(Column("id", DataType.INT32))))

        t2.tableId shouldBe t1.tableId + 1
    }

    @Test
    fun `영속성 - close 후 reopen`() {
        val dbPath = tempDir.resolve("persist_test.db")

        // 1. 생성 후 닫기
        run {
            val dm = DiskManager(dbPath)
            val bpm = BufferPoolManager(dm, 64)
            val catalog = Catalog.createNew(bpm)
            catalog.createTable("users", userSchema)
            bpm.flushAllPages()
            dm.close()
        }

        // 2. 다시 열기
        run {
            val dm = DiskManager(dbPath)
            val bpm = BufferPoolManager(dm, 64)
            val catalog = Catalog.load(bpm, catalogPageId = 0)

            val info = catalog.getTable("users")
            info.shouldNotBeNull()
            info.name shouldBe "users"
            info.schema.columnCount shouldBe 4
            info.schema.column(1).name shouldBe "name"
            info.schema.column(1).type shouldBe DataType.VARCHAR
            info.schema.column(1).maxLength shouldBe 100
            info.schema.column(2).nullable shouldBe true
            dm.close()
        }
    }

    @Test
    fun `getTable by tableId`() {
        val (catalog, _, _) = createCatalog()
        val info = catalog.createTable("users", userSchema)

        val byId = catalog.getTable(info.tableId)
        byId.shouldNotBeNull()
        byId.name shouldBe "users"
    }
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 5-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (Catalog, TableInfo 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 5-3: Catalog 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/Catalog.kt
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테이블 메타데이터.
 */
data class TableInfo(
    val tableId: Int,
    val name: String,
    val schema: Schema,
    val heapFileFirstPageId: Int,
)

/**
 * 테이블/인덱스 메타데이터를 전용 페이지에 영속 저장한다.
 *
 * Catalog 페이지 직렬화 포맷:
 * ```
 * [nextTableId: Int]
 * [tableCount: Int]
 * 반복 {
 *   [tableId: Int]
 *   [nameLength: Short][name: UTF-8 bytes]
 *   [heapFileFirstPageId: Int]
 *   [columnCount: Short]
 *   반복 {
 *     [colNameLength: Short][colName: UTF-8 bytes]
 *     [dataType: Byte]
 *     [maxLength: Short]
 *     [nullable: Byte]
 *   }
 * }
 * ```
 */
class Catalog(
    private val bpm: BufferPoolManager,
    private val catalogPageId: Int,
) {
    private val tables = mutableListOf<TableInfo>()
    private var nextTableId: Int = 1

    companion object {
        /**
         * 새 Catalog를 생성한다. 전용 페이지를 할당하고 빈 상태로 초기화한다.
         */
        fun createNew(bpm: BufferPoolManager): Catalog {
            val page = bpm.newPage() ?: error("Catalog 페이지 할당 실패")
            val pageId = page.pageId
            bpm.unpinPage(pageId, isDirty = false)
            val catalog = Catalog(bpm, pageId)
            catalog.flush()
            return catalog
        }

        /**
         * 기존 Catalog를 로드한다.
         */
        fun load(bpm: BufferPoolManager, catalogPageId: Int): Catalog {
            val catalog = Catalog(bpm, catalogPageId)
            catalog.loadFromPage()
            return catalog
        }
    }

    /**
     * 테이블을 생성한다.
     */
    fun createTable(name: String, schema: Schema): TableInfo {
        require(tables.none { it.name == name }) { "테이블 '$name'이 이미 존재한다" }

        // HeapFile 생성
        val heapFile = HeapFile.createNew(bpm)
        val info = TableInfo(nextTableId++, name, schema, heapFile.firstPageId)
        tables.add(info)
        flush()
        return info
    }

    /** 이름으로 테이블 조회 */
    fun getTable(name: String): TableInfo? = tables.find { it.name == name }

    /** tableId로 테이블 조회 */
    fun getTable(tableId: Int): TableInfo? = tables.find { it.tableId == tableId }

    /** 모든 테이블 목록 */
    fun listTables(): List<TableInfo> = tables.toList()

    /**
     * 테이블을 삭제한다.
     */
    fun dropTable(name: String): Boolean {
        val removed = tables.removeAll { it.name == name }
        if (removed) flush()
        return removed
    }

    // --- 직렬화/역직렬화 ---

    private fun flush() {
        val page = bpm.fetchPage(catalogPageId) ?: error("Catalog 페이지 조회 실패")
        try {
            val buf = page.data
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.position(0)

            buf.putInt(nextTableId)
            buf.putInt(tables.size)

            for (info in tables) {
                buf.putInt(info.tableId)

                val nameBytes = info.name.toByteArray(Charsets.UTF_8)
                buf.putShort(nameBytes.size.toShort())
                buf.put(nameBytes)

                buf.putInt(info.heapFileFirstPageId)
                buf.putShort(info.schema.columnCount.toShort())

                for (col in info.schema.columns) {
                    val colNameBytes = col.name.toByteArray(Charsets.UTF_8)
                    buf.putShort(colNameBytes.size.toShort())
                    buf.put(colNameBytes)
                    buf.put(col.type.ordinal.toByte())
                    buf.putShort(col.maxLength.toShort())
                    buf.put(if (col.nullable) 1.toByte() else 0.toByte())
                }
            }

            page.isDirty = true
        } finally {
            bpm.unpinPage(catalogPageId, isDirty = true)
        }
    }

    private fun loadFromPage() {
        val page = bpm.fetchPage(catalogPageId) ?: error("Catalog 페이지 조회 실패")
        try {
            val buf = page.data
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.position(0)

            nextTableId = buf.getInt()
            val tableCount = buf.getInt()

            tables.clear()
            repeat(tableCount) {
                val tableId = buf.getInt()

                val nameLen = buf.getShort().toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)

                val heapFileFirstPageId = buf.getInt()
                val columnCount = buf.getShort().toInt() and 0xFFFF

                val columns = (0 until columnCount).map {
                    val colNameLen = buf.getShort().toInt() and 0xFFFF
                    val colNameBytes = ByteArray(colNameLen)
                    buf.get(colNameBytes)
                    val colName = String(colNameBytes, Charsets.UTF_8)
                    val dataType = DataType.entries[buf.get().toInt() and 0xFF]
                    val maxLength = buf.getShort().toInt() and 0xFFFF
                    val nullable = buf.get() != 0.toByte()
                    Column(colName, dataType, maxLength, nullable)
                }

                tables.add(TableInfo(tableId, name, Schema(columns), heapFileFirstPageId))
            }
        } finally {
            bpm.unpinPage(catalogPageId, isDirty = false)
        }
    }
}
```

- [ ] Catalog.kt를 생성한다.

### Step 5-4: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogTest"`

Expected: 9 tests passed

Run: `./gradlew :core:test`

Expected: 전체 통과

- [ ] CatalogTest 통과 및 전체 테스트 미파손 확인.

### Step 5-5: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/Catalog.kt \
       core/src/test/kotlin/gwanbase/table/CatalogTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] Catalog 구현 (테이블 메타데이터 영속 직렬화)
EOF
)"
```

- [ ] 커밋한다.

---

## Task 6: Database — 진입점 + 통합 테스트

**Files:**
- Create: `core/src/main/kotlin/gwanbase/table/Database.kt`
- Create: `core/src/test/kotlin/gwanbase/table/DatabaseTest.kt`

### Step 6-1: 실패하는 테스트 작성

```kotlin
// core/src/test/kotlin/gwanbase/table/DatabaseTest.kt
package gwanbase.table

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): Path = tempDir.resolve("test.db")

    private val userSchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("name", DataType.VARCHAR, maxLength = 100),
            Column("active", DataType.BOOLEAN),
        )
    )

    @Test
    fun `기본 CRUD - createTable, insertTuple, getTuple, deleteTuple`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)

            val tuple = Tuple(userSchema, arrayOf(1, "Alice", true))
            val rid = db.insertTuple("users", tuple)

            val retrieved = db.getTuple("users", rid)
            retrieved.shouldNotBeNull()
            retrieved.getInt(0) shouldBe 1
            retrieved.getString(1) shouldBe "Alice"
            retrieved.getBoolean(2) shouldBe true

            db.deleteTuple("users", rid) shouldBe true
            db.getTuple("users", rid).shouldBeNull()
        }
    }

    @Test
    fun `영속성 - close 후 reopen`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)
            db.insertTuple("users", Tuple(userSchema, arrayOf(1, "Alice", true)))
            db.insertTuple("users", Tuple(userSchema, arrayOf(2, "Bob", false)))
        }

        Database.open(dbPath()).use { db ->
            val results = db.scanTable("users").asSequence().toList()
            results shouldHaveSize 2

            val names = results.map { it.second.getString(1) }.toSet()
            names shouldBe setOf("Alice", "Bob")
        }
    }

    @Test
    fun `여러 테이블 독립 동작`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)

            val postSchema = Schema(
                listOf(
                    Column("id", DataType.INT32),
                    Column("title", DataType.VARCHAR, maxLength = 200),
                )
            )
            db.createTable("posts", postSchema)

            db.insertTuple("users", Tuple(userSchema, arrayOf(1, "Alice", true)))
            db.insertTuple("posts", Tuple(postSchema, arrayOf(1, "Hello")))

            db.scanTable("users").asSequence().toList() shouldHaveSize 1
            db.scanTable("posts").asSequence().toList() shouldHaveSize 1
        }
    }

    @Test
    fun `대량 삽입 10000건`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)

            repeat(10_000) { i ->
                db.insertTuple("users", Tuple(userSchema, arrayOf(i, "user-$i", i % 2 == 0)))
            }

            val count = db.scanTable("users").asSequence().count()
            count shouldBe 10_000
        }
    }

    @Test
    fun `Buffer Pool 압박 - poolSize 4`() {
        Database.open(dbPath(), bufferPoolSize = 4).use { db ->
            db.createTable("users", userSchema)

            val rids = (0 until 500).map { i ->
                db.insertTuple("users", Tuple(userSchema, arrayOf(i, "user-$i", true)))
            }

            rids.forEach { rid ->
                db.getTuple("users", rid).shouldNotBeNull()
            }
        }
    }

    @Test
    fun `close 후 연산 시 예외`() {
        val db = Database.open(dbPath())
        db.createTable("users", userSchema)
        db.close()

        assertThrows<IllegalStateException> {
            db.insertTuple("users", Tuple(userSchema, arrayOf(1, "Alice", true)))
        }
    }

    @Test
    fun `존재하지 않는 테이블에 삽입 시 예외`() {
        Database.open(dbPath()).use { db ->
            assertThrows<IllegalArgumentException> {
                db.insertTuple("nonexistent", Tuple(userSchema, arrayOf(1, "Alice", true)))
            }
        }
    }
}
```

- [ ] 위 테스트 파일을 생성한다.

### Step 6-2: 테스트 실패 확인

Run: `./gradlew :core:test --tests "gwanbase.table.DatabaseTest" --info 2>&1 | tail -5`

Expected: 컴파일 에러 (Database 클래스 미존재)

- [ ] 테스트가 실패하는 것을 확인한다.

### Step 6-3: Database 구현

```kotlin
// core/src/main/kotlin/gwanbase/table/Database.kt
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Phase 2 데이터베이스 진입점.
 *
 * 단일 DB 파일을 관리하며 테이블 생성, 튜플 CRUD, 스캔 기능을 제공한다.
 *
 * 파일 레이아웃:
 * ```
 * pageId 0    DB 메타데이터 (magic, version, catalogPageId, nextTableId)
 * pageId 1    Catalog 페이지
 * pageId 2+   HeapFile 페이지들
 * ```
 */
class Database private constructor(
    private val diskManager: DiskManager,
    private val bpm: BufferPoolManager,
    private val catalog: Catalog,
) : AutoCloseable {

    private var closed = false

    companion object {
        const val METADATA_PAGE_ID = 0
        const val MAGIC = 0x47574E42  // "GWNB"
        const val VERSION: Short = 2  // Phase 2

        private const val OFFSET_MAGIC = 0
        private const val OFFSET_VERSION = 4
        private const val OFFSET_CATALOG_PAGE_ID = 6

        /**
         * DB 파일을 열거나 새로 생성한다.
         */
        fun open(path: Path, bufferPoolSize: Int = 256): Database {
            val dm = DiskManager(path)
            val bpm = BufferPoolManager(dm, bufferPoolSize)

            val catalog = if (dm.pageCount == 0) {
                createFresh(bpm)
            } else {
                loadExisting(bpm)
            }

            return Database(dm, bpm, catalog)
        }

        private fun createFresh(bpm: BufferPoolManager): Catalog {
            // pageId 0: 메타데이터 페이지
            val metaPage = bpm.newPage() ?: error("메타데이터 페이지 할당 실패")
            check(metaPage.pageId == METADATA_PAGE_ID)
            bpm.unpinPage(METADATA_PAGE_ID, isDirty = false)

            // pageId 1: Catalog 페이지
            val catalog = Catalog.createNew(bpm)

            // 메타데이터 기록
            writeMetadata(bpm, catalog.catalogPageId)
            return catalog
        }

        private fun loadExisting(bpm: BufferPoolManager): Catalog {
            val page = bpm.fetchPage(METADATA_PAGE_ID) ?: error("메타데이터 페이지 조회 실패")
            val catalogPageId: Int
            try {
                val buf = page.data
                buf.order(ByteOrder.BIG_ENDIAN)
                val magic = buf.getInt(OFFSET_MAGIC)
                check(magic == MAGIC) {
                    "DB 파일 식별자 불일치: expected ${MAGIC.toString(16)}, got ${magic.toString(16)}"
                }
                catalogPageId = buf.getInt(OFFSET_CATALOG_PAGE_ID)
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = false)
            }
            return Catalog.load(bpm, catalogPageId)
        }

        private fun writeMetadata(bpm: BufferPoolManager, catalogPageId: Int) {
            val page = bpm.fetchPage(METADATA_PAGE_ID) ?: error("메타데이터 페이지 조회 실패")
            try {
                val buf = page.data
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putInt(OFFSET_MAGIC, MAGIC)
                buf.putShort(OFFSET_VERSION, VERSION)
                buf.putInt(OFFSET_CATALOG_PAGE_ID, catalogPageId)
                page.isDirty = true
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = true)
            }
        }
    }

    fun createTable(name: String, schema: Schema): TableInfo {
        checkOpen()
        return catalog.createTable(name, schema)
    }

    fun getTable(name: String): TableInfo? {
        checkOpen()
        return catalog.getTable(name)
    }

    fun insertTuple(tableName: String, tuple: Tuple): RID {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        return heapFile.insertTuple(tuple.serialize())
    }

    fun getTuple(tableName: String, rid: RID): Tuple? {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        val data = heapFile.getTuple(rid) ?: return null
        return Tuple.deserialize(info.schema, data)
    }

    fun deleteTuple(tableName: String, rid: RID): Boolean {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        return heapFile.deleteTuple(rid)
    }

    fun scanTable(tableName: String): Iterator<Pair<RID, Tuple>> {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        val rawIter = heapFile.scan()
        return object : Iterator<Pair<RID, Tuple>> {
            override fun hasNext() = rawIter.hasNext()
            override fun next(): Pair<RID, Tuple> {
                val (rid, data) = rawIter.next()
                return rid to Tuple.deserialize(info.schema, data)
            }
        }
    }

    override fun close() {
        if (closed) return
        bpm.flushAllPages()
        diskManager.close()
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "Database is closed" }
    }
}
```

Catalog 클래스에 `catalogPageId` 프로퍼티를 외부에서 접근할 수 있도록 수정이 필요하다:

```kotlin
// Catalog.kt에서 catalogPageId를 val로 노출:
// 기존: private val catalogPageId: Int,
// 변경: val catalogPageId: Int,
```

- [ ] Database.kt를 생성하고, Catalog.kt의 `catalogPageId`를 `val`로 변경한다.

### Step 6-4: 테스트 통과 확인

Run: `./gradlew :core:test --tests "gwanbase.table.DatabaseTest"`

Expected: 7 tests passed

Run: `./gradlew :core:test`

Expected: 전체 통과

- [ ] DatabaseTest 통과 및 전체 테스트 미파손 확인.

### Step 6-5: 커밋

```bash
git add core/src/main/kotlin/gwanbase/table/Database.kt \
       core/src/main/kotlin/gwanbase/table/Catalog.kt \
       core/src/test/kotlin/gwanbase/table/DatabaseTest.kt
git commit -m "$(cat <<'EOF'
[Phase 2] Database 진입점 구현 및 통합 테스트
EOF
)"
```

- [ ] 커밋한다.

---

## Task 7: 문서 업데이트 및 Phase 2 마무리

**Files:**
- Modify: `docs/specs/phase-2-table-storage.md` — 구현 과정에서 변경된 세부사항 반영
- Modify: `docs/ARCHITECTURE.md` — Phase 2 완료 상태 반영
- Modify: `CLAUDE.md` — Phase 2 완료 상태 갱신

### Step 7-1: ARCHITECTURE.md 업데이트

`docs/ARCHITECTURE.md`의 모듈 구조에서 `table/`을 ✅로 변경하고, Phase 2 완료 상태 섹션을 추가한다:

```markdown
## 모듈 구조

core/
├── storage/     Phase 1 ✅ DiskManager, BufferPool, SlottedPage
├── index/       Phase 1 ✅ B+Tree (BPlusTreeNode, BPlusTree)
├── kv/          Phase 1 ✅ KVStore (public Key-Value API)
├── table/       Phase 2 ✅ Schema, Tuple, HeapFile, Catalog, Database
...
```

Phase 2 완료 상태 섹션을 `Phase 1 완료 상태` 뒤에 추가:

```markdown
## Phase 2 완료 상태 (v0.2-table)

스토리지 레이어 위에 관계형 테이블 저장소 구축.

### 계층 구조

Database (gwanbase.table)        ← 진입점: open/close, 테이블 CRUD
  ├── Catalog                    ← 메타데이터 영속 (전용 페이지)
  └── HeapFile                   ← 튜플 저장 (Free Page List)
        └── HeapPage
              └── SlottedPage (gwanbase.storage)
                    └── BufferPoolManager → DiskManager

### 핵심 설계 결정
- 단일 파일 레이아웃 (향후 PostgreSQL 방식 파일 분리 예정)
- RID (pageId, slotId) 기반 튜플 식별
- Free Page List로 빈 공간 관리
- Catalog 전용 페이지에 바이너리 직렬화
- Tuple은 null bitmap + 스키마 순서 직렬화
```

- [ ] ARCHITECTURE.md를 업데이트한다.

### Step 7-2: CLAUDE.md 업데이트

`CLAUDE.md`의 Phase 2 섹션을 완료 상태로 변경하고, Phase 2 컴포넌트 테이블을 추가한다.

- [ ] CLAUDE.md를 업데이트한다.

### Step 7-3: 커밋

```bash
git add docs/ARCHITECTURE.md CLAUDE.md docs/specs/phase-2-table-storage.md
git commit -m "$(cat <<'EOF'
[Phase 2] 완료 상태로 문서 갱신
EOF
)"
```

- [ ] 커밋한다.

### Step 7-4: 태그

```bash
git tag v0.2-table
```

- [ ] 태그를 생성한다.
