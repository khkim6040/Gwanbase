# 범위 스캔 (Range Scan) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `WHERE col > 20 AND col < 30` 같은 부등식 조건을 B+Tree 인덱스 범위 스캔으로 실행하고, 선행 버그인 VARCHAR 인덱스 키 접두사 오매칭을 수정한다.

**Architecture:** `PlanNode.IndexScan`의 단일 `lookupValue`를 하한·상한 `Bound` 두 개로 일반화한다. `PlanEnumerator`가 WHERE의 AND 체인에서 `col OP literal`을 모아 인덱스 컬럼별 경계를 만들고, `KeySerializer`가 `KeyRange`를 B+Tree 바이트 구간 `[startKey, endKey?)`로 변환한다. 재검사(recheck)는 옵티마이저가 인덱스 조건을 필터에서 제거하지 않는 방식으로 구현한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions 5.8.0, Kotest property 5.8.0, B+Tree (Phase 1), Volcano Operator (Phase 4)

**Spec:** `docs/specs/advanced.md` → "4. 인덱스 고도화 → 범위 스캔 (Range Scan)"

## Global Constraints

- 커뮤니케이션·주석·커밋 메시지는 한국어, 코드 식별자는 영어.
- 커밋 형식: `타입: 간결한 설명` 한 줄. 본문 없음. `Co-Authored-By` 없음. 버그 수정은 `fix:`, 기능은 `feat:`, 문서는 `docs:`.
- TDD: 실패하는 테스트를 먼저 작성하고 실패를 확인한 뒤 구현한다.
- 테스트 메서드명은 백틱 한국어. 파일 경로는 `@TempDir`만 사용.
- 새 public 클래스/함수에 KDoc 한국어 주석. 다른 DB 동작을 언급하면 문서·소스 URL을 링크.
- `require()`로 전제 조건, `check()`로 상태 검증.
- 모듈 의존 방향 `execution → optimizer → sql → table → index → storage`. 하위가 상위를 참조하지 않는다.
- 각 Task 완료 시 `./gradlew :core:test`가 통과해야 한다 (빌드 깨진 상태로 커밋 금지).
- 브랜치: `feat/range-scan` (이미 생성됨). `main`에는 PR로 머지.
- 옵티마이저 비용 모델: `seqScanCost = max(1, rows * 0.01)`, `indexScanCost = 3 + matchedRows`. 인덱스 경로가 선택되려면 테이블이 수백 행 이상이고 매칭 행이 적어야 한다. 옵티마이저 테스트는 1000행 이상 삽입 후 좁은 범위로 검증한다.

---

## 파일 맵

| 파일 | 역할 | 변경 |
|------|------|------|
| `core/src/main/kotlin/gwanbase/index/KeySerializer.kt` | VARCHAR 종단 바이트, `KeyRange` → 바이트 구간 변환 | 수정 |
| `core/src/main/kotlin/gwanbase/index/KeyRange.kt` | 인덱스 스캔 컬럼 값 범위 (하한/상한 + 포함 여부) | 생성 |
| `core/src/main/kotlin/gwanbase/index/BPlusTree.kt` | `scan(startKey, endKey: ByteArray?)` 상한 없는 스캔 | 수정 |
| `core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt` | `rangeSupplier`로 범위 스캔, 필터 재검사 | 수정 |
| `core/src/main/kotlin/gwanbase/execution/Planner.kt` | `PlanNode.IndexScan` 경계 → `KeyRange` 변환 | 수정 |
| `core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt` | `Bound`, `IndexScan.lowerBound/upperBound`, EXPLAIN 출력 | 수정 |
| `core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt` | 양방향 `rangeSelectivity` | 수정 |
| `core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt` | 범위 조건 수집·경계 병합·인덱스 선택 | 수정 |
| `core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt` | 종단 바이트 순서, `scanBounds` | 수정 |
| `core/src/test/kotlin/gwanbase/index/BPlusTreeTest.kt` | 상한 없는 스캔 | 수정 |
| `core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt` | 다섯 가지 경계, 모순 범위, VARCHAR 범위 | 수정 |
| `core/src/test/kotlin/gwanbase/optimizer/PlanNodeTest.kt` | EXPLAIN 출력 형식 | 생성 |
| `core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt` | 양방향 선택도 | 수정 |
| `core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt` | 범위 경로 선택, 필터 유지, 교환, 병합 | 수정 |
| `core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt` | SQL end-to-end 범위 스캔, VARCHAR 접두사 회귀 | 수정 |
| `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt` | UNIQUE VARCHAR 접두사 회귀 | 수정 |
| `docs/specs/advanced.md`, `CLAUDE.md`, `HANDOFF.md` | 완료 표시, 진행 상황 표 | 수정 |

---

### Task 1: VARCHAR 인덱스 키 종단 바이트 (버그 수정)

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/index/KeySerializer.kt:46` (VARCHAR 분기)
- Test: `core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt`
- Test: `core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt`
- Test: `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt`

**Interfaces:**
- Consumes: `KeySerializer.serializeKey(value: Any, dataType: DataType): ByteArray`, `KeySerializer.compositeKey(columnKey, rid)`, `KeySerializer.equalityScanEnd(columnKey)`
- Produces: VARCHAR 키 = `UTF-8 바이트 + 0x00`. NUL 포함 문자열은 `IllegalArgumentException`. 시그니처 변경 없음.

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`KeySerializerTest.kt`의 `VARCHAR - 빈 문자열` 테스트 뒤에 추가한다. 파일 상단 import에 `io.kotest.property.Arb`, `io.kotest.property.arbitrary.*`, `org.junit.jupiter.api.assertThrows`를 추가한다.

```kotlin
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
```

- [ ] **Step 2: 실패하는 회귀 테스트 작성 (SQL 경로)**

`OptimizerIntegrationTest.kt`의 `parseSelect` 앞에 추가한다. import에 `gwanbase.sql.ExecuteResult`가 이미 있는지 확인하고 없으면 추가한다 (`gwanbase.sql.*`가 이미 import되어 있으므로 불필요할 수 있다).

```kotlin
    @Test
    fun `VARCHAR 인덱스 등가 검색이 접두사를 공유하는 행을 반환하지 않는다`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', 20)")
        }
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2001, 'abc', 1)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2002, 'abcd', 1)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2003, 'abd', 1)")
        database.executeSql("CREATE INDEX idx_users_name ON users (name)")
        database.executeSql("ANALYZE users")

        val explain = database.executeSql("EXPLAIN SELECT id FROM users WHERE name = 'abc'")
        explain.shouldBeInstanceOf<ExecuteResult.Explained>().planText shouldContain "IndexScan"

        val result = database.executeSql("SELECT id FROM users WHERE name = 'abc'")
            .shouldBeInstanceOf<ExecuteResult.Selected>()
        result.rows shouldBe listOf(listOf(2001))
    }
