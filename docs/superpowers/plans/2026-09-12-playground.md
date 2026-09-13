# Playground 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브라우저에서 Gwanbase에 SQL을 실행해 볼 수 있는 웹 콘솔을 새 Gradle 모듈 `playground/`로 만들고 Fly.io에 배포한다.

**Architecture:** JDK 내장 `com.sun.net.httpserver.HttpServer`가 엔드포인트 4개(`/`, `/query`, `/schema`, `/reset`)를 서빙한다. 쿠키 하나로 방문자를 `DatabaseSession`에 대응시키고, 공유 `Database` 하나를 모두가 쓴다. JSON은 손으로 인코딩하고, 요청 본문은 SQL 텍스트 그대로 받아 파서가 필요 없다.

**Tech Stack:** Kotlin 1.9.22 / JVM 17, Gradle Kotlin DSL(`application` 플러그인), JUnit 5 + Kotest assertions, `java.net.http.HttpClient`(테스트), Docker, Fly.io.

**Spec:** `docs/superpowers/specs/2026-09-12-playground-design.md`

## Global Constraints

- 새 외부 의존성을 추가하지 않는다. `playground`는 `implementation(project(":core"))`만 가진다.
- `core` 변경은 `ConnectionHandler.sqlStateOf`의 `internal` → `public` 하나뿐이다.
- 모든 public 클래스/함수에 한국어 KDoc. 테스트 메서드명은 백틱 한국어.
- 커밋 메시지는 `타입: 한 줄 설명`, 본문 없음. 브랜치는 `feat/playground`(이미 생성됨, 스펙 커밋 포함).
- 테스트는 `@TempDir`만 쓴다. 파일 경로 하드코딩 금지.
- 실행 결과 상한 500행, 요청 본문 64KB, 락 타임아웃 5초, 세션 idle 10분.
- 트랜잭션 상태 I/T/E 상태기계를 플레이그라운드에 두는 코드에는 `ponytail:` 주석으로 `DatabaseSession`으로 옮길 시점을 적는다.

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `settings.gradle.kts` (수정) | `include("playground")` |
| `playground/build.gradle.kts` | kotlin jvm + application, `mainClass = gwanbase.playground.MainKt` |
| `core/.../server/ConnectionHandler.kt:168` (수정) | `sqlStateOf` public |
| `playground/src/main/kotlin/gwanbase/playground/Json.kt` | `Json.encode(Any?)`, `Json.result(...)`, `Json.schema(...)` — 응답 JSON 인코딩 |
| `.../SampleData.kt` | 초기 스키마·행 SQL 목록과 `load(db)` |
| `.../Engine.kt` | `Database` 보유, `reset()` = 파일 삭제 → open → SampleData |
| `.../SessionRegistry.kt` | `PlaygroundSession`(세션 + I/T/E 상태기계, synchronized), `SessionRegistry`(쿠키 ID → 세션, idle 회수) |
| `.../PlaygroundServer.kt` | `HttpServer` 라우팅, 쿠키, reset RW 락, idle 회수 스케줄 |
| `.../Main.kt` | 환경변수 읽고 기동 |
| `playground/src/main/resources/index.html` | 화면 |
| `playground/src/test/kotlin/gwanbase/playground/*Test.kt` | 각 컴포넌트 테스트 |
| `Dockerfile`, `fly.toml` (저장소 루트) | 배포 |
| `README.md`, `CLAUDE.md` (수정) | 링크·구조 반영 |

---

### Task 1: 모듈 스캐폴드 + Json 인코더

**Files:**
- Modify: `settings.gradle.kts`
- Create: `playground/build.gradle.kts`
- Create: `playground/src/main/kotlin/gwanbase/playground/Json.kt`
- Test: `playground/src/test/kotlin/gwanbase/playground/JsonTest.kt`

**Interfaces:**
- Produces: `object Json { fun encode(value: Any?): String }` — `Map<String, *>`→객체, `Iterable<*>`→배열, `String`→따옴표+escape, `Boolean`/`Number`→그대로, `null`→`null`, 그 외→`toString()`을 문자열로.

- [ ] **Step 1: 모듈 등록과 빌드 파일**

`settings.gradle.kts`:
```kotlin
rootProject.name = "gwanbase"

include("core")
include("bench")
include("playground")
```

