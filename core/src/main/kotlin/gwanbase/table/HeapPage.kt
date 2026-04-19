package gwanbase.table

import gwanbase.storage.DiskManager
import gwanbase.storage.SlottedPage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HeapFile의 데이터 페이지.
 *
 * 페이지 앞 [HEADER_SIZE]바이트는 [nextFreePageId]를 저장하고,
 * 나머지 영역은 [SlottedPage]로 위임한다.
 *
 * 레이아웃:
 * ```
 * [nextFreePageId: 4 bytes] [SlottedPage 영역: PAGE_SIZE - 4 bytes]
 * ```
 */
class HeapPage(private val buffer: ByteBuffer) {

    companion object {
        /** HeapPage 전용 헤더 크기 (nextFreePageId) */
        const val HEADER_SIZE = 4

        const val INVALID_PAGE_ID = -1

        /** Free Page List에 속하지 않은 상태를 나타내는 센티널 값 */
        const val NOT_IN_FREE_LIST = -2
    }

    init {
        buffer.order(ByteOrder.BIG_ENDIAN)
    }

    /** Free Page List에서 다음 페이지의 pageId. 없으면 [INVALID_PAGE_ID]. */
    var nextFreePageId: Int
        get() = buffer.getInt(0)
        set(value) { buffer.putInt(0, value) }

    private val slottedPage: SlottedPage = run {
        buffer.position(HEADER_SIZE)
        buffer.limit(DiskManager.PAGE_SIZE)
        val slice = buffer.slice().order(ByteOrder.BIG_ENDIAN)
        buffer.position(0)
        buffer.limit(DiskManager.PAGE_SIZE)
        SlottedPage(slice)
    }

    /** 빈 페이지로 초기화 */
    fun init() {
        nextFreePageId = NOT_IN_FREE_LIST
        slottedPage.init()
    }

    /** 레코드 삽입. 공간 부족 시 -1 반환. */
    fun insertRecord(record: ByteArray): Int = slottedPage.insertRecord(record)

    /** 슬롯 ID로 레코드 조회 */
    fun getRecord(slotId: Int): ByteArray? = slottedPage.getRecord(slotId)

    /** 슬롯 삭제 */
    fun deleteRecord(slotId: Int): Boolean = slottedPage.deleteRecord(slotId)

    /** 유효한 레코드 수 */
    val recordCount: Int get() = slottedPage.allRecords().size

    /** 여유 공간 바이트 수 */
    val freeSpace: Int get() = slottedPage.freeSpace

    /** 모든 유효한 (slotId, record) 쌍 반환 */
    fun allRecords(): List<Pair<Int, ByteArray>> = slottedPage.allRecords()
}