```

`SqlExecutorTest.kt`의 `CREATE UNIQUE INDEX 후 중복 삽입 시 UniqueViolationException` 뒤에 추가한다.

```kotlin
    @Test
    fun `UNIQUE VARCHAR 컬럼에 접두사를 공유하는 다른 값은 삽입된다`() {
        executor.execute("CREATE TABLE tags (name VARCHAR(50) UNIQUE)")
        executor.execute("INSERT INTO tags (name) VALUES ('abc')")
        // 'abc'가 있어도 'abcd'는 다른 값이므로 23505가 아니어야 한다
        executor.execute("INSERT INTO tags (name) VALUES ('abcd')")
        val result = executor.execute("SELECT name FROM tags") as ExecuteResult.Selected
        result.rows.size shouldBe 2
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
./gradlew :core:test --tests "gwanbase.index.KeySerializerTest" --tests "gwanbase.optimizer.OptimizerIntegrationTest" --tests "gwanbase.sql.SqlExecutorTest"
```
Expected: `등가 스캔 구간이 접두사를…`, `접두사 복합 키가…`, `NUL 문자를…`, `VARCHAR 인덱스 등가 검색이…`(rows가 2001, 2002 두 개), `UNIQUE VARCHAR 컬럼에…`(UniqueViolationException) 실패. property 테스트는 통과할 수도 있다(현재 구현도 대부분 순서를 보존).

- [ ] **Step 4: 구현**

`KeySerializer.kt`의 VARCHAR 분기를 교체한다.

```kotlin
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
```

`object KeySerializer {` 바로 아래에 상수를 추가한다.

```kotlin
    /** VARCHAR 키 종단 바이트. 접두사 관계인 문자열들의 복합 키 순서를 보존한다. */
    private val VARCHAR_TERMINATOR = byteArrayOf(0)
```

KDoc 상단 설명에 한 줄 추가한다: `VARCHAR는 뒤에 0x00 종단 바이트를 붙여 접두사 순서를 보존한다.`

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL. 기존 `VARCHAR - UTF-8 사전순 정렬`, `VARCHAR - 빈 문자열`도 통과해야 한다 (빈 문자열 키는 `[0x00]`, `"a"`는 `[0x61, 0x00]`이므로 여전히 앞).

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/index/KeySerializer.kt core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt
git commit -m "fix: VARCHAR 인덱스 키에 종단 바이트를 붙여 접두사 오매칭 수정"
```

---

### Task 2: BPlusTree 상한 없는 스캔

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/index/BPlusTree.kt:93-121`
- Test: `core/src/test/kotlin/gwanbase/index/BPlusTreeTest.kt`

**Interfaces:**
- Produces: `fun scan(startKey: ByteArray, endKey: ByteArray?): Iterator<Pair<ByteArray, ByteArray>>` — `endKey == null`이면 리프 체인 끝까지 반환. 기존 non-null 호출은 그대로 컴파일된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`BPlusTreeTest.kt`의 `scan의 start가 모든 키보다 크면 빈 결과를 반환한다` 뒤에 추가한다.

```kotlin
    @Test
    fun `scan의 end가 null이면 start 이상 전체를 리프 경계를 넘어 반환한다`() {
        for (i in 0 until 300) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(formatKey(250), null).asSequence().toList()

        result.size shouldBe 50
        result.first().first shouldBe formatKey(250)
        result.last().first shouldBe formatKey(299)
    }

    @Test
    fun `scan의 start가 빈 배열이고 end가 null이면 전체를 반환한다`() {
        for (i in 0 until 20) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(ByteArray(0), null).asSequence().toList()

        result.size shouldBe 20
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.BPlusTreeTest"`
Expected: 컴파일 에러 (`Null can not be a value of a non-null type ByteArray`).

- [ ] **Step 3: 구현**

`BPlusTree.scan`의 시그니처와 종료 조건을 바꾼다.

```kotlin
    /**
     * [startKey] 이상 [endKey] 미만 범위의 (key, value) 쌍을 키 오름차순으로 반환한다.
     * [endKey]가 null이면 상한 없이 리프 체인 끝까지 반환한다.
     *
     * 구현은 leaf 체인을 따라가며 조건을 만족하는 엔트리만 lazy 하게 내보낸다.
     * 각 leaf 단위로는 모든 엔트리를 힙 메모리에 복사해 가면서 페이지를
     * 즉시 unpin 한다 (scan 도중 긴 pin 유지 방지).
     */
    fun scan(startKey: ByteArray, endKey: ByteArray?): Iterator<Pair<ByteArray, ByteArray>> {
```

루프 안 종료 조건:

```kotlin
                for ((k, v) in entries) {
                    if (compareUnsigned(k, startKey) < 0) continue
                    if (endKey != null && compareUnsigned(k, endKey) >= 0) return@sequence
                    yield(k to v)
                }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.BPlusTreeTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/index/BPlusTree.kt core/src/test/kotlin/gwanbase/index/BPlusTreeTest.kt
git commit -m "feat: BPlusTree.scan에 상한 없는(endKey=null) 스캔 지원"
```

---

### Task 3: KeyRange와 바이트 구간 변환

**Files:**
- Create: `core/src/main/kotlin/gwanbase/index/KeyRange.kt`
- Modify: `core/src/main/kotlin/gwanbase/index/KeySerializer.kt` (`scanBounds` 추가)
- Test: `core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt`

**Interfaces:**
- Consumes: `KeySerializer.serializeKey`, `KeySerializer.equalityScanEnd`
- Produces:
  - `data class KeyRange(val lower: Any?, val lowerInclusive: Boolean, val upper: Any?, val upperInclusive: Boolean)` + `KeyRange.equal(value: Any)`
  - `fun KeySerializer.scanBounds(range: KeyRange, dataType: DataType): Pair<ByteArray, ByteArray?>`

- [ ] **Step 1: 실패하는 테스트 작성**

`KeySerializerTest.kt` 끝(RID 테스트 뒤)에 추가한다.

```kotlin
    // --- scanBounds: KeyRange → [startKey, endKey?) ---

    private fun key(v: Int) = KeySerializer.serializeKey(v, DataType.INT32)
    private fun succ(v: Int) = KeySerializer.equalityScanEnd(key(v))

    @Test
    fun `scanBounds - 등가는 값부터 successor 미만`() {
        val (start, end) = KeySerializer.scanBounds(KeyRange.equal(5), DataType.INT32)
        start shouldBe key(5)
        end shouldBe succ(5)
    }

    @Test
    fun `scanBounds - 포함 하한은 값부터, 상한 없음은 null`() {
        val (start, end) = KeySerializer.scanBounds(KeyRange(5, true, null, false), DataType.INT32)
        start shouldBe key(5)
        end shouldBe null
    }

    @Test
    fun `scanBounds - 제외 하한은 successor부터`() {
        val (start, _) = KeySerializer.scanBounds(KeyRange(5, false, null, false), DataType.INT32)
        start shouldBe succ(5)
    }

    @Test
    fun `scanBounds - 하한 없음은 빈 배열부터, 제외 상한은 값 미만`() {
        val (start, end) = KeySerializer.scanBounds(KeyRange(null, false, 9, false), DataType.INT32)
        start shouldBe ByteArray(0)
        end shouldBe key(9)
    }

    @Test
    fun `scanBounds - 포함 상한은 successor 미만`() {
        val (_, end) = KeySerializer.scanBounds(KeyRange(null, false, 9, true), DataType.INT32)
        end shouldBe succ(9)
    }

    @Test
    fun `scanBounds - VARCHAR 제외 하한이 접두사를 공유하는 더 긴 문자열을 포함한다`() {
        // name > 'abc' 는 'abcd'를 포함해야 한다
        val (start, _) = KeySerializer.scanBounds(KeyRange("abc", false, null, false), DataType.VARCHAR)
        val abcd = KeySerializer.compositeKey(KeySerializer.serializeKey("abcd", DataType.VARCHAR), RID(0, 0))
        compareBytes(abcd, start) shouldBeGreaterThan 0
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.KeySerializerTest"`
Expected: 컴파일 에러 (`Unresolved reference: KeyRange`, `scanBounds`).

- [ ] **Step 3: KeyRange 생성**

`core/src/main/kotlin/gwanbase/index/KeyRange.kt`:

```kotlin
package gwanbase.index

/**
 * 인덱스 스캔이 읽을 컬럼 값 범위.
 *
 * 경계가 null이면 그 방향은 무제한이다. 등가 조건은 [lower]와 [upper]가 같은 값이고
 * 양쪽 모두 포함인 범위로 표현한다.
 *
 * PostgreSQL nbtree는 시작 위치용 insertion scankey와 종료 판정용 search scankey를
 * 따로 두지만(`_bt_first()`, `_bt_checkkeys()`), Gwanbase는 B+Tree가 바이트 구간
 * `[startKey, endKey)`만 이해하므로 값 범위 하나로 충분하다.
 * - https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/README
 *
 * @param lower 하한 값 (null이면 없음)
 * @param lowerInclusive 하한 포함 여부 (`>=`이면 true, `>`이면 false)
 * @param upper 상한 값 (null이면 없음)
 * @param upperInclusive 상한 포함 여부 (`<=`이면 true, `<`이면 false)
 */
data class KeyRange(
    val lower: Any?,
    val lowerInclusive: Boolean,
    val upper: Any?,
    val upperInclusive: Boolean,
) {
    companion object {
        /** `col = value` 등가 조건 범위. */
        fun equal(value: Any): KeyRange = KeyRange(value, true, value, true)
    }
}
```

- [ ] **Step 4: scanBounds 구현**

`KeySerializer.kt`의 `equalityScanEnd` 뒤에 추가한다.

```kotlin
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
     * @return (startKey, endKey). endKey가 null이면 상한 없음
     */
    fun scanBounds(range: KeyRange, dataType: DataType): Pair<ByteArray, ByteArray?> {
        val start = when {
            range.lower == null -> ByteArray(0)
            range.lowerInclusive -> serializeKey(range.lower, dataType)
            else -> equalityScanEnd(serializeKey(range.lower, dataType))
        }
        val end = when {
            range.upper == null -> null
            range.upperInclusive -> equalityScanEnd(serializeKey(range.upper, dataType))
            else -> serializeKey(range.upper, dataType)
        }
        return start to end
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.KeySerializerTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/index/KeyRange.kt core/src/main/kotlin/gwanbase/index/KeySerializer.kt core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt
git commit -m "feat: KeyRange와 B+Tree 스캔 구간 변환(scanBounds) 추가"
```

---

### Task 4: IndexScanOperator 범위 스캔

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt`
- Modify: `core/src/main/kotlin/gwanbase/execution/Planner.kt:50-62` (컴파일 유지용 최소 변경)
- Test: `core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt`

**Interfaces:**
- Consumes: `KeyRange`, `KeySerializer.scanBounds`, `BPlusTree.scan(start, end?)`
- Produces: 생성자 파라미터 `lookupKeySupplier: () -> Any?` → `rangeSupplier: () -> KeyRange?`, `remainingFilter` → `filter`. supplier가 null을 돌려주면 빈 결과.

- [ ] **Step 1: 기존 테스트를 새 시그니처로 바꾸고 범위 테스트 추가**

`IndexScanOperatorTest.kt`:

1. import에 `gwanbase.index.KeyRange` 추가.
2. 기존 5개 테스트의 `lookupKeySupplier = { 2 }` → `rangeSupplier = { KeyRange.equal(2) }`, `lookupKeySupplier = { null }` → `rangeSupplier = { null }`, 그 외 `{ N }` → `{ KeyRange.equal(N) }`. `remainingFilter = null` → `filter = null`. `open 재호출 시 다른 키로 검색 가능` 테스트에서 변수로 키를 바꾸는 부분은 `KeyRange.equal(currentKey)`처럼 감싼다.
3. `buildIdIndex()`를 컬럼 일반화 헬퍼로 교체한다.

```kotlin
    /**
     * [columnIndex] 컬럼에 대한 B+Tree 인덱스를 수동으로 구축한다 (복합 키 사용).
     */
    private fun buildIndex(columnIndex: Int, type: DataType): BPlusTree {
        val tree = BPlusTree.createNew(database.bpm)
        val iter = database.scanTable("students")
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            val value = ExpressionEvaluator.getTupleValue(tuple, columnIndex, type) ?: continue
            val columnKey = KeySerializer.serializeKey(value, type)
            tree.insert(
                KeySerializer.compositeKey(columnKey, rid),
                KeySerializer.serializeRid(rid),
            )
        }
        return tree
    }

    private fun buildIdIndex(): BPlusTree = buildIndex(0, DataType.INT32)

    /** id 인덱스로 [range]를 스캔해 id 목록을 반환한다. */
    private fun scanIds(range: KeyRange?, filter: gwanbase.sql.Expression? = null): List<Int> {
        val schema = database.getTable("students")!!.schema
        val op = IndexScanOperator(
            database = database, tableName = "students", schema = schema,
            tree = buildIdIndex(), indexColumnIndex = 0, indexColumnType = DataType.INT32,
            rangeSupplier = { range }, filter = filter,
        )
        op.open()
        val ids = generateSequence { op.next() }.map { it.getInt(0)!! }.toList()
        op.close()
        return ids
    }
```

4. 범위 테스트를 추가한다.

```kotlin
    @Test
    fun `범위 - 제외 하한(gt)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(3, false, null, false)) shouldBe listOf(4, 5)
    }

    @Test
    fun `범위 - 포함 하한(gte)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(3, true, null, false)) shouldBe listOf(3, 4, 5)
    }

    @Test
    fun `범위 - 제외 상한(lt)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, 3, false)) shouldBe listOf(1, 2)
    }

    @Test
    fun `범위 - 포함 상한(lte)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, 3, true)) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `범위 - 양방향 경계`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(2, false, 5, false)) shouldBe listOf(3, 4)
    }

    @Test
    fun `범위 - 하한이 상한보다 크면 빈 결과`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(4, true, 2, true)) shouldBe emptyList()
    }

    @Test
    fun `범위 - 양쪽 경계 없음은 전체 반환`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, null, false)) shouldBe listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `범위 - 음수를 포함한 INT32 순서 보존`() {
        listOf(-5, -1, 0, 3, 7).forEach { insertStudent(it, "s$it", 0) }
        scanIds(KeyRange(-1, true, 3, true)) shouldBe listOf(-1, 0, 3)
    }

    @Test
    fun `범위 - 필터가 튜플을 재검사한다`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        // id >= 2 범위 안에서 score = 40 만 통과
        val filter = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef(null, "score"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.IntLiteral(40),
        )
        scanIds(KeyRange(2, true, null, false), filter) shouldBe listOf(4)
    }

    @Test
    fun `범위 - VARCHAR 제외 하한이 접두사를 공유하는 긴 문자열을 포함한다`() {
        insertStudent(1, "abc", 0)
        insertStudent(2, "abcd", 0)
        insertStudent(3, "abd", 0)
        insertStudent(4, "ab", 0)
        val schema = database.getTable("students")!!.schema
        val op = IndexScanOperator(
            database = database, tableName = "students", schema = schema,
            tree = buildIndex(1, DataType.VARCHAR), indexColumnIndex = 1, indexColumnType = DataType.VARCHAR,
            rangeSupplier = { KeyRange("abc", false, null, false) }, filter = null,
        )
        op.open()
        val names = generateSequence { op.next() }.map { it.getString(1) }.toList()
        op.close()
        names shouldBe listOf("abcd", "abd")
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.execution.IndexScanOperatorTest"`
Expected: 컴파일 에러 (`rangeSupplier`, `filter` 파라미터 없음).

- [ ] **Step 3: IndexScanOperator 구현**

파일 전체를 다음으로 교체한다.

```kotlin
package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeyRange
import gwanbase.index.KeySerializer
import gwanbase.sql.Expression
import gwanbase.table.*
import gwanbase.txn.DatabaseSession

/**
 * B+Tree 인덱스로 [KeyRange] 범위에 매칭하는 튜플을 스캔하는 연산자.
 *
 * rangeSupplier로 범위를 동적으로 받을 수 있어 open() 재호출 시 다른 범위로
 * 스캔할 수 있다. 등가 조건은 `KeyRange.equal(v)`로 표현한다.
 *
 * [filter]는 힙 튜플에서 다시 평가한다. 옵티마이저가 인덱스 조건을 필터에서 제거하지
 * 않으므로 이 평가가 PostgreSQL의 recheck 역할을 한다:
 * https://www.postgresql.org/docs/current/index-scanning.html
 */
class IndexScanOperator(
    private val database: Database,
    private val tableName: String,
    private val schema: Schema,
    private val tree: BPlusTree,
    private val indexColumnIndex: Int,
    private val indexColumnType: DataType,
    private val rangeSupplier: () -> KeyRange?,
    private val filter: Expression?,
    private val session: DatabaseSession? = null,
) : Operator {

    private var matchedRids: Iterator<RID> = emptyList<RID>().iterator()

    override val outputSchema: Schema get() = schema

    override fun open() {
        val range = rangeSupplier() ?: run {
            matchedRids = emptyList<RID>().iterator()
            return
        }
        val (startKey, endKey) = KeySerializer.scanBounds(range, indexColumnType)
        val scanIter = tree.scan(startKey, endKey)
        val rids = mutableListOf<RID>()
        while (scanIter.hasNext()) {
            val (_, value) = scanIter.next()
            rids.add(KeySerializer.deserializeRid(value))
        }
        matchedRids = rids.iterator()
    }

    override fun next(): Tuple? {
        while (matchedRids.hasNext()) {
            val rid = matchedRids.next()
            if (session != null) {
                session.acquireSharedLock(tableName, rid)
            }
            val tuple = database.getTuple(tableName, rid) ?: continue
            if (filter != null &&
                !ExpressionEvaluator.evaluateCondition(schema, tuple, filter)
            ) {
                continue
            }
            return tuple
        }
        return null
    }

    override fun close() {
        matchedRids = emptyList<RID>().iterator()
    }
}
```

- [ ] **Step 4: Planner 컴파일 유지**

`Planner.kt`의 `is PlanNode.IndexScan` 분기에서 생성자 호출만 바꾼다 (PlanNode는 아직 `lookupValue`를 가진다).

```kotlin
            IndexScanOperator(
                database, plan.tableName, schema, tree,
                colIndex, colType,
                { evaluateLiteral(plan.lookupValue)?.let { KeyRange.equal(it) } },
                plan.remainingFilter, session,
            )
```

import에 `gwanbase.index.KeyRange`를 추가한다.

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt core/src/main/kotlin/gwanbase/execution/Planner.kt core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt
git commit -m "feat: IndexScanOperator가 KeyRange 범위 스캔과 필터 재검사를 지원"
```

---

### Task 5: PlanNode.IndexScan 경계 표현과 EXPLAIN

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt`
- Modify: `core/src/main/kotlin/gwanbase/execution/Planner.kt`
- Modify: `core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt:41-46` (컴파일 유지용 최소 변경)
- Modify: `core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt` (컴파일 유지)
- Create: `core/src/test/kotlin/gwanbase/optimizer/PlanNodeTest.kt`

**Interfaces:**
- Produces:
  - `data class Bound(val value: Expression, val inclusive: Boolean)` (`gwanbase.optimizer`)
  - `PlanNode.IndexScan(tableName, indexName, indexColumnName, lowerBound: Bound?, upperBound: Bound?, filter: Expression?, estimatedRows, estimatedCost)` + `val isEquality: Boolean`
  - EXPLAIN: 등가 `IndexScan(table=t, index=i, key=42)`, 범위 `IndexScan(table=t, index=i, range=[20, 30))`, 상한 없음 `range=(20, +inf)`, 하한 없음 `range=(-inf, 30]`

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/optimizer/PlanNodeTest.kt`:

```kotlin
package gwanbase.optimizer

import gwanbase.sql.Expression
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class PlanNodeTest {

    private fun scan(lower: Bound?, upper: Bound?) = PlanNode.IndexScan(
        tableName = "t", indexName = "idx", indexColumnName = "c",
        lowerBound = lower, upperBound = upper, filter = null,
        estimatedRows = 1, estimatedCost = 4.0,
    )

    @Test
    fun `등가 경계는 key=로 출력된다`() {
        val v = Expression.IntLiteral(42)
        val node = scan(Bound(v, true), Bound(v, true))
        node.isEquality.shouldBeTrue()
        node.explain() shouldContain "key=42"
    }

    @Test
    fun `양방향 범위는 포함 여부에 따라 괄호가 달라진다`() {
        val node = scan(Bound(Expression.IntLiteral(20), true), Bound(Expression.IntLiteral(30), false))
        node.isEquality.shouldBeFalse()
        node.explain() shouldContain "range=[20, 30)"
    }

    @Test
    fun `상한 없는 범위는 +inf로 출력된다`() {
        val node = scan(Bound(Expression.IntLiteral(20), false), null)
        node.explain() shouldContain "range=(20, +inf)"
    }

    @Test
    fun `하한 없는 범위는 -inf로 출력된다`() {
        val node = scan(null, Bound(Expression.StringLiteral("abc"), true))
        node.explain() shouldContain "range=(-inf, 'abc']"
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.PlanNodeTest"`
Expected: 컴파일 에러 (`Bound`, `lowerBound` 없음).

- [ ] **Step 3: PlanNode 수정**

`PlanNode.kt`에서 `IndexScan`을 교체하고 `Bound`를 파일 끝에 추가한다.

```kotlin
    /**
     * 인덱스 스캔. [lowerBound]/[upperBound]가 컬럼 값 범위를 정하고,
     * [filter]는 힙 튜플에서 재검사할 전체 WHERE 조건이다 (인덱스 조건 포함).
     */
    data class IndexScan(
        val tableName: String,
        val indexName: String,
        val indexColumnName: String,
        val lowerBound: Bound?,
        val upperBound: Bound?,
        val filter: Expression?,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode() {
        /** 등가 조건 여부: 양쪽 경계가 같은 값이고 모두 포함. */
        val isEquality: Boolean
            get() = lowerBound != null && upperBound != null &&
                lowerBound.inclusive && upperBound.inclusive &&
                lowerBound.value == upperBound.value

        /** EXPLAIN용 범위 텍스트. */
        internal fun describeRange(): String {
            if (isEquality) return "key=${lowerBound!!.value.toSql()}"
            val lo = lowerBound?.let { (if (it.inclusive) "[" else "(") + it.value.toSql() } ?: "(-inf"
            val hi = upperBound?.let { it.value.toSql() + (if (it.inclusive) "]" else ")") } ?: "+inf)"
            return "range=$lo, $hi"
        }
    }
```

`explain()`의 IndexScan 분기:

```kotlin
            is IndexScan -> "${prefix}IndexScan(table=$tableName, index=$indexName, ${describeRange()})" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
```

파일 끝에:

```kotlin
/**
 * 인덱스 스캔 경계.
 *
 * @param value 리터럴 표현식 (Planner가 실행 시점에 값으로 평가)
 * @param inclusive 경계 포함 여부 (`>=`/`<=`이면 true, `>`/`<`이면 false)
 */
data class Bound(val value: Expression, val inclusive: Boolean)
```

- [ ] **Step 4: Planner 수정**

`Planner.kt`의 `is PlanNode.IndexScan` 분기와 `evaluateLiteral` 뒤에:

```kotlin
        is PlanNode.IndexScan -> {
            val tableInfo = database.getTable(plan.tableName)!!
            val indexInfo = database.getCatalog().getIndex(plan.indexName)!!
            val tree = BPlusTree(database.bpm, indexInfo.rootPageId)
            val schema = tableInfo.schema
            val colIndex = schema.columnIndex(plan.indexColumnName)
            val colType = schema.column(colIndex).type
            IndexScanOperator(
                database, plan.tableName, schema, tree,
                colIndex, colType, { toKeyRange(plan) },
                plan.filter, session,
            )
        }
```

```kotlin
    /**
     * 계획 노드의 경계 리터럴을 평가해 [KeyRange]로 만든다.
     * 경계 중 하나라도 NULL이면 비교 결과가 항상 UNKNOWN이므로 null(빈 결과)을 반환한다.
     */
    private fun toKeyRange(plan: PlanNode.IndexScan): KeyRange? {
        val lower = plan.lowerBound?.let { evaluateLiteral(it.value) ?: return null }
        val upper = plan.upperBound?.let { evaluateLiteral(it.value) ?: return null }
        return KeyRange(
            lower, plan.lowerBound?.inclusive ?: false,
            upper, plan.upperBound?.inclusive ?: false,
        )
    }
```

`evaluateLiteral`의 KDoc을 `인덱스 경계 값 용`으로 고친다.

- [ ] **Step 5: PlanEnumerator 컴파일 유지 (등가만)**

`PlanEnumerator.bestAccessPath`의 `PlanNode.IndexScan(...)` 생성 부분만 바꾼다. 로직은 Task 7에서 교체한다.

```kotlin
                if (idxCost < seqCost) {
                    val bound = Bound(eqColumn.second, inclusive = true)
                    return PlanNode.IndexScan(
                        tableName, matchingIndex.name, matchingIndex.columnName,
                        bound, bound, removeCondition(filter, eqColumn.first),
                        matchedRows, idxCost,
                    )
                }
```

`PlanEnumeratorTest.kt`의 `인덱스 있는 등가 조건에서 IndexScan 선택` 끝에 두 줄을 추가한다.

```kotlin
        plan.lowerBound shouldBe Bound(gwanbase.sql.Expression.IntLiteral(42), true)
        plan.upperBound shouldBe plan.lowerBound
```

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt core/src/main/kotlin/gwanbase/execution/Planner.kt core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt core/src/test/kotlin/gwanbase/optimizer/PlanNodeTest.kt core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt
git commit -m "feat: PlanNode.IndexScan을 하한·상한 경계로 일반화하고 EXPLAIN에 범위 출력"
```

---

### Task 6: CostEstimator 양방향 범위 선택도

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt:16-50`
- Test: `core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt`

**Interfaces:**
- Produces: `fun rangeSelectivity(stats: ColumnStats?, lower: Long?, upper: Long?): Double` (기존 `(stats, threshold)` 대체). 상수 `DEFAULT_RANGE_SELECTIVITY = 1.0 / 3`, `DEFAULT_TWO_SIDED_RANGE_SELECTIVITY = 0.005`.

- [ ] **Step 1: 기존 테스트를 새 시그니처로 바꾸고 추가**

`CostEstimatorTest.kt`의 세 `범위 선택도` 테스트를 교체·추가한다.

```kotlin
    @Test
    fun `범위 선택도 - 하한만 있을 때 (max - lower) 나누기 (max - min)`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 50, null) shouldBe 0.5
    }

    @Test
    fun `범위 선택도 - 상한만 있을 때 (upper - min) 나누기 (max - min)`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, null, 20) shouldBe 0.2
    }

    @Test
    fun `범위 선택도 - 양방향은 구간 길이 비율`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 20, 30) shouldBe 0.1
    }

    @Test
    fun `범위 선택도 - 경계가 통계 범위 밖이면 0과 1로 clamp`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 200, null) shouldBe 0.0
        CostEstimator.rangeSelectivity(stats, -50, null) shouldBe 1.0
    }

    @Test
    fun `범위 선택도 - 통계 없을 때 단방향 기본값`() {
        CostEstimator.rangeSelectivity(null, 50, null) shouldBe CostEstimator.DEFAULT_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - 통계 없을 때 양방향 기본값`() {
        CostEstimator.rangeSelectivity(null, 20, 30) shouldBe CostEstimator.DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - min과 max가 같으면 기본값`() {
        val stats = ColumnStats(distinctCount = 1, minValue = 5L, maxValue = 5L, nullCount = 0)
        CostEstimator.rangeSelectivity(stats, 5, null) shouldBe CostEstimator.DEFAULT_RANGE_SELECTIVITY
    }

    @Test
    fun `범위 선택도 - 경계가 둘 다 없으면 예외`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CostEstimator.rangeSelectivity(null, null, null)
        }
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.CostEstimatorTest"`
Expected: 컴파일 에러 (인자 수 불일치, `DEFAULT_TWO_SIDED_RANGE_SELECTIVITY` 없음).

- [ ] **Step 3: 구현**

`CostEstimator.kt`의 상수와 `rangeSelectivity`를 교체한다.

```kotlin
    /**
     * 통계 없을 때 단방향 범위 조건(`col > v`)의 기본 선택도.
     * PostgreSQL `DEFAULT_INEQ_SEL`과 같은 값:
     * https://github.com/postgres/postgres/blob/master/src/include/utils/selfuncs.h
     */
    const val DEFAULT_RANGE_SELECTIVITY = 1.0 / 3

    /**
     * 통계 없을 때 양방향 범위 조건(`col > a AND col < b`)의 기본 선택도.
     * PostgreSQL `DEFAULT_RANGE_INEQ_SEL`과 같은 값.
     */
    const val DEFAULT_TWO_SIDED_RANGE_SELECTIVITY = 0.005
```

```kotlin
    /**
     * 범위 조건의 선택도를 추정한다.
     *
     * 통계의 min/max 사이에 값이 균등 분포한다고 가정하고
     * `(min(upper, max) - max(lower, min)) / (max - min)`을 [0, 1]로 clamp한다.
     * 경계 포함 여부는 무시한다. PostgreSQL은 히스토그램으로 각 경계의 선택도를 구한 뒤
     * `hisel + losel - 1`로 결합한다(`clauselist_selectivity_ext()`):
     * https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/clausesel.c
     *
     * @param lower 하한 (null이면 없음)
     * @param upper 상한 (null이면 없음)
     * @throws IllegalArgumentException 경계가 둘 다 없을 때
     */
    fun rangeSelectivity(stats: ColumnStats?, lower: Long?, upper: Long?): Double {
        require(lower != null || upper != null) { "범위 조건에는 경계가 하나 이상 있어야 한다" }
        val default = if (lower != null && upper != null) DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
        else DEFAULT_RANGE_SELECTIVITY
        val min = stats?.minValue as? Long ?: return default
        val max = stats.maxValue as? Long ?: return default
        if (max == min) return default
        val lo = max(lower ?: min, min)
        val hi = minOf(upper ?: max, max)
        return ((hi - lo).toDouble() / (max - min).toDouble()).coerceIn(0.0, 1.0)
    }
```

(`kotlin.math.max`가 이미 import되어 있다. `minOf`는 stdlib.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test`
Expected: PASS. (`rangeSelectivity`의 기존 호출처는 테스트뿐이다.)

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt
git commit -m "feat: CostEstimator 범위 선택도를 하한·상한 양방향으로 확장"
```

---

### Task 7: PlanEnumerator 범위 조건 인덱스 접근 경로

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt`
- Test: `core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt`

**Interfaces:**
- Consumes: `Bound`, `PlanNode.IndexScan(…, lowerBound, upperBound, filter, …)`, `CostEstimator.rangeSelectivity(stats, lower, upper)`, `CostEstimator.equalitySelectivity(stats)`
- Produces: `bestAccessPath(tableName, filter)`가 `=`, `<`, `<=`, `>`, `>=` 조건(리터럴이 어느 쪽이든)을 인덱스 경계로 변환. 필터는 **그대로** `IndexScan.filter`에 실린다. `extractEqualityColumn`, `removeCondition` 삭제.

- [ ] **Step 1: 실패하는 테스트 작성**

`PlanEnumeratorTest.kt`에 헬퍼와 테스트를 추가한다. 파일 상단 import에 `gwanbase.sql.BinaryOperator`, `gwanbase.sql.Expression`을 추가하고 기존 테스트의 `gwanbase.sql.` 접두사는 그대로 두어도 된다.

```kotlin
    private fun col(name: String) = Expression.ColumnRef(null, name)
    private fun lit(v: Long) = Expression.IntLiteral(v)
    private fun bin(l: Expression, op: BinaryOperator, r: Expression) = Expression.BinaryOp(l, op, r)

    /** 1000행 + id 인덱스 + ANALYZE. */
    private fun prepareIndexedUsers() {
        for (i in 1..1000) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")
    }

    @Test
    fun `인덱스 있는 단방향 범위 조건에서 IndexScan 선택`() {
        prepareIndexedUsers()
        // id > 995 → 통계상 5행 → 비용 8 < seq 10
        val filter = bin(col("id"), BinaryOperator.GT, lit(995))
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(995), false)
        scan.upperBound shouldBe null
    }

    @Test
    fun `인덱스 있는 양방향 범위 조건을 하나의 구간으로 병합`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GTE, lit(100)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.LT, lit(104)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(100), true)
        scan.upperBound shouldBe Bound(lit(104), false)
    }

    @Test
    fun `IndexScan은 인덱스 조건을 포함한 전체 필터를 유지한다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(995)),
            BinaryOperator.AND,
            bin(col("age"), BinaryOperator.EQ, lit(30)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        plan.shouldBeInstanceOf<PlanNode.IndexScan>().filter shouldBe filter
    }

    @Test
    fun `리터럴이 왼쪽에 있으면 연산자를 뒤집어 매칭한다`() {
        prepareIndexedUsers()
        // 5 > id  ≡  id < 5
        val filter = bin(lit(5), BinaryOperator.GT, col("id"))
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe null
        scan.upperBound shouldBe Bound(lit(5), false)
    }

    @Test
    fun `같은 방향 경계가 둘이면 첫 번째만 인덱스로 보낸다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(995)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.GT, lit(990)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(995), false)
        scan.filter shouldBe filter
    }

    @Test
    fun `등가 조건은 앞선 범위 경계를 덮어쓴다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(3)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.EQ, lit(42)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.isEquality shouldBe true
        scan.lowerBound shouldBe Bound(lit(42), true)
    }

    @Test
    fun `부등호가 아닌 조건(NEQ)은 인덱스를 쓰지 않는다`() {
        prepareIndexedUsers()
        val filter = bin(col("id"), BinaryOperator.NEQ, lit(42))
        enumerator.bestAccessPath("users", filter).shouldBeInstanceOf<PlanNode.SeqScan>()
    }

    @Test
    fun `넓은 범위 조건은 비용 비교로 SeqScan을 선택한다`() {
        prepareIndexedUsers()
        // id > 10 → 990행 → 인덱스 비용 993 > seq 10
        val filter = bin(col("id"), BinaryOperator.GT, lit(10))
        enumerator.bestAccessPath("users", filter).shouldBeInstanceOf<PlanNode.SeqScan>()
    }
```

