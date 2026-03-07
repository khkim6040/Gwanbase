package gwanbase.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path

class DiskManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dm: DiskManager

    @BeforeEach
    fun setUp() {
        dm = DiskManager(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() {
        dm.close()
    }

    @Test
    fun `새 파일은 페이지가 0개`() {
        dm.pageCount shouldBe 0
    }

    @Test
    fun `페이지 할당 후 개수 증가`() {
        dm.allocatePage() shouldBe 0
        dm.allocatePage() shouldBe 1
        dm.pageCount shouldBe 2
    }

    @Test
    fun `쓰기 후 읽기 일치`() {
        val pageId = dm.allocatePage()
        val data = ByteBuffer.allocate(DiskManager.PAGE_SIZE)
        data.putInt(0, 42)
        data.putInt(100, 9999)
        dm.writePage(pageId, data)

        val read = dm.readPage(pageId)
        read.getInt(0) shouldBe 42
        read.getInt(100) shouldBe 9999
    }

    @Test
    fun `여러 페이지 독립적으로 읽기 쓰기`() {
        val pages = (0 until 10).map { i ->
            val pageId = dm.allocatePage()
            val buf = ByteBuffer.allocate(DiskManager.PAGE_SIZE)
            buf.putInt(0, i * 100)
            dm.writePage(pageId, buf)
            pageId
        }

        pages.forEachIndexed { i, pageId ->
            val buf = dm.readPage(pageId)
            buf.getInt(0) shouldBe i * 100
        }
    }
}
