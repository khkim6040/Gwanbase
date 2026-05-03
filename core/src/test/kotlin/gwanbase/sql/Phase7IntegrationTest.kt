package gwanbase.sql

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.optimizer.StatisticsManager
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase7IntegrationTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        database.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50), age INT)")
    }

    @AfterEach
    fun tearDown() { database.close() }

    @Test
    fun `CREATE INDEX 실행 후 Catalog에 등록 확인`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")

        val result = database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        result.shouldBeInstanceOf<ExecuteResult.IndexCreated>()
        result.indexName shouldBe "idx_users_id"

        val indexInfo = database.getCatalog().getIndex("idx_users_id")
        indexInfo shouldNotBe null
        indexInfo!!.tableName shouldBe "users"
        indexInfo.columnName shouldBe "id"
    }

    @Test
    fun `DROP INDEX 실행`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")

        val result = database.executeSql("DROP INDEX idx_users_id")
        result.shouldBeInstanceOf<ExecuteResult.IndexDropped>()
        result.indexName shouldBe "idx_users_id"

        database.getCatalog().getIndex("idx_users_id") shouldBe null
    }

    @Test
    fun `INSERT 후 인덱스 자동 갱신`() {
        // 빈 테이블에 인덱스 생성
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")

        // INSERT 후 인덱스에서 직접 검색
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)")

        val indexInfo = database.getCatalog().getIndex("idx_users_id")!!
        val tree = database.getIndexTree(indexInfo)

        // 각 키가 인덱스에 존재하는지 확인
        val key1 = KeySerializer.serializeKey(1, DataType.INT32)
        tree.search(key1) shouldNotBe null

        val key2 = KeySerializer.serializeKey(2, DataType.INT32)
        tree.search(key2) shouldNotBe null

        val key3 = KeySerializer.serializeKey(3, DataType.INT32)
        tree.search(key3) shouldNotBe null

        // 존재하지 않는 키
        val key999 = KeySerializer.serializeKey(999, DataType.INT32)
        tree.search(key999) shouldBe null
    }

    @Test
    fun `DELETE 후 인덱스에서 제거 확인`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)")
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")

        // id=2 삭제
        database.executeSql("DELETE FROM users WHERE id = 2")

        val indexInfo = database.getCatalog().getIndex("idx_users_id")!!
        val tree = database.getIndexTree(indexInfo)

        // id=2는 인덱스에서 제거되어야 함
        val key2 = KeySerializer.serializeKey(2, DataType.INT32)
        tree.search(key2) shouldBe null

        // id=1, 3은 여전히 존재해야 함
        val key1 = KeySerializer.serializeKey(1, DataType.INT32)
        tree.search(key1) shouldNotBe null

        val key3 = KeySerializer.serializeKey(3, DataType.INT32)
        tree.search(key3) shouldNotBe null
    }

    @Test
    fun `INSERT 후 rowCount 증가 확인`() {
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")

        database.getCatalog().getRowCount("users") shouldBe 2
    }

    @Test
    fun `DELETE 후 rowCount 감소 확인`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")

        database.executeSql("DELETE FROM users WHERE id = 1")

        database.getCatalog().getRowCount("users") shouldBe 1
    }

    @Test
    fun `ANALYZE 실행 후 rowCount 반환`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)")

        val result = database.executeSql("ANALYZE users")
        result.shouldBeInstanceOf<ExecuteResult.Analyzed>()
        result.tableName shouldBe "users"
        result.rowCount shouldBe 3
    }

    @Test
    fun `EXPLAIN SELECT → SeqScan 포함 계획 텍스트 반환`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")

        val result = database.executeSql("EXPLAIN SELECT * FROM users")
        result.shouldBeInstanceOf<ExecuteResult.Explained>()
        result.planText shouldContain "SeqScan"
        result.planText shouldContain "Project"
    }

    @Test
    fun `EXPLAIN SELECT with index → IndexScan 포함 계획 텍스트 반환`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")

        val result = database.executeSql("EXPLAIN SELECT * FROM users WHERE id = 500")
        result.shouldBeInstanceOf<ExecuteResult.Explained>()
        result.planText shouldContain "IndexScan"
    }

    @Test
    fun `Optimizer 경유 SELECT 실행 — 기존 결과와 동일`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)")

        val result = database.executeSql("SELECT * FROM users WHERE age = 30")
        result.shouldBeInstanceOf<ExecuteResult.Selected>()
        result.rows.size shouldBe 1
        result.rows[0][1] shouldBe "Alice"
    }

    @Test
    fun `SELECT with WHERE + index → Optimizer 경로로 정확한 결과`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")

        val result = database.executeSql("SELECT * FROM users WHERE id = 777")
        result.shouldBeInstanceOf<ExecuteResult.Selected>()
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 777
        result.rows[0][1] shouldBe "user777"
    }

    @Test
    fun `두 테이블 INNER JOIN SQL 실행`() {
        Database.open(tempDir.resolve("join.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("CREATE TABLE orders (oid INT NOT NULL, user_id INT NOT NULL, amount INT NOT NULL)")
            db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
            db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
            db.executeSql("INSERT INTO orders (oid, user_id, amount) VALUES (10, 1, 100)")
            db.executeSql("INSERT INTO orders (oid, user_id, amount) VALUES (11, 1, 200)")
            db.executeSql("INSERT INTO orders (oid, user_id, amount) VALUES (12, 2, 300)")

            val result = db.executeSql(
                "SELECT * FROM users JOIN orders ON users.id = orders.user_id"
            ) as ExecuteResult.Selected
            result.rows.size shouldBe 3
        }
    }

    @Test
    fun `인덱스 유지보수 — INSERT 후 인덱스 경유 조회`() {
        Database.open(tempDir.resolve("idx-insert.db")).use { db ->
            db.executeSql("CREATE TABLE t (id INT NOT NULL, val VARCHAR(50))")
            db.executeSql("CREATE INDEX idx_id ON t (id)")
            repeat(50) { db.executeSql("INSERT INTO t (id, val) VALUES ($it, 'v$it')") }
            db.executeSql("ANALYZE t")
            val result = db.executeSql("SELECT val FROM t WHERE id = 25") as ExecuteResult.Selected
            result.rows.size shouldBe 1
            result.rows[0][0] shouldBe "v25"
        }
    }

    @Test
    fun `DELETE 후 인덱스 정합성`() {
        Database.open(tempDir.resolve("idx-delete.db")).use { db ->
            db.executeSql("CREATE TABLE t (id INT NOT NULL, val VARCHAR(50))")
            db.executeSql("CREATE INDEX idx_id ON t (id)")
            db.executeSql("INSERT INTO t (id, val) VALUES (1, 'a')")
            db.executeSql("INSERT INTO t (id, val) VALUES (2, 'b')")
            db.executeSql("DELETE FROM t WHERE id = 1")
            db.executeSql("ANALYZE t")
            val result = db.executeSql("SELECT val FROM t WHERE id = 1") as ExecuteResult.Selected
            result.rows.size shouldBe 0
        }
    }

    @Test
    fun `ANALYZE 후 옵티마이저 계획 변경 확인`() {
        Database.open(tempDir.resolve("analyze-plan.db")).use { db ->
            db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
            repeat(1000) { db.executeSql("INSERT INTO t (id, name) VALUES ($it, 'n$it')") }
            db.executeSql("CREATE INDEX idx_id ON t (id)")
            db.executeSql("ANALYZE t")
            val result = db.executeSql("EXPLAIN SELECT * FROM t WHERE id = 50") as ExecuteResult.Explained
            result.planText shouldContain "IndexScan"
        }
    }

    @Test
    fun `JOIN EXPLAIN 출력`() {
        Database.open(tempDir.resolve("join-explain.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("CREATE TABLE orders (oid INT NOT NULL, user_id INT NOT NULL)")
            db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
            db.executeSql("INSERT INTO orders (oid, user_id) VALUES (10, 1)")
            val result = db.executeSql(
                "EXPLAIN SELECT * FROM users JOIN orders ON users.id = orders.user_id"
            ) as ExecuteResult.Explained
            result.planText shouldContain "NestedLoopJoin"
        }
    }

    @Test
    fun `ANALYZE 후 컬럼 통계 정확성`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', NULL)")

        database.executeSql("ANALYZE users")

        val catalog = database.getCatalog()

        // id 컬럼: 3개 고유값, min=1, max=3, null=0
        val idStats = catalog.getColumnStats("users", "id")
        idStats shouldNotBe null
        idStats!!.distinctCount shouldBe 3
        idStats.minValue shouldBe 1L
        idStats.maxValue shouldBe 3L
        idStats.nullCount shouldBe 0

        // name 컬럼: 3개 고유값, 문자열이므로 min/max=null, null=0
        val nameStats = catalog.getColumnStats("users", "name")
        nameStats shouldNotBe null
        nameStats!!.distinctCount shouldBe 3
        nameStats.minValue shouldBe null
        nameStats.maxValue shouldBe null
        nameStats.nullCount shouldBe 0

        // age 컬럼: 2개 고유값, min=25, max=30, null=1
        val ageStats = catalog.getColumnStats("users", "age")
        ageStats shouldNotBe null
        ageStats!!.distinctCount shouldBe 2
        ageStats.minValue shouldBe 25L
        ageStats.maxValue shouldBe 30L
        ageStats.nullCount shouldBe 1
    }
}
