# Phase 2: Table Storage Engine

## 목표

관계형 데이터베이스의 핵심인 **테이블** 개념을 도입한다. Schema로 컬럼을 정의하고,
Tuple로 행을 직렬화하며, HeapFile로 순서 없는 레코드를 저장하고, Catalog로
메타데이터를 영속 관리한다. Phase 1의 storage 레이어(DiskManager, BufferPoolManager,
SlottedPage) 위에 구축한다.

## 비기능 요구사항

- 프로세스 재시작 후 테이블 스키마와 데이터 영속성 보장
- 단일 테이블 10만 건 삽입/전체 스캔이 정상 동작
- Buffer Pool 크기가 작아도 정상 동작 (성능 저하는 허용)
- Phase 1의 KVStore/B+Tree 코드에 영향을 주지 않는 독립 구현

## 인터페이스 설계 (public API)

### DataType — 지원 타입

```kotlin
enum class DataType(val fixedSize: Int?) {
    BOOLEAN(1),
    INT32(4),
    INT64(8),
    FLOAT64(8),
    TIMESTAMP(8),
    VARCHAR(null);  // 가변 길이, 최대 바이트 수는 Column에서 지정

    val isFixedSize: Boolean get() = fixedSize != null
}
```

### Column — 컬럼 정의

```kotlin
data class Column(
    val name: String,
    val type: DataType,
    val maxLength: Int = 0,   // VARCHAR 전용: 최대 바이트 수
    val nullable: Boolean = false,
)
```

### Schema — 테이블 스키마

```kotlin
class Schema(val columns: List<Column>) {
    fun columnIndex(name: String): Int
    fun column(index: Int): Column
    val columnCount: Int
}
```

### Tuple — 행 데이터

```kotlin
class Tuple(val schema: Schema, private val values: Array<Any?>) {
    fun getBoolean(index: Int): Boolean?
    fun getInt(index: Int): Int?
    fun getLong(index: Int): Long?
    fun getDouble(index: Int): Double?
    fun getString(index: Int): String?
    fun getTimestamp(index: Int): Long?
    fun isNull(index: Int): Boolean

    fun serialize(): ByteArray
    companion object {
        fun deserialize(schema: Schema, data: ByteArray): Tuple
    }
}
```

### RID — 튜플 물리 위치

```kotlin
data class RID(val pageId: Int, val slotId: Int)
```

### HeapFile — 순서 없는 튜플 저장

```kotlin
class HeapFile(
    private val bpm: BufferPoolManager,
    val firstPageId: Int,
) {
    fun insertTuple(data: ByteArray): RID
    fun getTuple(rid: RID): ByteArray?
    fun deleteTuple(rid: RID): Boolean
    fun updateTuple(rid: RID, data: ByteArray): RID
    fun scan(): Iterator<Pair<RID, ByteArray>>

    companion object {
        fun createNew(bpm: BufferPoolManager): HeapFile
    }
}
```

### Catalog — 메타데이터 관리

```kotlin
data class TableInfo(
    val tableId: Int,
    val name: String,
    val schema: Schema,
    val heapFileFirstPageId: Int,
)

class Catalog(private val bpm: BufferPoolManager) {
    fun createTable(name: String, schema: Schema): TableInfo
    fun getTable(name: String): TableInfo?
    fun getTable(tableId: Int): TableInfo?
    fun listTables(): List<TableInfo>
    fun dropTable(name: String): Boolean
}
```

### Database — 진입점

```kotlin
class Database private constructor(...) : AutoCloseable {
    fun createTable(name: String, schema: Schema): TableInfo
    fun getTable(name: String): TableInfo?
    fun insertTuple(tableName: String, tuple: Tuple): RID
    fun getTuple(tableName: String, rid: RID): Tuple?
    fun deleteTuple(tableName: String, rid: RID): Boolean
    fun scanTable(tableName: String): Iterator<Pair<RID, Tuple>>

    companion object {
        fun open(path: Path, bufferPoolSize: Int = 256): Database
    }
}
```

## 내부 설계

### 컴포넌트 의존 관계

```
Database (gwanbase.table)        ← 진입점: open/close, 테이블 CRUD
  ├── Catalog                    ← 메타데이터 영속 (전용 페이지)
  └── TableInfo
        ├── Schema + Column      ← 컬럼 정의
        └── HeapFile             ← 튜플 저장 (Free Page List)
              └── SlottedPage (gwanbase.storage)
                    └── BufferPoolManager
                          └── DiskManager
```

`gwanbase.table`은 `gwanbase.storage`에만 의존한다.
`gwanbase.index`, `gwanbase.kv`는 참조하지 않는다.

### 단일 파일 레이아웃

```
pageId 0     DB 메타데이터 페이지
               offset  size  field
               0       4     magic (0x47574E42 = "GWNB")
               4       2     version (2)
               6       4     catalogPageId (보통 1)

pageId 1     Catalog 페이지
               테이블 목록을 바이너리 직렬화

pageId 2..   HeapFile 헤더/데이터 페이지들 (동적 할당)
```

### Tuple 직렬화 레이아웃

