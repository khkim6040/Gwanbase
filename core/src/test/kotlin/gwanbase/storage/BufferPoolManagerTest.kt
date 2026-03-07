package gwanbase.storage

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BufferPoolManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dm: DiskManager
    private lateinit var bpm: BufferPoolManager

    @BeforeEach
    fun setUp() {
        dm = DiskManager(tempDir.resolve("test.db"))
        bpm = BufferPoolManager(dm, poolSize = 3)
    }

    @AfterEach
    fun tearDown() {
        dm.close()
    }

    @Test
    fun `새 페이지 생성 및 데이터 기록`() {
        val page = bpm.newPage().shouldNotBeNull()
        page.data.putInt(0, 42)
        bpm.unpinPage(page.pageId, isDirty = true)
    }

    @Test
    fun `페이지 기록 후 다시 읽기`() {
        val page = bpm.newPage().shouldNotBeNull()
        val pageId = page.pageId
        page.data.putInt(0, 12345)
        bpm.unpinPage(pageId, isDirty = true)
        bpm.flushPage(pageId)

        val fetched = bpm.fetchPage(pageId).shouldNotBeNull()
        fetched.data.getInt(0) shouldBe 12345
        bpm.unpinPage(pageId)
    }

    @Test
    fun `풀 크기 초과 시 eviction 동작`() {
        // 풀 크기가 3이므로 4번째 페이지 요청 시 eviction 발생해야 함
        val ids = (0 until 3).map { bpm.newPage()!!.pageId }
        ids.forEach { bpm.unpinPage(it, isDirty = true) }

        // 4번째 페이지 - eviction 발생
        val page4 = bpm.newPage().shouldNotBeNull()
        bpm.unpinPage(page4.pageId, isDirty = true)

        // evict된 페이지도 fetch로 다시 가져올 수 있어야 함
        val refetched = bpm.fetchPage(ids[0]).shouldNotBeNull()
        refetched.pageId shouldBe ids[0]
        bpm.unpinPage(ids[0])
    }

    @Test
    fun `모든 페이지가 pin 상태면 new page 실패`() {
        // 풀 크기 3개를 모두 pin 상태로 유지
        repeat(3) { bpm.newPage().shouldNotBeNull() }
        // 4번째는 eviction 불가 → null
        bpm.newPage().shouldBeNull()
    }

    @Test
    fun `영속성 - flush 후 새 BufferPoolManager에서 읽기`() {
        val page = bpm.newPage().shouldNotBeNull()
        val pageId = page.pageId
        page.data.putInt(0, 99999)
        bpm.unpinPage(pageId, isDirty = true)
        bpm.flushAllPages()

        // 새 BPM으로 교체
        val bpm2 = BufferPoolManager(dm, poolSize = 3)
        val fetched = bpm2.fetchPage(pageId).shouldNotBeNull()
        fetched.data.getInt(0) shouldBe 99999
        bpm2.unpinPage(pageId)
    }
}
