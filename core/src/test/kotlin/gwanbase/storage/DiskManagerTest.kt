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
    fun `readPage는 EOF에서도 PAGE_SIZE 버퍼를 반환`() {
        // 빈 파일 상태에서 존재하지 않는 페이지를 읽어도
        // 페이지 I/O 계약(position=0, limit=PAGE_SIZE, 나머지 0)을 지켜야 한다.
        val buffer = dm.readPage(0)
        buffer.position() shouldBe 0
        buffer.limit() shouldBe DiskManager.PAGE_SIZE
        buffer.remaining() shouldBe DiskManager.PAGE_SIZE
        // 읽지 못한 영역은 0으로 채워져 있다
        for (i in 0 until DiskManager.PAGE_SIZE) {
            buffer.get(i) shouldBe 0.toByte()
        }
    }

    @Test
    fun `allocatePage 이후 readPage는 전체 PAGE_SIZE 반환`() {
        val pageId = dm.allocatePage()
        val buffer = dm.readPage(pageId)
        buffer.position() shouldBe 0
        buffer.limit() shouldBe DiskManager.PAGE_SIZE
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