기존 `인덱스 있는 등가 조건에서 IndexScan 선택`의 마지막에 `plan.filter shouldBe filter`를 추가한다 (등가 조건도 이제 필터에 남는다).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.PlanEnumeratorTest"`
Expected: 범위 관련 테스트가 `SeqScan` 반환으로 실패, 등가 테스트가 `filter shouldBe filter`에서 실패 (현재는 null).

- [ ] **Step 3: 구현**

`PlanEnumerator.kt`에서 `bestAccessPath`, `extractEqualityColumn`, `removeCondition`, `estimateFilteredRows`를 아래로 교체한다. `bestJoinOrder`, `buildJoin`은 그대로 둔다.

```kotlin
    /**
     * 단일 테이블의 최적 접근 경로를 선택한다.
     *
     * WHERE의 AND 체인에서 `col OP literal` 조건을 모아 인덱스 컬럼별 하한·상한 경계를
     * 만들고, 각 인덱스 경로의 비용을 SeqScan과 비교해 가장 저렴한 계획을 고른다.
     * 인덱스 조건은 필터에서 제거하지 않는다. 실행기가 힙 튜플에서 전체 필터를 다시
     * 평가하므로(recheck) 경계가 넓어도 결과는 정확하다.
     *
     * PostgreSQL `match_opclause_to_indexcol()`은 `indexkey OP const` 꼴만 인덱스 조건으로
     * 받고 `const OP indexkey`는 commutator로 뒤집는다:
     * https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/indxpath.c
     *
     * @param tableName 대상 테이블
     * @param filter WHERE 조건 (null이면 전체 스캔)
     * @return 최적 PlanNode
     */
    fun bestAccessPath(tableName: String, filter: Expression?): PlanNode {
        val rowCount = catalog.getRowCount(tableName)
        val seqCost = CostEstimator.seqScanCost(rowCount)
        if (filter == null) return PlanNode.SeqScan(tableName, null, rowCount, seqCost)

        val conditions = collectIndexConditions(filter)
        var best: PlanNode = PlanNode.SeqScan(
            tableName, filter, estimateFilteredRows(tableName, conditions, rowCount), seqCost,
        )
        for (index in catalog.getIndexesForTable(tableName)) {
            val (lower, upper) = indexRange(conditions, index.columnName) ?: continue
            val stats = catalog.getColumnStats(tableName, index.columnName)
            val rows = max(1, (rowCount * rangeSelectivity(lower, upper, stats)).toLong())
            val cost = CostEstimator.indexScanCost(rows)
            if (cost < best.estimatedCost) {
                best = PlanNode.IndexScan(tableName, index.name, index.columnName, lower, upper, filter, rows, cost)
            }
        }
        return best
    }

    /** 인덱스 조건 후보. 컬럼이 왼쪽에 오도록 연산자를 정규화한 상태. */
    private data class IndexCondition(val column: String, val op: BinaryOperator, val literal: Expression)

    /** AND 체인에서 `col OP literal` 또는 `literal OP col` 꼴 조건을 모은다. OP는 =, <, <=, >, >=. */
    private fun collectIndexConditions(expr: Expression): List<IndexCondition> {
        if (expr !is Expression.BinaryOp) return emptyList()
        if (expr.op == BinaryOperator.AND) {
            return collectIndexConditions(expr.left) + collectIndexConditions(expr.right)
        }
        if (expr.op !in RANGE_OPERATORS) return emptyList()
        val left = expr.left
        val right = expr.right
        return when {
            left is Expression.ColumnRef && isLiteral(right) -> listOf(IndexCondition(left.name, expr.op, right))
            right is Expression.ColumnRef && isLiteral(left) -> listOf(IndexCondition(right.name, commute(expr.op), left))
            else -> emptyList()
        }
    }

    private fun isLiteral(expr: Expression): Boolean =
        expr is Expression.IntLiteral || expr is Expression.StringLiteral ||
            expr is Expression.BoolLiteral || expr is Expression.FloatLiteral

    /** `literal OP col`을 `col OP' literal`로 바꿀 때의 OP'. */
    private fun commute(op: BinaryOperator): BinaryOperator = when (op) {
        BinaryOperator.LT -> BinaryOperator.GT
        BinaryOperator.GT -> BinaryOperator.LT
        BinaryOperator.LTE -> BinaryOperator.GTE
        BinaryOperator.GTE -> BinaryOperator.LTE
        else -> op
    }

    /**
     * 컬럼의 하한·상한 경계를 만든다. 조건이 하나도 없으면 null.
     *
     * 같은 방향 경계가 여럿이면 첫 번째를 쓴다(재검사가 정확성을 보장). 등가는 항상
     * 가장 좁으므로 앞선 경계를 덮어쓴다. PostgreSQL `_bt_preprocess_keys()`는 더 좁은
     * 쪽을 고르지만 리터럴 비교 코드를 줄이기 위해 단순화했다:
     * https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtutils.c
     */
    private fun indexRange(conditions: List<IndexCondition>, column: String): Pair<Bound?, Bound?>? {
        var lower: Bound? = null
        var upper: Bound? = null
        for (cond in conditions) {
            if (cond.column != column) continue
            when (cond.op) {
                BinaryOperator.EQ -> {
                    lower = Bound(cond.literal, true)
                    upper = Bound(cond.literal, true)
                }
                BinaryOperator.GT -> lower = lower ?: Bound(cond.literal, false)
                BinaryOperator.GTE -> lower = lower ?: Bound(cond.literal, true)
                BinaryOperator.LT -> upper = upper ?: Bound(cond.literal, false)
                BinaryOperator.LTE -> upper = upper ?: Bound(cond.literal, true)
                else -> {}
            }
        }
        if (lower == null && upper == null) return null
        return lower to upper
    }

    /** 경계 쌍의 선택도. 등가면 등가 선택도, 정수 경계면 통계 기반, 그 외는 기본값. */
    private fun rangeSelectivity(lower: Bound?, upper: Bound?, stats: ColumnStats?): Double {
        val isEquality = lower != null && upper != null &&
            lower.inclusive && upper.inclusive && lower.value == upper.value
        if (isEquality) return CostEstimator.equalitySelectivity(stats)
        val lo = (lower?.value as? Expression.IntLiteral)?.value
        val hi = (upper?.value as? Expression.IntLiteral)?.value
        if (lo == null && hi == null) {
            // VARCHAR 등 비정수 경계: 통계가 없으므로 기본값
            return if (lower != null && upper != null) CostEstimator.DEFAULT_TWO_SIDED_RANGE_SELECTIVITY
            else CostEstimator.DEFAULT_RANGE_SELECTIVITY
        }
        return CostEstimator.rangeSelectivity(stats, lo, hi)
    }

    /** 필터 적용 후 예상 행 수. 인덱스 조건이 있는 컬럼 중 가장 좁은 선택도를 쓴다. */
    private fun estimateFilteredRows(tableName: String, conditions: List<IndexCondition>, totalRows: Long): Long {
        val selectivities = conditions.map { it.column }.distinct().mapNotNull { column ->
            indexRange(conditions, column)?.let { (lower, upper) ->
                rangeSelectivity(lower, upper, catalog.getColumnStats(tableName, column))
            }
        }
        val sel = selectivities.minOrNull() ?: CostEstimator.DEFAULT_OTHER_SELECTIVITY
        return max(1, (totalRows * sel).toLong())
    }

    private companion object {
        val RANGE_OPERATORS = setOf(
            BinaryOperator.EQ, BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE,
        )
    }