`playground/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("gwanbase.playground.MainKt")
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`playground/src/test/kotlin/gwanbase/playground/JsonTest.kt`:
```kotlin
package gwanbase.playground

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `기본 타입은 JSON 리터럴로 인코딩된다`() {
        Json.encode(null) shouldBe "null"
        Json.encode(true) shouldBe "true"
        Json.encode(42) shouldBe "42"
        Json.encode(3.5) shouldBe "3.5"
        Json.encode(10_000_000_000L) shouldBe "10000000000"
    }

    @Test
    fun `문자열의 따옴표 역슬래시 개행 제어문자를 escape한다`() {
        Json.encode("a\"b\\c\nd\te") shouldBe "\"a\\\"b\\\\c\\nd\\te\\u0001\""
    }

    @Test
    fun `Map은 객체로 List는 배열로 중첩 인코딩된다`() {
        val value = mapOf("columns" to listOf("id", "name"), "rows" to listOf(listOf(1, "Alice"), listOf(2, null)))
        Json.encode(value) shouldBe """{"columns":["id","name"],"rows":[[1,"Alice"],[2,null]]}"""
    }

    @Test
    fun `알 수 없는 타입은 toString 문자열로 인코딩된다`() {
        Json.encode(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")) shouldBe
            "\"00000000-0000-0000-0000-000000000001\""
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.JsonTest"`
Expected: 컴파일 실패 — `Unresolved reference: Json`

- [ ] **Step 4: 최소 구현**

`playground/src/main/kotlin/gwanbase/playground/Json.kt`:
```kotlin
package gwanbase.playground

/**
 * 의존성 없이 응답 JSON을 만드는 최소 인코더.
 *
 * 요청 본문은 SQL 텍스트를 그대로 받으므로 디코더는 필요 없다.
 * `Map`은 객체, `Iterable`은 배열, 문자열·숫자·불리언·null은 리터럴, 그 외는 `toString()` 문자열이다.
 */
object Json {

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Float -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.JsonTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed

- [ ] **Step 6: 커밋**

```bash
git add settings.gradle.kts playground/build.gradle.kts playground/src
git commit -m "feat: playground 모듈 스캐폴드와 JSON 인코더 추가"
```

---

### Task 2: 결과·스키마 JSON 변환

**Files:**
- Modify: `playground/src/main/kotlin/gwanbase/playground/Json.kt`
- Test: `playground/src/test/kotlin/gwanbase/playground/JsonTest.kt`

**Interfaces:**
- Consumes: `gwanbase.sql.ExecuteResult`(sealed: `Created, Dropped, Inserted, Selected, Updated, Deleted, TransactionStarted, TransactionCommitted, TransactionRolledBack, IndexCreated, IndexDropped, Analyzed, Explained`), `gwanbase.table.Catalog.listTables(): List<TableInfo>`, `Catalog.getIndexesForTable(name): List<IndexInfo>`, `TableInfo.schema.columns: List<Column>`(`name`, `type: DataType`, `nullable`).
- Produces:
  - `Json.result(result: ExecuteResult, txn: Char, maxRows: Int): Map<String, Any?>`
  - `Json.schema(catalog: Catalog): Map<String, Any?>`

- [ ] **Step 1: 실패하는 테스트 추가**

`JsonTest.kt`에 추가:
```kotlin
    @Test
    fun `Selected 결과는 columns rows txn을 담고 maxRows에서 잘린다`() {
        val result = gwanbase.sql.ExecuteResult.Selected(
            columns = listOf("id"),
            rows = listOf(listOf(1), listOf(2), listOf(3)),
        )
        Json.result(result, txn = 'I', maxRows = 2) shouldBe mapOf(
            "kind" to "Selected",
            "columns" to listOf("id"),
            "rows" to listOf(listOf(1), listOf(2)),
            "count" to 3,
            "truncated" to true,
            "txn" to "I",
        )
    }

    @Test
    fun `Updated 결과는 count를 나머지는 message를 담는다`() {
        Json.result(gwanbase.sql.ExecuteResult.Updated(3), 'T', 500) shouldBe
            mapOf("kind" to "Updated", "count" to 3, "txn" to "T")
        Json.result(gwanbase.sql.ExecuteResult.Created("users"), 'I', 500) shouldBe
            mapOf("kind" to "Created", "message" to "CREATE TABLE users", "txn" to "I")
        Json.result(gwanbase.sql.ExecuteResult.TransactionStarted, 'T', 500) shouldBe
            mapOf("kind" to "TransactionStarted", "message" to "BEGIN", "txn" to "T")
    }

    @Test
    fun `Explained 결과는 QUERY PLAN 한 컬럼에 줄 단위 행으로 변환된다`() {
        Json.result(gwanbase.sql.ExecuteResult.Explained("SeqScan(t)\n  Filter"), 'I', 500) shouldBe mapOf(
            "kind" to "Explained",
            "columns" to listOf("QUERY PLAN"),
            "rows" to listOf(listOf("SeqScan(t)"), listOf("  Filter")),
            "count" to 2,
            "truncated" to false,
            "txn" to "I",
        )
    }
```

`JsonTest.kt`에 스키마 테스트 추가 (Database가 필요하므로 `@TempDir` 사용):
```kotlin
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `schema는 테이블별 컬럼과 인덱스를 담는다`() {
        gwanbase.table.Database.open(tempDir.resolve("t.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
            db.executeSql("CREATE INDEX idx_name ON users (name)")

            val schema = Json.schema(db.getCatalog())

            val tables = schema["tables"] as List<*>
            val users = tables.single() as Map<*, *>
            users["name"] shouldBe "users"
            users["columns"] shouldBe listOf(
                mapOf("name" to "id", "type" to "INT32", "nullable" to false),
                mapOf("name" to "name", "type" to "VARCHAR", "nullable" to true),
            )
            val indexes = users["indexes"] as List<*>
            indexes.size shouldBe 2
            indexes.any { (it as Map<*, *>)["name"] == "idx_name" && it["column"] == "name" && it["unique"] == false } shouldBe true
        }
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.JsonTest"`
Expected: 컴파일 실패 — `Unresolved reference: result` / `schema`

- [ ] **Step 3: 구현**

`Json.kt`의 `object Json` 안에 추가 (import: `gwanbase.sql.ExecuteResult`, `gwanbase.table.Catalog`):
```kotlin
    /**
     * 실행 결과를 응답 맵으로 바꾼다. `txn`은 PG 프로토콜 ReadyForQuery 상태(I/T/E)와 같은 의미다.
     * `Selected`/`Explained`는 [maxRows]에서 자르고 `truncated`를 표시한다.
     */
    fun result(result: ExecuteResult, txn: Char, maxRows: Int): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>("kind" to result::class.simpleName)
        when (result) {
            is ExecuteResult.Selected -> base.putRows(result.columns, result.rows, maxRows)
            is ExecuteResult.Explained -> base.putRows(listOf("QUERY PLAN"), result.planText.split("\n").map { listOf(it) }, maxRows)
            is ExecuteResult.Updated -> base["count"] = result.count
            is ExecuteResult.Deleted -> base["count"] = result.count
            is ExecuteResult.Inserted -> base["message"] = "INSERT 1"
            is ExecuteResult.Created -> base["message"] = "CREATE TABLE ${result.tableName}"
            is ExecuteResult.Dropped -> base["message"] = "DROP TABLE ${result.tableName}"
            is ExecuteResult.IndexCreated -> base["message"] = "CREATE INDEX ${result.indexName}"
            is ExecuteResult.IndexDropped -> base["message"] = "DROP INDEX ${result.indexName}"
            is ExecuteResult.Analyzed -> base["message"] = "ANALYZE ${result.tableName} (${result.rowCount} rows)"
            ExecuteResult.TransactionStarted -> base["message"] = "BEGIN"
            ExecuteResult.TransactionCommitted -> base["message"] = "COMMIT"
            ExecuteResult.TransactionRolledBack -> base["message"] = "ROLLBACK"
        }
        base["txn"] = txn.toString()
        return base
    }

    private fun MutableMap<String, Any?>.putRows(columns: List<String>, rows: List<List<Any?>>, maxRows: Int) {
        this["columns"] = columns
        this["rows"] = rows.take(maxRows)
        this["count"] = rows.size
        this["truncated"] = rows.size > maxRows
    }

    /** 사이드바용 스키마: 테이블 → 컬럼(name/type/nullable), 인덱스(name/column/unique). */
    fun schema(catalog: Catalog): Map<String, Any?> = mapOf(
        "tables" to catalog.listTables().map { table ->
            mapOf(
                "name" to table.name,
                "columns" to table.schema.columns.map {
                    mapOf("name" to it.name, "type" to it.type.name, "nullable" to it.nullable)
                },
                "indexes" to catalog.getIndexesForTable(table.name).map {
                    mapOf("name" to it.name, "column" to it.columnName, "unique" to it.unique)
                },
            )
        },
    )
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.JsonTest"`
Expected: 8 tests passed. `when`이 exhaustive하지 않다는 컴파일 오류가 나면 `ExecuteResult` 서브타입 목록을 `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt:12-50`과 대조해 빠진 분기를 추가한다.

- [ ] **Step 5: 커밋**

```bash
git add playground/src
git commit -m "feat: 실행 결과와 카탈로그를 플레이그라운드 응답 JSON으로 변환"
```

---

### Task 3: SampleData + Engine

**Files:**
- Create: `playground/src/main/kotlin/gwanbase/playground/SampleData.kt`
- Create: `playground/src/main/kotlin/gwanbase/playground/Engine.kt`
- Test: `playground/src/test/kotlin/gwanbase/playground/EngineTest.kt`

**Interfaces:**
- Consumes: `gwanbase.table.Database.open(path: Path): Database`, `Database.executeSql(sql): ExecuteResult`, `Database.close()`. WAL 파일은 `path + ".wal"`(`Database.open` 내부 규약, `Database.kt:66`).
- Produces:
  - `object SampleData { val statements: List<String>; fun load(db: Database) }`
  - `class Engine(path: Path) : AutoCloseable { val database: Database; fun reset() }`

- [ ] **Step 1: 실패하는 테스트 작성**

`playground/src/test/kotlin/gwanbase/playground/EngineTest.kt`:
```kotlin
package gwanbase.playground

import gwanbase.sql.ExecuteResult
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EngineTest {

    @TempDir
    lateinit var tempDir: Path

    private fun count(engine: Engine, table: String): Int {
        val r = engine.database.executeSql("SELECT * FROM $table") as ExecuteResult.Selected
        return r.rows.size
    }

    @Test
    fun `생성 시 샘플 스키마와 행이 적재된다`() {
        Engine(tempDir.resolve("pg.db")).use { engine ->
            engine.database.getCatalog().listTables().map { it.name } shouldContainExactlyInAnyOrder listOf("users", "orders")
            count(engine, "users") shouldBe 5
            count(engine, "orders") shouldBe 8
            engine.database.getCatalog().getIndexesForTable("orders").any { it.name == "idx_orders_user" } shouldBe true
        }
    }

    @Test
    fun `reset은 변경을 버리고 샘플 상태로 되돌린다`() {
        Engine(tempDir.resolve("pg.db")).use { engine ->
            engine.database.executeSql("DROP TABLE orders")
            engine.database.executeSql("INSERT INTO users (id, name, age) VALUES (99, 'Zed', 1)")

            engine.reset()

            engine.database.getCatalog().listTables().map { it.name } shouldContainExactlyInAnyOrder listOf("users", "orders")
            count(engine, "users") shouldBe 5
        }
    }

    @Test
    fun `이전 실행이 남긴 DB 파일이 있어도 생성 시 새로 시작한다`() {
        val path = tempDir.resolve("pg.db")
        Engine(path).use { it.database.executeSql("INSERT INTO users (id, name, age) VALUES (99, 'Zed', 1)") }

        Engine(path).use { engine -> count(engine, "users") shouldBe 5 }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.EngineTest"`
Expected: 컴파일 실패 — `Unresolved reference: Engine`

- [ ] **Step 3: 구현**

`SampleData.kt`:
```kotlin
package gwanbase.playground

import gwanbase.table.Database

/**
 * 플레이그라운드 초기 데이터.
 *
 * PRIMARY KEY(유일 인덱스), FOREIGN KEY, 보조 인덱스를 하나씩 넣어 예제 버튼이
 * 23505/23503 에러와 IndexScan을 바로 보여줄 수 있게 한다.
 */
object SampleData {

    val statements: List<String> = listOf(
        "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), age INT)",
        "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id), amount INT)",
        "CREATE INDEX idx_orders_user ON orders (user_id)",
        "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)",
        "INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)",
        "INSERT INTO users (id, name, age) VALUES (3, 'Carol', 41)",
        "INSERT INTO users (id, name, age) VALUES (4, 'Dave', 35)",
        "INSERT INTO users (id, name, age) VALUES (5, 'Eve', 28)",
        "INSERT INTO orders (id, user_id, amount) VALUES (1, 1, 120)",
        "INSERT INTO orders (id, user_id, amount) VALUES (2, 1, 80)",
        "INSERT INTO orders (id, user_id, amount) VALUES (3, 2, 300)",
        "INSERT INTO orders (id, user_id, amount) VALUES (4, 3, 45)",
        "INSERT INTO orders (id, user_id, amount) VALUES (5, 3, 60)",
        "INSERT INTO orders (id, user_id, amount) VALUES (6, 3, 15)",
        "INSERT INTO orders (id, user_id, amount) VALUES (7, 4, 500)",
        "INSERT INTO orders (id, user_id, amount) VALUES (8, 5, 210)",
        "ANALYZE users",
        "ANALYZE orders",
    )

    /** 빈 데이터베이스에 샘플 스키마와 행을 적재한다. */
    fun load(db: Database) {
        for (sql in statements) db.executeSql(sql)
    }
}
```

`Engine.kt`:
```kotlin
package gwanbase.playground

import gwanbase.table.Database
import java.nio.file.Files
import java.nio.file.Path

/**
 * 플레이그라운드가 공유하는 [Database] 하나를 보유하고 초기화한다.
 *
 * 영속성은 의도적으로 없다. 생성·[reset] 시 DB 파일과 WAL을 지우고 새로 열어
 * [SampleData]를 적재한다. 누가 `DROP TABLE`을 해도 "초기화" 한 번으로 돌아온다.
 *
 * @param path DB 파일 경로. WAL은 `Database.open` 규약대로 `<path>.wal`에 생긴다.
 */
class Engine(private val path: Path) : AutoCloseable {

    @Volatile
    var database: Database = openFresh()
        private set

    /** 현재 DB를 닫고 파일을 지운 뒤 샘플 상태로 다시 연다. 호출자가 다른 요청을 막아야 한다. */
    fun reset() {
        database.close()
        database = openFresh()
    }

    private fun openFresh(): Database {
        Files.deleteIfExists(path)
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".wal"))
        return Database.open(path).also { SampleData.load(it) }
    }

    override fun close() = database.close()
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.EngineTest"`
Expected: 3 tests passed

- [ ] **Step 5: 커밋**

```bash
git add playground/src
git commit -m "feat: 플레이그라운드 샘플 데이터와 초기화 가능한 Engine 추가"
```

---

### Task 4: PlaygroundSession — 트랜잭션 상태기계

**Files:**
- Create: `playground/src/main/kotlin/gwanbase/playground/SessionRegistry.kt`
- Test: `playground/src/test/kotlin/gwanbase/playground/PlaygroundSessionTest.kt`

**Interfaces:**
- Consumes: `Database.createSession(): DatabaseSession`, `DatabaseSession.executeSql(sql): ExecuteResult`, `DatabaseSession.close()`, `DatabaseSession.lockTimeoutMillis: Long`.
- Produces:
  - `class PlaygroundSession(session: DatabaseSession) : AutoCloseable { val txnStatus: Char; @Volatile var lastUsedAt: Long; fun execute(sql: String): ExecuteResult }`
  - `class TransactionAbortedException : RuntimeException` — SQLSTATE 25P02에 대응.

- [ ] **Step 1: 실패하는 테스트 작성**

`playground/src/test/kotlin/gwanbase/playground/PlaygroundSessionTest.kt`:
```kotlin
package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlaygroundSessionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var session: PlaygroundSession

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("t.db"))
        db.executeSql("CREATE TABLE t (id INT PRIMARY KEY)")
        session = PlaygroundSession(db.createSession())
    }

    @AfterEach
    fun tearDown() {
        session.close()
        db.close()
    }

    @Test
    fun `auto-commit 상태는 I이고 BEGIN 후 T COMMIT 후 다시 I다`() {
        session.txnStatus shouldBe 'I'
        session.execute("BEGIN")
        session.txnStatus shouldBe 'T'
        session.execute("INSERT INTO t (id) VALUES (1)")
        session.txnStatus shouldBe 'T'
        session.execute("COMMIT")
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `트랜잭션 중 바인딩 오류가 나면 E가 되고 ROLLBACK 전까지 명령을 거부한다`() {
        session.execute("BEGIN")
        assertThrows<gwanbase.sql.BindException> { session.execute("SELECT * FROM nope") }
        session.txnStatus shouldBe 'E'

        assertThrows<TransactionAbortedException> { session.execute("SELECT * FROM t") }
        session.txnStatus shouldBe 'E'

        session.execute("ROLLBACK") shouldBe ExecuteResult.TransactionRolledBack
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `트랜잭션 중 실행 오류(UNIQUE 위반)가 나도 ROLLBACK으로 I로 돌아온다`() {
        session.execute("INSERT INTO t (id) VALUES (1)")
        session.execute("BEGIN")
        assertThrows<gwanbase.table.UniqueViolationException> { session.execute("INSERT INTO t (id) VALUES (1)") }
        session.txnStatus shouldBe 'E'

        session.execute("ROLLBACK") shouldBe ExecuteResult.TransactionRolledBack
        session.txnStatus shouldBe 'I'
        session.execute("SELECT * FROM t") // 정상 실행되어야 한다
    }

    @Test
    fun `auto-commit 오류는 상태를 바꾸지 않는다`() {
        assertThrows<gwanbase.sql.BindException> { session.execute("SELECT * FROM nope") }
        session.txnStatus shouldBe 'I'
    }

    @Test
    fun `execute는 lastUsedAt을 갱신한다`() {
        session.lastUsedAt = 0L
        session.execute("SELECT * FROM t")
        (session.lastUsedAt > 0L) shouldBe true
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.PlaygroundSessionTest"`
Expected: 컴파일 실패 — `Unresolved reference: PlaygroundSession`

- [ ] **Step 3: 구현**

`SessionRegistry.kt` (이 Task에서는 세션 래퍼만; 레지스트리는 Task 5에서 같은 파일에 추가):
```kotlin
package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.txn.DatabaseSession

/** 실패한 트랜잭션 안에서 ROLLBACK 이외의 명령을 보냈을 때. PostgreSQL SQLSTATE 25P02. */
class TransactionAbortedException : RuntimeException(
    "current transaction is aborted, commands ignored until end of transaction block"
)

/**
 * 방문자 한 명의 [DatabaseSession]과 트랜잭션 상태(I/T/E)를 묶는다.
 *
 * `DatabaseSession`은 스레드 안전하지 않으므로 같은 방문자의 동시 요청은 이 객체로 직렬화한다.
 *
 * 상태기계는 `gwanbase.server.ConnectionHandler`와 같다 — 결과 타입으로 T/I를 갱신하고,
 * 트랜잭션 중 예외가 나면 E로 간다. E에서는 ROLLBACK만 받는다.
 * ponytail: 이 상태기계는 세션 계층(`DatabaseSession`)에 FAILED state가 들어오면 그쪽으로 옮기고
 * 여기와 ConnectionHandler 양쪽에서 제거한다.
 */
class PlaygroundSession(private val session: DatabaseSession) : AutoCloseable {

    @Volatile
    var lastUsedAt: Long = System.currentTimeMillis()

    private var inTransaction = false
    private var txnFailed = false

    /** PG 프로토콜 ReadyForQuery 상태와 같은 의미: I(idle) / T(트랜잭션 중) / E(실패한 트랜잭션). */
    val txnStatus: Char
        get() = when {
            txnFailed -> 'E'
            inTransaction -> 'T'
            else -> 'I'
        }

    /**
     * SQL 한 문장을 실행한다.
     * @throws TransactionAbortedException 실패한 트랜잭션 안에서 ROLLBACK이 아닌 명령을 보낸 경우
     */
    @Synchronized
    fun execute(sql: String): ExecuteResult {
        lastUsedAt = System.currentTimeMillis()
        if (txnFailed) {
            if (!isRollback(sql)) throw TransactionAbortedException()
            // ponytail: 실행 단계 오류(UNIQUE 위반 등)는 DatabaseSession이 이미 abort해 활성 트랜잭션이
            // 없고, 이때 ROLLBACK은 IllegalStateException을 던진다. 바인딩 오류는 abort되지 않아
            // ROLLBACK이 정상 동작한다. 두 경우 모두 방문자에게는 ROLLBACK 성공으로 보여야 한다.
            runCatching { session.executeSql(sql) }
            inTransaction = false
            txnFailed = false
            return ExecuteResult.TransactionRolledBack
        }
        try {
            val result = session.executeSql(sql)
            when (result) {
                ExecuteResult.TransactionStarted -> inTransaction = true
                ExecuteResult.TransactionCommitted, ExecuteResult.TransactionRolledBack -> inTransaction = false
                else -> {}
            }
            return result
        } catch (e: Exception) {
            if (inTransaction) txnFailed = true
            throw e
        }
    }

    private fun isRollback(sql: String): Boolean =
        sql.trim().removeSuffix(";").trim().equals("ROLLBACK", ignoreCase = true)

    @Synchronized
    override fun close() = session.close()
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.PlaygroundSessionTest"`
Expected: 5 tests passed

- [ ] **Step 5: 커밋**

```bash
git add playground/src
git commit -m "feat: 플레이그라운드 세션 래퍼와 트랜잭션 상태기계(I/T/E) 추가"
```

---

### Task 5: SessionRegistry — 쿠키 ID → 세션, idle 회수

**Files:**
- Modify: `playground/src/main/kotlin/gwanbase/playground/SessionRegistry.kt`
- Test: `playground/src/test/kotlin/gwanbase/playground/SessionRegistryTest.kt`

**Interfaces:**
- Consumes: Task 4의 `PlaygroundSession`, `Database.createSession()`, `DatabaseSession.lockTimeoutMillis`.
- Produces: `class SessionRegistry(database: () -> Database, lockTimeoutMillis: Long, idleMillis: Long) { fun acquire(id: String?): Pair<String, PlaygroundSession>; fun evictIdle(now: Long = System.currentTimeMillis()): Int; fun closeAll(); val size: Int }`

- [ ] **Step 1: 실패하는 테스트 작성**

`playground/src/test/kotlin/gwanbase/playground/SessionRegistryTest.kt`:
```kotlin
package gwanbase.playground

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var registry: SessionRegistry

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("t.db"))
        registry = SessionRegistry({ db }, lockTimeoutMillis = 100, idleMillis = 1_000)
    }

    @AfterEach
    fun tearDown() {
        registry.closeAll()
        db.close()
    }

    @Test
    fun `ID가 없으면 새 세션을 만들고 같은 ID로 다시 요청하면 같은 세션을 준다`() {
        val (id, first) = registry.acquire(null)
        val (again, second) = registry.acquire(id)

        again shouldBe id
        second shouldBe first
        registry.size shouldBe 1
    }

    @Test
    fun `모르는 ID로 요청하면 새 ID와 새 세션을 준다`() {
        val (id, _) = registry.acquire("stale-cookie")
        id shouldNotBe "stale-cookie"
        registry.size shouldBe 1
    }

    @Test
    fun `idle 시간을 넘긴 세션만 회수한다`() {
        val (oldId, old) = registry.acquire(null)
        val (freshId, fresh) = registry.acquire(null)
        old.lastUsedAt = 0L
        fresh.lastUsedAt = 10_000L

        registry.evictIdle(now = 5_000L) shouldBe 1

        registry.size shouldBe 1
        registry.acquire(freshId).second shouldBe fresh
        registry.acquire(oldId).first shouldNotBe oldId
    }

    @Test
    fun `회수된 세션은 닫혀서 미커밋 트랜잭션의 잠금이 풀린다`() {
        db.executeSql("CREATE TABLE t (id INT PRIMARY KEY)")
        db.executeSql("INSERT INTO t (id) VALUES (1)")
        val (_, holder) = registry.acquire(null)
        holder.execute("BEGIN")
        holder.execute("UPDATE t SET id = 1 WHERE id = 1")
        holder.lastUsedAt = 0L

        registry.evictIdle(now = 5_000L)

        val (_, other) = registry.acquire(null)
        other.execute("UPDATE t SET id = 1 WHERE id = 1") // 잠금이 남아 있으면 100ms 후 LockTimeoutException
    }

    @Test
    fun `closeAll은 모든 세션을 닫고 비운다`() {
        registry.acquire(null)
        registry.acquire(null)
        registry.closeAll()
        registry.size shouldBe 0
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.SessionRegistryTest"`
Expected: 컴파일 실패 — `Unresolved reference: SessionRegistry`

- [ ] **Step 3: 구현**

`SessionRegistry.kt` 끝에 추가 (import: `gwanbase.table.Database`, `java.util.UUID`, `java.util.concurrent.ConcurrentHashMap`):
```kotlin
/**
 * 쿠키 ID → [PlaygroundSession] 매핑.
 *
 * 브라우저를 닫고 떠난 방문자가 미커밋 트랜잭션의 잠금을 영원히 쥐지 않도록
 * [evictIdle]로 idle 세션을 닫는다. 공유 DB 모델에서 가장 중요한 안전장치다.
 *
 * @param database 현재 Database를 돌려주는 함수. reset 후 새 인스턴스를 받기 위해 지연 참조한다.
 * @param lockTimeoutMillis 새 세션마다 설정할 잠금 대기 상한
 * @param idleMillis 이 시간 동안 요청이 없으면 회수 대상
 */
class SessionRegistry(
    private val database: () -> Database,
    private val lockTimeoutMillis: Long,
    private val idleMillis: Long,
) {

    private val sessions = ConcurrentHashMap<String, PlaygroundSession>()

    val size: Int get() = sessions.size

    /** [id]의 세션을 돌려주고, 없거나 회수됐으면 새로 만든다. first는 클라이언트에 줄 세션 ID다. */
    fun acquire(id: String?): Pair<String, PlaygroundSession> {
        if (id != null) sessions[id]?.let { return id to it }
        val newId = UUID.randomUUID().toString()
        val dbSession = database().createSession().apply { lockTimeoutMillis = this@SessionRegistry.lockTimeoutMillis }
        val session = PlaygroundSession(dbSession)
        sessions[newId] = session
        return newId to session
    }

    /** [now] 기준 idle 초과 세션을 닫고 제거한다. 닫은 개수를 반환한다. */
    fun evictIdle(now: Long = System.currentTimeMillis()): Int {
        var closed = 0
        for ((id, session) in sessions) {
            if (now - session.lastUsedAt > idleMillis && sessions.remove(id, session)) {
                session.close()
                closed++
            }
        }
        return closed
    }

    /** 모든 세션을 닫는다. reset 전에 호출한다. */
    fun closeAll() {
        val all = sessions.values.toList()
        sessions.clear()
        all.forEach { it.close() }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.SessionRegistryTest"`
Expected: 5 tests passed

- [ ] **Step 5: 커밋**

```bash
git add playground/src
git commit -m "feat: 쿠키 ID 기반 SessionRegistry와 idle 세션 회수 추가"
```

---

### Task 6: PlaygroundServer — `/query`, `/schema`, `/reset`

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt:168` (`internal fun sqlStateOf` → `fun sqlStateOf`)
- Create: `playground/src/main/kotlin/gwanbase/playground/PlaygroundServer.kt`
- Create: `playground/src/main/resources/index.html` (이 Task에서는 `<title>Gwanbase Playground</title>`만 든 자리표시 파일; Task 7에서 채운다)
- Test: `playground/src/test/kotlin/gwanbase/playground/PlaygroundServerTest.kt`

**Interfaces:**
- Consumes: Task 2 `Json.result/schema/encode`, Task 3 `Engine`, Task 5 `SessionRegistry`, Task 4 `TransactionAbortedException`, `gwanbase.server.ConnectionHandler.sqlStateOf(e: Throwable): String`.
- Produces: `class PlaygroundServer(engine: Engine, port: Int = 8080, lockTimeoutMillis: Long = 5_000, idleMillis: Long = 600_000) : AutoCloseable { val port: Int; fun start() }`
- HTTP 계약 (스펙 "엔드포인트" 표): `POST /query` 본문 = SQL 텍스트, 성공 200 / SQL 오류 400 `{"error","sqlState","txn"}`, 쿠키 `gb_session`, 404/405/413.

- [ ] **Step 1: core 가시성 변경**

`core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt:168`의 `internal fun sqlStateOf(e: Throwable): String`을 `fun sqlStateOf(e: Throwable): String`으로 바꾼다. KDoc은 그대로 둔다.

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 실패하는 테스트 작성**

`playground/src/test/kotlin/gwanbase/playground/PlaygroundServerTest.kt`:
```kotlin
package gwanbase.playground

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class PlaygroundServerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var engine: Engine
    private lateinit var server: PlaygroundServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        engine = Engine(tempDir.resolve("pg.db"))
        server = PlaygroundServer(engine, port = 0, lockTimeoutMillis = 300)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
        engine.close()
    }

    private fun url(path: String) = URI("http://localhost:${server.port}$path")

    private fun query(sql: String, cookie: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(url("/query")).POST(HttpRequest.BodyPublishers.ofString(sql))
        if (cookie != null) builder.header("Cookie", cookie)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(url(path)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())

    /** 응답의 Set-Cookie에서 다음 요청에 보낼 `gb_session=...` 부분만 꺼낸다. */
    private fun HttpResponse<String>.sessionCookie(): String =
        headers().firstValue("Set-Cookie").get().substringBefore(";")

    @Test
    fun `SELECT는 columns와 rows를 담아 200으로 응답한다`() {
        val res = query("SELECT name FROM users WHERE id = 1")
        res.statusCode() shouldBe 200
        res.body() shouldContain """"columns":["name"]"""
        res.body() shouldContain """"rows":[["Alice"]]"""
        res.body() shouldContain """"txn":"I""""
    }

    @Test
    fun `존재하지 않는 테이블은 400과 sqlState 42000으로 응답한다`() {
        val res = query("SELECT * FROM nope")
        res.statusCode() shouldBe 400
        res.body() shouldContain """"sqlState":"42000""""
    }

    @Test
    fun `첫 응답은 gb_session 쿠키를 발급하고 같은 쿠키로 트랜잭션이 이어진다`() {
        val begin = query("BEGIN")
        val cookie = begin.sessionCookie()
        cookie shouldContain "gb_session="
        begin.body() shouldContain """"txn":"T""""

        query("SELECT * FROM nope", cookie).body() shouldContain """"txn":"E""""
        query("SELECT * FROM users", cookie).body() shouldContain """"sqlState":"25P02""""
        query("ROLLBACK", cookie).body() shouldContain """"txn":"I""""
    }

    @Test
    fun `다른 방문자의 미커밋 행을 갱신하면 락 타임아웃 55P03을 받는다`() {
        val a = query("BEGIN").sessionCookie()
        query("UPDATE users SET age = 31 WHERE id = 1", a)

        val res = query("UPDATE users SET age = 32 WHERE id = 1")
        res.statusCode() shouldBe 400
        res.body() shouldContain """"sqlState":"55P03""""
    }

    @Test
    fun `schema는 샘플 테이블과 인덱스를 담는다`() {
        val res = get("/schema")
        res.statusCode() shouldBe 200
        res.body() shouldContain """"name":"orders""""
        res.body() shouldContain """"name":"idx_orders_user""""
    }

    @Test
    fun `reset 후 schema가 샘플 상태로 돌아오고 기존 세션의 트랜잭션은 사라진다`() {
        val cookie = query("BEGIN").sessionCookie()
        query("DROP TABLE orders", cookie)

        post("/reset").statusCode() shouldBe 200

        get("/schema").body() shouldContain """"name":"orders""""
        query("SELECT * FROM users", cookie).body() shouldContain """"txn":"I""""
    }

    @Test
    fun `500행을 넘는 SELECT는 truncated true로 잘린다`() {
        engine.database.executeSql("CREATE TABLE big (n INT)")
        for (i in 1..501) engine.database.executeSql("INSERT INTO big (n) VALUES ($i)")

        val body = query("SELECT * FROM big").body()
        body shouldContain """"truncated":true"""
        body shouldContain """"count":501"""
    }

    @Test
    fun `모르는 경로는 404 잘못된 메서드는 405 큰 본문은 413이다`() {
        get("/nope").statusCode() shouldBe 404
        get("/query").statusCode() shouldBe 405
        query("SELECT * FROM users -- " + "x".repeat(65 * 1024)).statusCode() shouldBe 413
    }

    @Test
    fun `루트는 HTML을 돌려준다`() {
        val res = get("/")
        res.statusCode() shouldBe 200
        res.headers().firstValue("Content-Type").get() shouldContain "text/html"
        res.body() shouldContain "Gwanbase Playground"
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.PlaygroundServerTest"`
Expected: 컴파일 실패 — `Unresolved reference: PlaygroundServer`

- [ ] **Step 4: 구현**

`playground/src/main/resources/index.html` (자리표시):
```html
<!doctype html>
<title>Gwanbase Playground</title>
```

`PlaygroundServer.kt`:
```kotlin
package gwanbase.playground

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gwanbase.server.ConnectionHandler
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

/**
 * 플레이그라운드 HTTP 서버.
 *
 * - `GET /` 화면, `POST /query` SQL 실행(본문 = SQL 텍스트), `GET /schema` 카탈로그, `POST /reset` 초기화.
 * - 쿠키 `gb_session`으로 방문자를 [SessionRegistry]의 세션에 대응시킨다.
 * - `/reset`은 write 락, 나머지는 read 락을 잡아 초기화 중 요청이 끼어들지 않게 한다.
 * - 1분마다 idle 세션을 회수한다.
 *
 * @param port 0이면 임의 포트. 실제 포트는 [port] 프로퍼티로 읽는다.
 */
class PlaygroundServer(
    private val engine: Engine,
    port: Int = 8080,
    lockTimeoutMillis: Long = 5_000,
    idleMillis: Long = 10 * 60_000,
) : AutoCloseable {

    private val sessions = SessionRegistry({ engine.database }, lockTimeoutMillis, idleMillis)
    private val resetLock = ReentrantReadWriteLock()
    private val http: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val evictor = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    private val indexHtml: String = PlaygroundServer::class.java.getResource("/index.html")!!.readText()

    val port: Int get() = http.address.port

    init {
        http.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        http.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Exception) {
                logger.error(e) { "요청 처리 실패: ${exchange.requestMethod} ${exchange.requestURI}" }
                exchange.respond(500, Json.encode(mapOf("error" to (e.message ?: "internal error"))))
            }
        }
    }

    fun start() {
        http.start()
        evictor.scheduleAtFixedRate({ resetLock.read { sessions.evictIdle() } }, 1, 1, TimeUnit.MINUTES)
        logger.info { "Playground 시작: port=$port" }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/" && method == "GET" -> ex.respond(200, indexHtml, "text/html; charset=utf-8")
            path == "/query" && method == "POST" -> query(ex)
            path == "/schema" && method == "GET" ->
                resetLock.read { ex.respond(200, Json.encode(Json.schema(engine.database.getCatalog()))) }
            path == "/reset" && method == "POST" -> resetLock.write {
                sessions.closeAll()
                engine.reset()
                ex.respond(200, """{"ok":true}""")
            }
            path in KNOWN_PATHS -> ex.respond(405, Json.encode(mapOf("error" to "method not allowed")))
            else -> ex.respond(404, Json.encode(mapOf("error" to "not found")))
        }
    }

    private fun query(ex: HttpExchange) {
        val body = ex.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        if (body.size > MAX_BODY_BYTES) {
            ex.respond(413, Json.encode(mapOf("error" to "본문이 ${MAX_BODY_BYTES}바이트를 넘는다")))
            return
        }
        val sql = String(body, Charsets.UTF_8)
        resetLock.read {
            val (id, session) = sessions.acquire(ex.cookie(COOKIE_NAME))
            ex.responseHeaders.add("Set-Cookie", "$COOKIE_NAME=$id; Path=/; HttpOnly; SameSite=Lax")
            try {
                val result = session.execute(sql)
                ex.respond(200, Json.encode(Json.result(result, session.txnStatus, MAX_ROWS)))
            } catch (e: Exception) {
                val sqlState = if (e is TransactionAbortedException) "25P02" else ConnectionHandler.sqlStateOf(e)
                ex.respond(400, Json.encode(mapOf(
                    "error" to (e.message ?: e::class.simpleName),
                    "sqlState" to sqlState,
                    "txn" to session.txnStatus.toString(),
                )))
            }
        }
    }

    private fun HttpExchange.cookie(name: String): String? =
        requestHeaders.getFirst("Cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() {
        evictor.shutdownNow()
        http.stop(0)
        sessions.closeAll()
    }

    companion object {
        const val COOKIE_NAME = "gb_session"
        const val MAX_ROWS = 500
        const val MAX_BODY_BYTES = 64 * 1024
        private val KNOWN_PATHS = setOf("/", "/query", "/schema", "/reset")
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.PlaygroundServerTest"`
Expected: 9 tests passed. 55P03 테스트는 락 타임아웃 300ms를 기다리므로 다른 테스트보다 느리다.

- [ ] **Step 6: core 테스트 회귀 확인**

Run: `./gradlew :core:test`
Expected: 전부 통과 (`sqlStateOf` 가시성 변경은 동작에 영향 없음)

- [ ] **Step 7: 커밋 (core 변경과 playground 서버를 분리)**

```bash
git add core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt
git commit -m "refactor: sqlStateOf를 다른 모듈에서 재사용할 수 있게 public으로 변경"
git add playground/src
git commit -m "feat: 플레이그라운드 HTTP 서버(/query, /schema, /reset) 추가"
```

---

### Task 7: index.html 화면

**Files:**
- Modify: `playground/src/main/resources/index.html` (자리표시 → 전체 화면)
- Test: `playground/src/test/kotlin/gwanbase/playground/PlaygroundServerTest.kt` (`루트는 HTML을 돌려준다` 테스트에 단언 추가)

**Interfaces:**
- Consumes: Task 6의 HTTP 계약. 응답 필드: 성공 `kind, columns?, rows?, count?, message?, truncated?, txn` / 실패 `error, sqlState, txn`. `/schema`의 `tables[].{name, columns[].{name,type,nullable}, indexes[].{name,column,unique}}`.

- [ ] **Step 1: 테스트 단언 추가**

`PlaygroundServerTest.kt`의 `루트는 HTML을 돌려준다`에 추가:
```kotlin
        res.body() shouldContain "id=\"sql\""
        res.body() shouldContain "fetch('/query'"
        res.body() shouldContain "fetch('/schema')"
        res.body() shouldContain "fetch('/reset'"
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :playground:test --tests "gwanbase.playground.PlaygroundServerTest.루트는*"`
Expected: FAIL — `id="sql"` 미포함

- [ ] **Step 3: 화면 작성**

`playground/src/main/resources/index.html` 전체 교체:
```html
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Gwanbase Playground</title>
<style>
  :root { --bg:#f7f7f5; --panel:#fff; --line:#e2e2de; --text:#1f1f1f; --muted:#6b6b66; --accent:#2f6fdb; --err:#b3261e; }
  * { box-sizing: border-box; }
  body { margin:0; font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color:var(--text); background:var(--bg); }
  header { padding:12px 20px; border-bottom:1px solid var(--line); background:var(--panel); display:flex; gap:12px; align-items:baseline; }
  header h1 { font-size:16px; margin:0; }
  header a, header span { color:var(--muted); font-size:13px; }
  main { display:grid; grid-template-columns: 260px 1fr; min-height: calc(100vh - 49px); }
  aside { border-right:1px solid var(--line); background:var(--panel); padding:16px; overflow:auto; }
  aside h2 { font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--muted); margin:0 0 8px; }
  .table { margin-bottom:14px; }
  .table b { display:block; }
  .table ul { list-style:none; margin:2px 0 0; padding:0 0 0 10px; color:var(--muted); font-size:13px; }
  .table .idx { color:var(--accent); }
  section { padding:16px 20px; display:flex; flex-direction:column; gap:12px; min-width:0; }
  textarea { width:100%; height:110px; font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; padding:10px; border:1px solid var(--line); border-radius:6px; resize:vertical; }
  .row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
  button { font:inherit; padding:6px 12px; border:1px solid var(--line); border-radius:6px; background:var(--panel); cursor:pointer; }
  button.primary { background:var(--accent); color:#fff; border-color:var(--accent); }
  button.danger { color:var(--err); }
  .examples button { font-size:12px; padding:4px 10px; }
  #history { display:flex; flex-direction:column; gap:10px; }
  .entry { background:var(--panel); border:1px solid var(--line); border-radius:6px; padding:10px 12px; }
  .entry pre { margin:0 0 8px; font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; white-space:pre-wrap; }
  .entry .meta { font-size:12px; color:var(--muted); display:flex; gap:8px; align-items:center; }
  .badge { font: 11px/1 ui-monospace, monospace; padding:3px 6px; border-radius:4px; background:#eee; }
  .badge.T { background:#dbeafe; color:#1e40af; } .badge.E { background:#fee2e2; color:#991b1b; }
  .error { color:var(--err); }
  table.result { border-collapse:collapse; font-size:13px; margin-top:6px; max-width:100%; display:block; overflow:auto; }
  table.result th, table.result td { border:1px solid var(--line); padding:3px 8px; text-align:left; white-space:pre; }
  table.result th { background:var(--bg); }
  td.null { color:var(--muted); font-style:italic; }
  @media (max-width: 720px) { main { grid-template-columns: 1fr; } aside { border-right:0; border-bottom:1px solid var(--line); } }
</style>
</head>
<body>
<header>
  <h1>Gwanbase Playground</h1>
  <span>Kotlin으로 밑바닥부터 만든 RDB · 모든 방문자가 같은 DB를 공유합니다</span>
  <a href="https://github.com/khkim6040/Gwanbase" target="_blank" rel="noopener">GitHub</a>
</header>
<main>
  <aside>
    <h2>스키마</h2>
    <div id="schema"></div>
    <button class="danger" id="reset">초기화</button>
  </aside>
  <section>
    <textarea id="sql" spellcheck="false" placeholder="SELECT * FROM users;  (Ctrl/Cmd+Enter로 실행)"></textarea>
    <div class="row">
      <button class="primary" id="run">실행</button>
      <span class="badge" id="txn">I</span>
      <span class="examples row" id="examples"></span>
    </div>
    <div id="history"></div>
  </section>
</main>
<script>
const EXAMPLES = [
  ['SELECT', 'SELECT * FROM users WHERE age > 28 ORDER BY age'],
  ['JOIN', 'SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id WHERE o.amount > 100'],
  ['EXPLAIN', 'EXPLAIN SELECT * FROM orders WHERE user_id = 3'],
  ['INSERT', "INSERT INTO users (id, name, age) VALUES (6, 'Frank', 52)"],
  ['UPDATE', 'UPDATE orders SET amount = amount + 10 WHERE user_id = 1'],
  ['BEGIN…ROLLBACK', 'BEGIN'],
  ['UNIQUE 위반', "INSERT INTO users (id, name, age) VALUES (1, 'Dup', 1)"],
  ['FK 위반', 'INSERT INTO orders (id, user_id, amount) VALUES (9, 999, 1)'],
];
const $ = (id) => document.getElementById(id);
const sqlBox = $('sql'), history = $('history'), txnBadge = $('txn');

for (const [label, sql] of EXAMPLES) {
  const b = document.createElement('button');
  b.textContent = label;
  b.onclick = () => { sqlBox.value = sql; sqlBox.focus(); };
  $('examples').appendChild(b);
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function renderTable(columns, rows) {
  const t = el('table', 'result');
  const head = t.createTHead().insertRow();
  for (const c of columns) head.appendChild(el('th', '', c));
  const body = t.createTBody();
  for (const r of rows) {
    const tr = body.insertRow();
    for (const v of r) {
      const td = el('td', v === null ? 'null' : '', v === null ? 'NULL' : String(v));
      tr.appendChild(td);
    }
  }
  return t;
}

function setTxn(txn) { txnBadge.textContent = txn; txnBadge.className = 'badge ' + txn; }

async function loadSchema() {
  const res = await fetch('/schema');
  const data = await res.json();
  const box = $('schema');
  box.textContent = '';
  for (const t of data.tables) {
    const d = el('div', 'table');
    d.appendChild(el('b', '', t.name));
    const ul = el('ul');
    for (const c of t.columns) ul.appendChild(el('li', '', `${c.name} ${c.type}${c.nullable ? '' : ' NOT NULL'}`));
    for (const i of t.indexes) ul.appendChild(el('li', 'idx', `${i.unique ? 'UNIQUE ' : ''}INDEX ${i.name} (${i.column})`));
    d.appendChild(ul);
    box.appendChild(d);
  }
}

async function run() {
  const sql = sqlBox.value.trim();
  if (!sql) return;
  const res = await fetch('/query', { method: 'POST', body: sql });
  const data = await res.json();
  const entry = el('div', 'entry');
  entry.appendChild(el('pre', '', sql));
  const meta = el('div', 'meta');
  if (data.error) {
    meta.appendChild(el('span', 'error', `ERROR [${data.sqlState}] ${data.error}`));
  } else if (data.columns) {
    meta.appendChild(el('span', '', `${data.count}행${data.truncated ? ` (앞 ${data.rows.length}행만 표시)` : ''}`));
  } else if (data.count !== undefined) {
    meta.appendChild(el('span', '', `${data.kind.toUpperCase()} ${data.count}`));
  } else {
    meta.appendChild(el('span', '', data.message));
  }
  const badge = el('span', 'badge ' + data.txn, data.txn);
  meta.appendChild(badge);
  entry.appendChild(meta);
  if (data.columns) entry.appendChild(renderTable(data.columns, data.rows));
  history.appendChild(entry);
  entry.scrollIntoView({ block: 'end' });
  setTxn(data.txn);
  if (!data.columns) loadSchema();
}

$('run').onclick = run;
sqlBox.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') run(); });
$('reset').onclick = async () => {
  if (!confirm('모든 방문자의 데이터를 지우고 샘플 상태로 되돌립니다. 계속할까요?')) return;
  await fetch('/reset', { method: 'POST' });
  history.textContent = '';
  setTxn('I');
  loadSchema();
};
loadSchema();
</script>
</body>
</html>
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :playground:test`
Expected: 전체 통과

- [ ] **Step 5: 수동 확인 (Main은 Task 8에서 만들므로 테스트 서버로 대신 본다)**

임시로 다음 Kotlin 스크립트 없이, Task 8 완료 후 `./gradlew :playground:run`으로 브라우저 확인한다. 이 Task에서는 테스트 통과까지만.

- [ ] **Step 6: 커밋**

```bash
git add playground/src
git commit -m "feat: 플레이그라운드 화면(스키마 사이드바, 예제 버튼, 실행 이력) 추가"
```

---

### Task 8: Main + 로컬 실행 확인

**Files:**
- Create: `playground/src/main/kotlin/gwanbase/playground/Main.kt`

**Interfaces:**
- Consumes: Task 3 `Engine(path)`, Task 6 `PlaygroundServer(engine, port)`. 환경변수 `PORT`(기본 8080), `GWANBASE_DB`(기본 `playground.db`).

- [ ] **Step 1: Main 작성**

```kotlin
package gwanbase.playground

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import mu.KotlinLogging
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * 플레이그라운드 진입점.
 *
 * 환경변수: `PORT`(기본 8080), `GWANBASE_DB`(기본 `playground.db`).
 * core의 logback.xml이 `gwanbase`를 DEBUG로 두어 페이지 단위 로그가 쏟아지므로 INFO로 올린다.
 */
fun main() {
    (LoggerFactory.getLogger("gwanbase") as Logger).level = Level.INFO
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbPath = Paths.get(System.getenv("GWANBASE_DB") ?: "playground.db")

    val engine = Engine(dbPath)
    val server = PlaygroundServer(engine, port)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        engine.close()
    })
    server.start()
    logger.info { "http://localhost:${server.port}" }
}
```

- [ ] **Step 2: 로컬 실행과 브라우저 확인**

Run: `./gradlew :playground:run` (별도 터미널, 또는 백그라운드)
브라우저에서 `http://localhost:8080` 열고 확인:
1. 사이드바에 `users`, `orders`, `idx_orders_user`가 보인다.
2. `SELECT` 예제 → 결과 테이블, 배지 `I`.
3. `BEGIN…ROLLBACK` 예제 → 배지 `T`; `UNIQUE 위반` 예제 → 빨간 에러 `[23505]`, 배지 `E`; `SELECT * FROM users` 입력 → `[25P02]`; `ROLLBACK` 입력 → 배지 `I`.
4. 시크릿 창(다른 쿠키)에서 `BEGIN` 후 `UPDATE users SET age = 1 WHERE id = 1`, 원래 창에서 같은 UPDATE → 5초 뒤 `[55P03]`.
5. `초기화` → 확인창 → 스키마 복원.

Ctrl+C로 종료. 저장소에 `playground.db`, `playground.db.wal`이 남지만 `.gitignore`의 `*.db`, `*.wal`로 무시된다.

- [ ] **Step 3: 커밋**

```bash
git add playground/src
git commit -m "feat: 플레이그라운드 Main 진입점 추가"
```

---

### Task 9: Dockerfile + fly.toml + 문서

**Files:**
- Create: `Dockerfile` (저장소 루트)
- Create: `fly.toml` (저장소 루트)
- Create: `.dockerignore`
- Modify: `README.md` (현재 상태 섹션 아래 한 줄), `CLAUDE.md` (프로젝트 구조·기술 스택)

- [ ] **Step 1: Docker 파일 작성**

`.dockerignore`:
```
.git
.gradle
**/build
*.db
*.wal
*.log
```

`Dockerfile`:
```dockerfile
# 빌드: 저장소 전체가 컨텍스트여야 core를 함께 컴파일할 수 있다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :playground:installDist --no-daemon -q

# 실행: JRE만
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/playground/build/install/playground /app
ENV PORT=8080 GWANBASE_DB=/app/data/playground.db
RUN mkdir -p /app/data
EXPOSE 8080
CMD ["/app/bin/playground"]
```

- [ ] **Step 2: 로컬 Docker 확인**

Run:
```bash
docker build -t gwanbase-playground .
docker run --rm -p 8080:8080 gwanbase-playground
```
Expected: 로그에 `http://localhost:8080`, 브라우저에서 Task 8의 1~2번 확인. Ctrl+C.

- [ ] **Step 3: fly.toml 작성**

`fly launch --no-deploy --name gwanbase-playground --region nrt --copy-config --yes`를 실행하면 `fly.toml`이 생긴다. 내용을 다음으로 맞춘다 (앱 이름이 이미 사용 중이면 `fly launch`가 제안하는 이름을 쓴다):
```toml
app = "gwanbase-playground"
primary_region = "nrt"

[build]

[env]
  PORT = "8080"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "stop"
  auto_start_machines = true
  min_machines_running = 0

[[vm]]
  memory = "512mb"
  cpu_kind = "shared"
  cpus = 1
```

`auto_stop_machines`로 방문자가 없으면 꺼지고 첫 요청에 켜진다(콜드 스타트 수 초). 볼륨은 두지 않는다 — 재시작 시 샘플 상태로 돌아가는 것이 의도다.

- [ ] **Step 4: 배포**

Run: `fly deploy`
Expected: 빌드·배포 성공, `fly status`에 머신 1개. `https://gwanbase-playground.fly.dev`에서 Task 8의 1~3번 확인.

- [ ] **Step 5: 문서**

`README.md`의 "## 현재 상태" 섹션 첫 단락 아래에 추가:
```markdown
브라우저에서 바로 써볼 수 있다: **[Playground](https://gwanbase-playground.fly.dev)** (모든 방문자가 DB 하나를 공유하며, 재시작 시 초기화된다)
```
배포된 실제 URL로 바꾼다.

`CLAUDE.md`:
- "## 기술 스택"의 `Build:` 줄을 `Build: Gradle Kotlin DSL (멀티모듈: core, bench, playground)`로.
- "## 프로젝트 구조" 트리의 `├── bench/` 아래에 추가:
  ```
  ├── playground/                ← 웹 SQL 콘솔 (JDK HttpServer, Fly.io 배포)
  │   └── src/main/kotlin/gwanbase/playground/
  ├── Dockerfile, fly.toml       ← playground 배포
  ```
- "## 빌드 및 테스트 명령어"에 추가:
  ```bash
  ./gradlew :playground:test         # playground 모듈 테스트
  ./gradlew :playground:run          # 로컬에서 플레이그라운드 실행 (http://localhost:8080)
  ```

- [ ] **Step 6: 커밋**

```bash
git add Dockerfile .dockerignore fly.toml
git commit -m "feat: 플레이그라운드 Docker 이미지와 Fly.io 설정 추가"
git add README.md CLAUDE.md
git commit -m "docs: README와 CLAUDE.md에 플레이그라운드 링크·모듈 반영"
```

---

### Task 10: PR

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: core·playground 전부 통과

- [ ] **Step 2: push + PR**

```bash
git push -u origin feat/playground
gh pr create --title "feat: 브라우저 SQL 플레이그라운드 모듈과 Fly.io 배포" --body "$(cat <<'EOF'
## 요약
- Gwanbase를 실제로 띄워 브라우저에서 SQL을 실행해 볼 수 있는 `playground/` 모듈을 추가하고 Fly.io에 배포했다.
- 설계: `docs/superpowers/specs/2026-09-12-playground-design.md`

## 변경 내용
- `playground/`: JDK 내장 HttpServer로 `/query`·`/schema`·`/reset` 제공. 쿠키 하나로 방문자별 `DatabaseSession`, 공유 DB, idle 10분 회수, 락 타임아웃 5초, 결과 500행 상한.
- `core`: `ConnectionHandler.sqlStateOf` public.
- `Dockerfile`, `fly.toml`, README 링크.

## 테스트
- [ ] `./gradlew test` 전체 통과
- [ ] 배포 URL에서 SELECT / BEGIN→UNIQUE 위반→25P02→ROLLBACK / 시크릿 창 55P03 / 초기화 수동 확인
EOF
)"
```

---

## 자체 점검

**스펙 커버리지**
- 모듈 구조 → Task 1, 3, 4/5, 6, 8. `Engine.kt` 추가와 `Dockerfile`/`fly.toml` 루트 배치는 스펙에 반영됨.
- 엔드포인트 4개, 상태 코드(400/404/405/413), 요청 본문=SQL 텍스트 → Task 6.
- 세션·동시성(쿠키, synchronized, lockTimeout 5s, idle 10분, reset RW 락, I/T/E 상태기계 + `ponytail:` 주석) → Task 4, 5, 6.
- 안전 상한(500행, 64KB, 문장 타임아웃 없음) → Task 6.
- 화면(사이드바, 예제 8개, 이력, txn 배지, 사이드바 갱신) → Task 7.
- 샘플 데이터 → Task 3.
- 테스트 6개 + Json escape → Task 1, 2, 4, 5, 6 (스펙 3번 55P03, 4번 reset, 5번 truncated, 6번 I/T/E 모두 `PlaygroundServerTest`/`PlaygroundSessionTest`에 있음).
- 배포(수동 `fly deploy`, README 링크, CI 배포는 별도 PR) → Task 9.

**타입 일관성**
- `Engine.database`, `Engine.reset()`: Task 3 정의, Task 5/6/8 사용 — 일치.
- `PlaygroundSession.execute/txnStatus/lastUsedAt/close`: Task 4 정의, Task 5/6 사용 — 일치.
- `SessionRegistry.acquire/evictIdle/closeAll/size`: Task 5 정의, Task 6 사용 — 일치.
- `Json.encode/result/schema`: Task 1/2 정의, Task 6 사용 — 일치.
- `ConnectionHandler.sqlStateOf`: companion 함수, Task 6에서 `ConnectionHandler.sqlStateOf(e)`로 호출 — 일치.

**알려진 주의점**
- `PlaygroundSession`의 ROLLBACK 처리에 있는 `runCatching`은 core의 "실행 오류 후 ROLLBACK이 IllegalStateException"인 문제를 우회한다. 이 문제는 `GwanServer`(PG 프로토콜)에도 그대로 있으며, correctness hardening 항목으로 별도 처리한다.
