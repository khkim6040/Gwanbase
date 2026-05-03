package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CatalogIndexTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createCatalog(): Triple<Catalog, BufferPoolManager, DiskManager> {
        val dm = DiskManager(tempDir.resolve("catalog_index_test.db"))
        val bpm = BufferPoolManager(dm, 64)
        val catalog = Catalog.createNew(bpm)
        return Triple(catalog, bpm, dm)
    }

    private val userSchema = Schema(
        listOf(
            Column("id", DataType.INT32),
            Column("name", DataType.VARCHAR, maxLength = 100),
        )
    )

    // --- IndexInfo 테스트 ---

    @Test
    fun `인덱스 등록 후 이름으로 조회`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)

        val idx = catalog.createIndex("idx_users_id", "users", "id", rootPageId = 10)
        idx.name shouldBe "idx_users_id"
        idx.tableName shouldBe "users"
        idx.columnName shouldBe "id"
        idx.rootPageId shouldBe 10

        val retrieved = catalog.getIndex("idx_users_id")
        retrieved.shouldNotBeNull()
        retrieved.indexId shouldBe idx.indexId
        retrieved.name shouldBe "idx_users_id"
    }

    @Test
    fun `존재하지 않는 인덱스 조회 시 null`() {
        val (catalog, _, _) = createCatalog()
        catalog.getIndex("nonexistent").shouldBeNull()
    }

    @Test
    fun `테이블별 인덱스 목록 조회`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)
        catalog.createTable("posts", Schema(listOf(Column("id", DataType.INT32))))

        catalog.createIndex("idx_users_id", "users", "id", rootPageId = 10)
        catalog.createIndex("idx_users_name", "users", "name", rootPageId = 11)
        catalog.createIndex("idx_posts_id", "posts", "id", rootPageId = 12)

        val userIndexes = catalog.getIndexesForTable("users")
        userIndexes shouldHaveSize 2

        val postIndexes = catalog.getIndexesForTable("posts")
        postIndexes shouldHaveSize 1
        postIndexes[0].name shouldBe "idx_posts_id"
    }

    @Test
    fun `인덱스가 없는 테이블의 인덱스 목록은 빈 리스트`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)

        catalog.getIndexesForTable("users").shouldBeEmpty()
    }

    @Test
    fun `인덱스 삭제 후 조회 시 null`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)
        catalog.createIndex("idx_users_id", "users", "id", rootPageId = 10)

        catalog.dropIndex("idx_users_id") shouldBe true
        catalog.getIndex("idx_users_id").shouldBeNull()
    }

    @Test
    fun `존재하지 않는 인덱스 삭제 시 false`() {
        val (catalog, _, _) = createCatalog()
        catalog.dropIndex("nonexistent") shouldBe false
    }

    @Test
    fun `중복 인덱스 이름 생성 시 예외`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)
        catalog.createIndex("idx_users_id", "users", "id", rootPageId = 10)

        assertThrows<IllegalArgumentException> {
            catalog.createIndex("idx_users_id", "users", "id", rootPageId = 11)
        }
    }

    @Test
    fun `indexId 자동 증가`() {
        val (catalog, _, _) = createCatalog()
        catalog.createTable("users", userSchema)
        val idx1 = catalog.createIndex("idx1", "users", "id", rootPageId = 10)
        val idx2 = catalog.createIndex("idx2", "users", "name", rootPageId = 11)

        idx2.indexId shouldBe idx1.indexId + 1
    }

    @Test
    fun `인덱스 영속화 후 재로드 시 보존`() {
        val dbPath = tempDir.resolve("index_persist_test.db")

        val catalogPageId: Int
        run {
            val dm = DiskManager(dbPath)
            val bpm = BufferPoolManager(dm, 64)
            val catalog = Catalog.createNew(bpm)
            catalogPageId = catalog.catalogPageId
            catalog.createTable("users", userSchema)
            catalog.createIndex("idx_users_id", "users", "id", rootPageId = 10)
            catalog.createIndex("idx_users_name", "users", "name", rootPageId = 11)
            bpm.flushAllPages()
            dm.close()
        }

        run {
            val dm = DiskManager(dbPath)
            val bpm = BufferPoolManager(dm, 64)
            val catalog = Catalog.load(bpm, catalogPageId)

            val idx1 = catalog.getIndex("idx_users_id")
            idx1.shouldNotBeNull()
            idx1.tableName shouldBe "users"
            idx1.columnName shouldBe "id"
            idx1.rootPageId shouldBe 10

            val idx2 = catalog.getIndex("idx_users_name")
            idx2.shouldNotBeNull()
            idx2.columnName shouldBe "name"
            idx2.rootPageId shouldBe 11

            catalog.getIndexesForTable("users") shouldHaveSize 2

            dm.close()
        }
    }
}
