package gwanbase.table

import gwanbase.storage.DiskManager
import gwanbase.storage.newPageBuffer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HeapPageTest {

    private lateinit var heapPage: HeapPage

    @BeforeEach
    fun setUp() {
        val buffer = newPageBuffer()
        heapPage = HeapPage(buffer)
        heapPage.init()
    }

    @Test
    fun `초기 상태 - nextFreePageId는 NOT_IN_FREE_LIST, 레코드 없음`() {
        heapPage.nextFreePageId shouldBe HeapPage.NOT_IN_FREE_LIST
        heapPage.recordCount shouldBe 0
    }

    @Test
    fun `레코드 삽입 및 조회`() {
        val data = "Hello, Table!".toByteArray()
        val slotId = heapPage.insertRecord(data)

        slotId shouldBe 0
        heapPage.getRecord(slotId) shouldBe data
        heapPage.recordCount shouldBe 1
    }

    @Test
    fun `레코드 삭제 후 null 반환`() {
        val slotId = heapPage.insertRecord("delete-me".toByteArray())
        heapPage.deleteRecord(slotId) shouldBe true
        heapPage.getRecord(slotId).shouldBeNull()
    }

    @Test
    fun `nextFreePageId 설정 및 조회`() {
        heapPage.nextFreePageId = 42
        heapPage.nextFreePageId shouldBe 42
    }

    @Test
    fun `여유 공간 확인`() {
        val initialFree = heapPage.freeSpace
        val data = ByteArray(100)
        heapPage.insertRecord(data)
        heapPage.freeSpace shouldBe initialFree - 100 - 4  // 레코드 + 슬롯 엔트리
    }

    @Test
    fun `HeapPage 메타데이터는 SlottedPage 영역을 침범하지 않는다`() {
        // SlottedPage 헤더(4) + 슬롯(4) = 8바이트를 빼면 최대 레코드 크기
        val maxRecord = ByteArray(heapPage.freeSpace - 4) // 슬롯 엔트리 4바이트 제외
        val slotId = heapPage.insertRecord(maxRecord)
        slotId shouldBe 0
        heapPage.getRecord(slotId) shouldBe maxRecord
    }
}
