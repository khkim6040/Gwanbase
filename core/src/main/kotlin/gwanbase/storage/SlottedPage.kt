package gwanbase.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 가변 길이 레코드를 한 페이지 내에 저장하는 슬롯 구조.
 *
 * 페이지 레이아웃:
 * ```
 * [Header: slotCount(2) | freeSpaceOffset(2)]
 * [Slot Directory: (offset(2), length(2)) × slotCount]
 *          ... free space ...
 * [Record N] [Record N-1] ... [Record 0]  ← 페이지 끝에서부터 역방향으로 쌓임
 * ```
 */
class SlottedPage(private val buffer: ByteBuffer) {

    companion object {
        private const val HEADER_SIZE = 4                // slotCount(2) + freeSpaceOffset(2)
        private const val SLOT_ENTRY_SIZE = 4            // offset(2) + length(2)
        private const val DELETED_MARKER: Short = -1
    }

    init {
        buffer.order(ByteOrder.BIG_ENDIAN)
    }

    // --- Header accessors ---

    var slotCount: Int
        get() = buffer.getShort(0).toInt() and 0xFFFF
        private set(value) = buffer.putShort(0, value.toShort()).let {}

    var freeSpaceOffset: Int
        get() = buffer.getShort(2).toInt() and 0xFFFF
        private set(value) = buffer.putShort(2, value.toShort()).let {}

    /** 새 빈 페이지로 초기화 */
    fun init() {
        slotCount = 0
        freeSpaceOffset = DiskManager.PAGE_SIZE
    }

    /** 현재 사용 가능한 빈 공간 바이트 수 */
    val freeSpace: Int
        get() = freeSpaceOffset - HEADER_SIZE - (slotCount * SLOT_ENTRY_SIZE)

    /**
     * 레코드를 삽입하고 슬롯 ID를 반환한다.
     * 공간이 부족하면 -1을 반환한다.
     *
     * 삭제된 슬롯을 재사용할 수 있으면 슬롯 디렉터리 크기가 늘어나지 않으므로,
     * 필요 공간은 레코드 크기만으로 계산한다. 그렇지 않으면 슬롯 엔트리 1개 분량을
     * 추가로 확보해야 한다.
     */
    fun insertRecord(record: ByteArray): Int {
        val reusableSlot = findDeletedSlot()
        val requiredSpace = if (reusableSlot != null) record.size else record.size + SLOT_ENTRY_SIZE
        if (freeSpace < requiredSpace) return -1

        // 레코드를 페이지 끝에서부터 역방향으로 배치
        val recordOffset = freeSpaceOffset - record.size
        buffer.position(recordOffset)
        buffer.put(record)
        freeSpaceOffset = recordOffset

        val slotId = reusableSlot ?: run {
            val newSlotId = slotCount
            slotCount = newSlotId + 1
            newSlotId
        }

        setSlotOffset(slotId, recordOffset)
        setSlotLength(slotId, record.size)

        return slotId
    }

    /**
     * 슬롯 ID로 레코드를 조회한다.
     * 삭제된 슬롯이면 null을 반환한다.
     */
    fun getRecord(slotId: Int): ByteArray? {
        if (slotId < 0 || slotId >= slotCount) return null

        val offset = getSlotOffset(slotId)
        if (offset.toShort() == DELETED_MARKER) return null

        val length = getSlotLength(slotId)
        val record = ByteArray(length)
        buffer.position(offset)
        buffer.get(record)
        return record
    }

    /**
     * 슬롯의 레코드를 삭제한다 (논리 삭제).
     * 실제 공간 회수는 compaction에서 처리한다.
     */
    fun deleteRecord(slotId: Int): Boolean {
        if (slotId < 0 || slotId >= slotCount) return false
        if (getSlotOffset(slotId).toShort() == DELETED_MARKER) return false

        setSlotOffset(slotId, DELETED_MARKER.toInt() and 0xFFFF)
        setSlotLength(slotId, 0)
        return true
    }

    /** 페이지 내 모든 유효한 (slotId, record) 쌍을 반환 */
    fun allRecords(): List<Pair<Int, ByteArray>> {
        return (0 until slotCount).mapNotNull { slotId ->
            getRecord(slotId)?.let { slotId to it }
        }
    }

    // --- Slot directory helpers ---

    private fun slotDirectoryOffset(slotId: Int): Int = HEADER_SIZE + (slotId * SLOT_ENTRY_SIZE)

    private fun getSlotOffset(slotId: Int): Int =
        buffer.getShort(slotDirectoryOffset(slotId)).toInt() and 0xFFFF

    private fun setSlotOffset(slotId: Int, offset: Int) =
        buffer.putShort(slotDirectoryOffset(slotId), offset.toShort())

    private fun getSlotLength(slotId: Int): Int =
        buffer.getShort(slotDirectoryOffset(slotId) + 2).toInt() and 0xFFFF

    private fun setSlotLength(slotId: Int, length: Int) =
        buffer.putShort(slotDirectoryOffset(slotId) + 2, length.toShort())

    private fun findDeletedSlot(): Int? {
        for (i in 0 until slotCount) {
            if (getSlotOffset(i).toShort() == DELETED_MARKER) return i
        }
        return null
    }
}
