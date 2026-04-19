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

        run {
            val dm = DiskManager(dbPath)
            val bpm = BufferPoolManager(dm, 64)
            val catalog = Catalog.createNew(bpm)
            catalog.createTable("users", userSchema)
            bpm.flushAllPages()
            dm.close()
        }

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
