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
}