```

import에 `gwanbase.table.ColumnStats`를 추가한다.

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `./gradlew :core:test`
Expected: PASS. `OptimizerIntegrationTest`의 기존 IndexScan 테스트(`id = 500`)도 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt
git commit -m "feat: PlanEnumerator가 범위 조건을 인덱스 경계로 변환해 접근 경로 선택"
```

---

### Task 8: SQL end-to-end 통합 테스트

**Files:**
- Test: `core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1~7 전체. `database.executeSql()` → `ExecuteResult.Selected(rows)`, `ExecuteResult.Explained(planText)`.

- [ ] **Step 1: 통합 테스트 작성**

`OptimizerIntegrationTest.kt`에 추가한다.

```kotlin
    private fun prepareIndexedUsers() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")
    }

    private fun selectIds(sql: String): List<Any?> =
        database.executeSql(sql).shouldBeInstanceOf<ExecuteResult.Selected>().rows.map { it[0] }

    private fun explain(sql: String): String =
        database.executeSql("EXPLAIN $sql").shouldBeInstanceOf<ExecuteResult.Explained>().planText

    @Test
    fun `범위 조건 SELECT가 IndexScan으로 정확한 행을 반환한다 - 단방향`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id > 1095"
        explain(sql) shouldContain "range=(1095, +inf)"
        selectIds(sql) shouldBe listOf(1096, 1097, 1098, 1099, 1100)
    }

    @Test
    fun `범위 조건 SELECT가 IndexScan으로 정확한 행을 반환한다 - 양방향`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id >= 1090 AND id <= 1094"
        explain(sql) shouldContain "range=[1090, 1094]"
        selectIds(sql) shouldBe listOf(1090, 1091, 1092, 1093, 1094)
    }

    @Test
    fun `범위 조건과 다른 컬럼 조건이 함께 있으면 필터로 재검사한다`() {
        prepareIndexedUsers()
        // age = 20 + i % 50 → id 1100은 age 20, 1099는 69, 1098은 68 …
        val sql = "SELECT id FROM users WHERE id > 1095 AND age = 20"
        explain(sql) shouldContain "IndexScan"
        selectIds(sql) shouldBe listOf(1100)
    }

    @Test
    fun `리터럴이 왼쪽인 범위 조건도 IndexScan을 쓴다`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE 4 > id"
        explain(sql) shouldContain "range=(-inf, 4)"
        selectIds(sql) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `모순된 범위 조건은 빈 결과를 반환한다`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id > 1095 AND id < 1090"
        explain(sql) shouldContain "IndexScan"
        selectIds(sql) shouldBe emptyList()
    }

    @Test
    fun `등가 조건 EXPLAIN은 key= 형식으로 출력된다`() {
        prepareIndexedUsers()
        explain("SELECT id FROM users WHERE id = 500") shouldContain "key=500"
    }