```
[null bitmap: ceil(columnCount / 8) bytes]
[고정 크기 필드들: 스키마 순서대로, NULL이면 0으로 채움]
[가변 크기 필드들: (length: Short)(data: bytes), NULL이면 length=0]
```

- Null bitmap: i번째 비트가 1이면 i번째 컬럼이 NULL
- 고정 크기 필드: BOOLEAN(1), INT32(4), INT64(8), FLOAT64(8), TIMESTAMP(8)
- 가변 크기 필드: VARCHAR — `(length: Short)(UTF-8 bytes)`
- NULL인 고정 크기 필드는 0으로 채워 고정 오프셋을 유지한다
- NULL인 가변 크기 필드는 `length=0`으로 기록한다
- ByteOrder: BIG_ENDIAN (Phase 1과 일관)

> **설계 결정: 고정/가변 분리 대신 스키마 순서 직렬화**
> PostgreSQL은 고정 크기 필드를 앞에 모으고 가변 크기를 뒤에 배치하여
> offset 계산을 최적화한다. 여기서는 학습 목적으로 스키마 순서 그대로
> 직렬화한다. 고정 크기 필드의 오프셋은 null bitmap + 이전 고정 크기
> 필드들의 합으로 계산 가능하다. 가변 크기 필드는 순차 파싱이 필요하지만,
> 4KB 페이지 내 데이터이므로 성능 영향은 무시할 수 있다.

### HeapFile 내부 구조

#### 헤더 페이지

HeapFile 생성 시 헤더 페이지 1개를 할당한다. 헤더 페이지 레이아웃:

```
offset  size  field
0       4     firstFreePageId  (빈 공간 있는 첫 데이터 페이지, 없으면 -1)
4       4     dataPageCount    (데이터 페이지 수)
8       4×N   dataPageIds[]    (데이터 페이지 ID 배열, 최대 ~1022개)
```

#### 데이터 페이지

각 데이터 페이지는 SlottedPage를 감싸되, 페이지 앞부분에 HeapFile 전용
메타데이터를 추가한다:

```
offset  size  field
0       4     nextFreePageId   (Free List의 다음 페이지, 없으면 -1)
4       ...   SlottedPage 영역 (slotCount, freeSpaceOffset, slots, records)
```

> **설계 결정: SlottedPage 재사용 방식**
> SlottedPage는 ByteBuffer의 offset 0부터 헤더를 시작한다. HeapFile
> 데이터 페이지에서는 앞 4바이트를 nextFreePageId로 예약하고,
> SlottedPage를 offset 4부터 시작하는 별도 ByteBuffer slice로 감싼다.
> 이렇게 하면 SlottedPage 코드를 수정하지 않고 재사용할 수 있다.

#### Free Page List 동작

- **삽입**: HeapFile 헤더의 `firstFreePageId`부터 시작하여 공간이 있는
  페이지를 찾아 삽입. 공간이 없으면 새 페이지를 할당하고 Free List에 추가.
- **삭제**: 튜플 삭제 후 해당 페이지에 공간이 생기면 Free List에 추가
  (이미 리스트에 있으면 무시).
- **페이지 가득 참**: 페이지가 가득 차면 Free List에서 제거.

### Catalog 직렬화

Catalog 페이지에 테이블 목록을 바이너리로 직렬화한다:

```
[tableCount: Int]
반복 {
  [tableId: Int]
  [nameLength: Short][name: UTF-8 bytes]
  [heapFileFirstPageId: Int]
  [columnCount: Short]
  반복 {
    [colNameLength: Short][colName: UTF-8 bytes]
    [dataType: Byte]  (enum ordinal)
    [maxLength: Short] (VARCHAR 전용, 나머지 타입은 0)
    [nullable: Byte]  (0 or 1)
  }
}
```

Catalog 변경 시 전체를 다시 직렬화하여 페이지에 덮어쓴다.
테이블 수가 적은 학습 프로젝트이므로 단일 페이지로 충분하다.

### Database 초기화 흐름

```
Database.open(path)
  ├── 파일이 비어 있음 → createFreshDatabase()
  │     ├── pageId 0: DB 메타데이터 페이지 초기화
  │     ├── pageId 1: 빈 Catalog 페이지 할당
  │     └── 메타데이터에 catalogPageId=1, nextTableId=1 기록
  └── 파일이 존재함 → loadExistingDatabase()
        ├── pageId 0에서 magic/version 확인
        ├── catalogPageId 로드
        └── Catalog 역직렬화
```

## 제약 사항 및 트레이드오프

