package gwanbase.sql

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
}