```

`selectIds`가 반환하는 값의 타입은 `ExpressionEvaluator.getTupleValue`가 INT32를 `Int`로 돌려주는지 확인한다. `listOf(1096, …)`는 `Int`이므로 `Selected.rows`의 원소가 `Int`여야 한다. 기존 `PlanNode를 Operator로 변환하여 실행 — IndexScan 경로` 테스트가 `rows[0] shouldBe 500`으로 `Int` 비교를 하므로 같다.

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.OptimizerIntegrationTest"`
Expected: PASS. 실패하면 EXPLAIN 텍스트를 출력해 실제 형식을 확인하고 Task 5의 `describeRange()`와 맞춘다. 정렬 순서가 다르면 인덱스 순서(오름차순)와 힙 삽입 순서를 확인한다.

- [ ] **Step 3: 전체 테스트 및 커밋**

Run: `./gradlew :core:test`
Expected: PASS.

```bash
git add core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt
git commit -m "test: 범위 스캔 SQL end-to-end 통합 테스트 추가"
```

---

### Task 9: 문서 갱신

**Files:**
- Modify: `docs/specs/advanced.md` (범위 스캔 항목, 우선순위 표)
- Modify: `CLAUDE.md` (고도화 진행 상황 표)
- Modify: `HANDOFF.md`