| 결정 | 선택 | 대안 | 이유 |
|---|---|---|---|
| 튜플 식별 | RID (pageId, slotId) | 자동 증가 rowId | PostgreSQL 방식, SlottedPage 활용, 직접 물리 위치 참조 |
| 빈 공간 관리 | Free Page List | FSM, 순차 탐색 | 구현 단순 + 공간 재활용 가능, FSM으로 업그레이드 가능 |
| Catalog 저장 | 전용 페이지 직렬화 | self-bootstrapping, KVStore 활용 | 직관적, Phase 1 메타데이터 패턴 확장 |
| 파일 레이아웃 | 단일 파일 | 테이블/인덱스별 파일 분리 | Phase 1 자산 재사용, 향후 PostgreSQL 방식으로 고도화 예정 |
| Phase 1 관계 | 독립 구축, KVStore 보존 | KVStore 위에 테이블 구축 | HeapFile 학습이 핵심 목표 |
| Tuple 직렬화 | 스키마 순서 그대로 | 고정/가변 분리 | 학습 목적, 4KB 내 성능 차이 무시 가능 |
| 인덱스 연결 | Phase 2에서 미포함 | HeapFile + B+Tree 즉시 연결 | 단계별 구현, Phase 2는 저장만 담당 |
| 동시성 | 단일 스레드 가정 | Fine-grained latch | Phase 6에서 추가 |
| Crash 일관성 | 미보장 | WAL | Phase 5에서 추가 |

## 테스트 시나리오

### DataType / Column / Schema

1. 각 DataType의 fixedSize 값 정확성
2. DataType.isFixedSize가 VARCHAR만 false
3. Column 생성 — nullable 기본값 false
4. VARCHAR Column — maxLength 지정 필수
5. Schema.columnIndex(name)로 올바른 인덱스 반환
6. Schema.columnIndex 존재하지 않는 이름 → 예외
7. Schema.columnCount 정확성

### Tuple 직렬화/역직렬화

1. 고정 크기 타입만으로 구성된 Tuple — serialize → deserialize → 값 일치
2. VARCHAR 포함 Tuple — serialize → deserialize → 값 일치
3. NULL 포함 Tuple — null bitmap 정확성, isNull() 반환값
4. 모든 필드가 NULL인 Tuple
5. 빈 문자열 VARCHAR
6. 최대 길이 VARCHAR
7. 모든 타입 혼합 Tuple — 각 getter 반환값 정확성
8. non-nullable 컬럼에 NULL 삽입 시 예외
9. **Kotest property-based**: 랜덤 스키마 + 랜덤 값 → serialize → deserialize → 값 일치

### HeapFile

1. 빈 HeapFile 생성 → scan 결과 없음
2. 단일 삽입 → getTuple(rid) 일치
3. 여러 건 삽입 → 모든 getTuple 일치
4. 삭제 → getTuple(rid) null 반환
5. 삭제 후 재삽입 → 공간 재활용 확인 (Free List 동작)
6. 페이지 가득 찰 때까지 삽입 → 새 페이지 자동 할당
7. 여러 페이지에 걸친 scan → 모든 튜플 반환
8. updateTuple — 같은 크기 → 성공, 값 갱신
9. updateTuple — 더 큰 크기 → delete + insert로 처리, RID가 변경될 수 있음
10. 대량 삽입 (10,000건) → 전체 scan 일치
11. Buffer Pool 압박 (poolSize=4) → 정상 동작
12. pinCount leak 없음 — 연산 후 모든 페이지 pinCount == 0
13. **Kotest property-based**: 랜덤 insert/delete/get 시퀀스 → 상태 일관성

### Catalog

1. createTable → getTable(name) 일치
2. createTable 중복 이름 → 예외
3. listTables — 빈 상태 → 빈 리스트
4. listTables — 여러 테이블 생성 후 → 전체 반환
5. dropTable → getTable(name) null
6. dropTable 존재하지 않는 이름 → false
7. tableId 자동 증가 — 순서대로 할당
8. 스키마 정확성 — 컬럼 이름, 타입, nullable, maxLength 모두 보존
9. **영속성**: createTable → close → reopen → getTable 일치

### Database 통합 테스트

1. 기본 CRUD: createTable → insertTuple → getTuple → deleteTuple → getTuple(null)
2. 영속성: createTable + insertTuple → close → reopen → scanTable 일치
3. 여러 테이블: 독립적으로 CRUD 동작
4. 대량 삽입: 단일 테이블 10,000건 → scanTable 전체 일치
5. Buffer Pool 압박: poolSize=4로 1,000건 처리
6. AutoCloseable: close 후 연산 → IllegalStateException

## 벤치마크 목표

| 항목 | 메트릭 | 목표 |
|---|---|---|
| 순차 삽입 | ops/sec | 측정 (기준선) |
| 랜덤 읽기 (by RID) | p50, p99 latency | 측정 (기준선) |
| 전체 스캔 | rows/sec | 측정 (기준선) |
| Tuple 직렬화 | ops/sec | 측정 (기준선) |

> Phase 2도 Phase 1과 마찬가지로 정확성이 우선이다. 벤치마크는 기준선을 잡는 것이 목적.

## 참고 자료

- *Database Internals* Ch.3 (File Formats), Ch.5 (B-Tree Variants)
- CMU 15-445 Project 3 (Query Execution — Heap File 부분)
- PostgreSQL: `src/backend/access/heap/`, `src/include/catalog/`
- SQLite: `btree.c` (페이지 관리), `build.c` (스키마)
- bustub: `table_heap.cpp`, `tuple.cpp`
