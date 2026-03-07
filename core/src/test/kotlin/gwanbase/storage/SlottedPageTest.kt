package gwanbase.storage

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SlottedPageTest {

    private lateinit var slottedPage: SlottedPage

    @BeforeEach
    fun setUp() {
        val buffer = newPageBuffer()
        slottedPage = SlottedPage(buffer)
        slottedPage.init()
    }

    @Test
    fun `초기 상태 검증`() {
        slottedPage.slotCount shouldBe 0
        slottedPage.freeSpaceOffset shouldBe DiskManager.PAGE_SIZE
    }

    @Test
    fun `레코드 삽입 및 조회`() {
        val data = "Hello, Database!".toByteArray()
        val slotId = slottedPage.insertRecord(data)

        slotId shouldBe 0
        slottedPage.slotCount shouldBe 1
        slottedPage.getRecord(slotId) shouldBe data
    }

    @Test
    fun `여러 레코드 삽입 후 개별 조회`() {
        val records = (0 until 10).map { "Record-$it".toByteArray() }
        val slotIds = records.map { slottedPage.insertRecord(it) }

        slotIds.forEachIndexed { i, slotId ->
            slottedPage.getRecord(slotId) shouldBe records[i]
        }
    }

    @Test
    fun `레코드 삭제 후 null 반환`() {
        val slotId = slottedPage.insertRecord("to-delete".toByteArray())
        slottedPage.deleteRecord(slotId) shouldBe true
        slottedPage.getRecord(slotId).shouldBeNull()
    }

    @Test
    fun `삭제된 슬롯 재사용`() {
        val slot0 = slottedPage.insertRecord("first".toByteArray())
        val slot1 = slottedPage.insertRecord("second".toByteArray())
        slottedPage.deleteRecord(slot0)

        // 새 삽입 시 삭제된 slot 0을 재사용
        val reused = slottedPage.insertRecord("reused".toByteArray())
        reused shouldBe slot0
        slottedPage.getRecord(reused) shouldBe "reused".toByteArray()
        slottedPage.getRecord(slot1) shouldBe "second".toByteArray()
    }

    @Test
    fun `allRecords는 삭제된 레코드를 제외`() {
        slottedPage.insertRecord("a".toByteArray())
        slottedPage.insertRecord("b".toByteArray())
        slottedPage.insertRecord("c".toByteArray())
        slottedPage.deleteRecord(1)

        val all = slottedPage.allRecords()
        all shouldHaveSize 2
        all.map { String(it.second) } shouldBe listOf("a", "c")
    }

    @Test
    fun `공간 부족 시 -1 반환`() {
        // 페이지를 거의 다 채움
        val bigRecord = ByteArray(DiskManager.PAGE_SIZE / 2)
        slottedPage.insertRecord(bigRecord) shouldBe 0
        slottedPage.insertRecord(bigRecord) shouldBe -1  // 공간 부족
    }

    @Test
    fun `범위 밖 슬롯 조회 시 null`() {
        slottedPage.getRecord(-1).shouldBeNull()
        slottedPage.getRecord(999).shouldBeNull()
    }
}