- [ ] **Step 1: advanced.md 완료 표시**

1. `#### 범위 스캔 (Range Scan) 🔄` → `#### 범위 스캔 (Range Scan) ✅`
2. `**Gwanbase 구현 (설계)**` → `**Gwanbase 구현**`
3. 우선순위 표 `| 1 | 범위 스캔 🔄 |` → `| 1 | 범위 스캔 ✅ |`
4. 구현하면서 설계와 달라진 점이 있으면 표의 해당 행을 실제 구현에 맞게 고친다. 특히 "조건 매칭" 행의 "등가 경계가 있는 인덱스를 우선, 없으면 범위 경계" 문구는 실제 구현(비용이 가장 낮은 인덱스 선택)에 맞게 다음으로 바꾼다:
   `인덱스마다 경계를 만들어 비용을 계산하고 SeqScan을 포함해 가장 저렴한 계획을 고른다`
5. 표에 행 추가:
   `| 리터럴 판정 | `IntLiteral`, `StringLiteral`, `BoolLiteral`, `FloatLiteral`만 경계 값으로 허용. `NullLiteral`·산술식은 필터에만 남긴다 | PostgreSQL은 volatile 함수·인덱스 테이블 변수가 없는 모든 식을 const로 본다 | `Planner.evaluateLiteral()`이 리터럴만 평가할 수 있다. NULL 경계는 비교가 항상 UNKNOWN이므로 실행기가 빈 결과를 낸다 |`

