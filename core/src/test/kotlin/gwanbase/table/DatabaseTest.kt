package gwanbase.table

import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteOrder
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

    @Test
    fun `dropTable 후 테이블 조회 시 null 반환`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)
            db.dropTable("users") shouldBe true
            db.getTable("users") shouldBe null
        }
    }

    @Test
    fun `존재하지 않는 테이블 dropTable 시 false 반환`() {
        Database.open(dbPath()).use { db ->
            db.dropTable("nonexistent") shouldBe false
        }
    }

    @Test
    fun `updateTuple 후 조회 시 변경된 값 반환`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)
            val original = Tuple(userSchema, arrayOf(1, "Alice", true))
            val rid = db.insertTuple("users", original)
            val updated = Tuple(userSchema, arrayOf(1, "Bob", false))
            val newRid = db.updateTuple("users", rid, updated)
            val result = db.getTuple("users", newRid)
            result.shouldNotBeNull()
            result.getString(1) shouldBe "Bob"
            result.getBoolean(2) shouldBe false
        }
    }

    @Test
    fun `getCatalog은 Catalog 인스턴스를 반환한다`() {
        Database.open(dbPath()).use { db ->
            db.createTable("users", userSchema)
            val catalog = db.getCatalog()
            catalog.getTable("users").shouldNotBeNull()
        }
    }

    @Test
    fun `잘못된 version의 DB 파일 열기 시 예외`() {
        val path = dbPath()

        // 정상 DB 생성 후 닫기
        Database.open(path).use { }

        // 메타데이터 페이지의 version을 변조
        val dm = DiskManager(path)
        val buf = dm.readPage(0)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(4, 99.toShort()) // OFFSET_VERSION = 4, 잘못된 version
        buf.position(0)
        dm.writePage(0, buf)
        dm.close()

        // 열기 시 version 불일치 예외
        assertThrows<IllegalStateException> {
            Database.open(path)
        }
    }
}