- [ ] **Step 2: CLAUDE.md 진행 상황 표에 행 추가**

`### 고도화 진행 상황` 표의 마지막 행 뒤에:

```
| Query Optimizer | 범위 스캔 (Range Scan) | `index/KeyRange.kt`, `index/KeySerializer.kt` (`scanBounds`), `execution/IndexScanOperator.kt`, `optimizer/PlanEnumerator.kt` (`collectIndexConditions`, `indexRange`) |
```

- [ ] **Step 3: HANDOFF.md 갱신**

`### 다음 작업: 범위 스캔 (계획 수립 단계)` 섹션을 `### 범위 스캔 완료 (PR #N)`로 바꾸고 핵심 파일과 VARCHAR 버그 수정 사실을 남긴다. `## Next Steps`의 1번을 제거하고 다음 후보(GROUP BY/집계, Extended Query, Group Commit)를 위로 올린다. Gotchas의 VARCHAR 버그 항목은 "수정됨(종단 바이트). 이전 파일의 VARCHAR 인덱스는 재생성 필요"로 바꾼다.

- [ ] **Step 4: 커밋**

```bash
git add docs/specs/advanced.md CLAUDE.md
git commit -m "docs: 범위 스캔 완료 기록과 진행 상황 갱신"
```

(`HANDOFF.md`는 `.gitignore`에 있어 커밋되지 않는다.)

- [ ] **Step 5: PR 생성**

Run: `./gradlew :core:test` 최종 통과 확인 후:

```bash
git push -u origin feat/range-scan
gh pr create --title "feat: 범위 스캔(Range Scan) 인덱스 접근 경로 추가" --body "$(cat <<'EOF'
## 요약
- VARCHAR 인덱스 키 접두사 오매칭 수정 (종단 바이트 0x00). 온디스크 인덱스 포맷 변경.
- `PlanNode.IndexScan`을 하한·상한 경계로 일반화, `<`/`<=`/`>`/`>=` 조건을 B+Tree 범위 스캔으로 실행.
- 재검사(recheck)는 인덱스 조건을 필터에 남기는 방식.
- 선택도: 양방향 범위 지원, 기본값은 PostgreSQL 상수(1/3, 0.005).

## 설계 문서
`docs/specs/advanced.md` → 4. 인덱스 고도화 → 범위 스캔

## 테스트
`./gradlew :core:test` 통과
EOF
)"
```

---

## Self-Review

**Spec coverage**
- 조건 매칭 (AND 체인, 교환, 5개 연산자) → Task 7
- 키 전처리 (같은 방향 첫 번째, 등가 덮어쓰기) → Task 7
- 계획 노드 (Bound, lower/upper) → Task 5
- 바이트 경계 (KeyRange, scanBounds, successor) → Task 3
- B+Tree endKey null → Task 2
- 실행기 (rangeSupplier, 필터 재검사, removeCondition 삭제) → Task 4, 7
- 선택도 (양방향, 1/3, 0.005) → Task 6
- EXPLAIN (key=, range=) → Task 5, 8
- 선행 버그 수정 (종단 바이트, NUL 거부, UNIQUE 회귀) → Task 1
- 문서 (✅, 표, 참고 자료) → Task 9. 참고 자료는 설계 단계에서 이미 기록됨.
- 스펙의 "등가 경계 우선" 문구는 구현(비용 비교)과 다르므로 Task 9 Step 1에서 스펙을 고친다.

**Type consistency**
- `KeyRange(lower: Any?, lowerInclusive, upper: Any?, upperInclusive)` — Task 3 정의, Task 4/5 사용 일치.
- `Bound(value: Expression, inclusive: Boolean)` — Task 5 정의, Task 7 사용 일치.
- `PlanNode.IndexScan(tableName, indexName, indexColumnName, lowerBound, upperBound, filter, estimatedRows, estimatedCost)` — Task 5 정의, Task 5 Step 5 / Task 7 호출 순서 일치.
- `IndexScanOperator(database, tableName, schema, tree, indexColumnIndex, indexColumnType, rangeSupplier, filter, session)` — Task 4 정의, Task 4 Step 4 / Task 5 Step 4 호출 일치.
- `CostEstimator.rangeSelectivity(stats, lower: Long?, upper: Long?)` — Task 6 정의, Task 7 사용 일치.
- `BPlusTree.scan(startKey, endKey: ByteArray?)` — Task 2 정의, Task 3 `scanBounds` 반환 타입 `Pair<ByteArray, ByteArray?>`와 일치.
