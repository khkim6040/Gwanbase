package gwanbase.index

import gwanbase.storage.DiskManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B+Tree의 리프/내부 노드 한 개를 나타내는 페이지 뷰.
 *
 * 페이지 내 레이아웃 (docs/specs/phase-1-kv-store.md 참조):
 * ```
 * offset  size  field
 * 0       1     nodeType (0 = internal, 1 = leaf)
 * 1       1     (reserved/padding)
 * 2       2     keyCount
 * 4       4     parentPageId
 * 8       4     nextLeafPageId       (leaf only, -1 for internal)
 * 12      4     rightmostChildPageId (internal only, -1 for leaf)
 * 16      2     freeSpaceOffset      (record 영역의 가장 낮은 offset)
 * 18      ...   slot directory (각 4바이트: offset, length)
 *               ...free space...
 *               [record N-1] ... [record 0]   ← 페이지 끝에서부터 역방향
 * ```
 *
 * 리프 레코드 포맷: `[keyLen: Short][key bytes][valueLen: Short][value bytes]`
 *
 * 현재 구현은 리프 노드에 append-only로 항목을 넣고 선형 스캔으로 조회한다.
 * 정렬 유지·이진 탐색·split은 TDD 다음 사이클에서 추가된다.
 */
class BPlusTreeNode(private val buffer: ByteBuffer) {

    init {
        buffer.order(ByteOrder.BIG_ENDIAN)
    }

    // --- Header accessors ---

    val isLeaf: Boolean
        get() = buffer.get(OFFSET_NODE_TYPE).toInt() == NODE_TYPE_LEAF

    val keyCount: Int
        get() = buffer.getShort(OFFSET_KEY_COUNT).toInt() and 0xFFFF

    var parentPageId: Int
        get() = buffer.getInt(OFFSET_PARENT_PAGE_ID)
        set(value) {
            buffer.putInt(OFFSET_PARENT_PAGE_ID, value)
        }

    var nextLeafPageId: Int
        get() = buffer.getInt(OFFSET_NEXT_LEAF_PAGE_ID)
        set(value) {
            buffer.putInt(OFFSET_NEXT_LEAF_PAGE_ID, value)
        }

    var rightmostChildPageId: Int
        get() = buffer.getInt(OFFSET_RIGHTMOST_CHILD_PAGE_ID)
        set(value) {
            buffer.putInt(OFFSET_RIGHTMOST_CHILD_PAGE_ID, value)
        }

    private var freeSpaceOffset: Int
        get() = buffer.getShort(OFFSET_FREE_SPACE_OFFSET).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(OFFSET_FREE_SPACE_OFFSET, value.toShort())
        }

    private fun setKeyCount(value: Int) {
        buffer.putShort(OFFSET_KEY_COUNT, value.toShort())
    }

    // --- Initialization ---

    fun initLeaf(parentPageId: Int) {
        buffer.put(OFFSET_NODE_TYPE, NODE_TYPE_LEAF.toByte())
        setKeyCount(0)
        this.parentPageId = parentPageId
        this.nextLeafPageId = INVALID_PAGE_ID
        buffer.putInt(OFFSET_RIGHTMOST_CHILD_PAGE_ID, INVALID_PAGE_ID)
        freeSpaceOffset = PAGE_SIZE
    }

    fun initInternal(parentPageId: Int, rightmostChildPageId: Int) {
        buffer.put(OFFSET_NODE_TYPE, NODE_TYPE_INTERNAL.toByte())
        setKeyCount(0)
        this.parentPageId = parentPageId
        this.rightmostChildPageId = rightmostChildPageId
        buffer.putInt(OFFSET_NEXT_LEAF_PAGE_ID, INVALID_PAGE_ID)
        freeSpaceOffset = PAGE_SIZE
    }

    // --- Leaf operations ---

    /**
     * 리프에 (key, value) 항목을 삽입한다.
     * 공간이 부족하면 false를 반환한다.
     *
     * 현재 구현은 정렬을 유지하지 않고 슬롯 디렉터리 끝에 append 한다.
     * 정렬 유지/이진 탐색은 후속 TDD 사이클에서 도입한다.
     */
    fun insertLeafEntry(key: ByteArray, value: ByteArray): Boolean {
        check(isLeaf) { "insertLeafEntry는 리프 노드에만 호출할 수 있다" }

        val recordSize = LEAF_RECORD_OVERHEAD + key.size + value.size
        val required = recordSize + SLOT_ENTRY_SIZE
        if (freeSpaceTop() < required) return false

        val newRecordOffset = freeSpaceOffset - recordSize
        writeLeafRecord(newRecordOffset, key, value)

        val newSlotIndex = keyCount
        writeSlot(newSlotIndex, newRecordOffset, recordSize)

        setKeyCount(keyCount + 1)
        freeSpaceOffset = newRecordOffset
        return true
    }

    /** 리프에서 [key]에 해당하는 값을 찾아 반환, 없으면 null */
    fun findValue(key: ByteArray): ByteArray? {
        check(isLeaf) { "findValue는 리프 노드에만 호출할 수 있다" }

        for (i in 0 until keyCount) {
            val slotKey = readLeafKey(i)
            if (keysEqual(slotKey, key)) {
                return readLeafValue(i)
            }
        }
        return null
    }

    // --- Private helpers ---

    /** 슬롯 디렉터리 상단에서 남는 free space (바이트) */
    private fun freeSpaceTop(): Int =
        freeSpaceOffset - HEADER_SIZE - keyCount * SLOT_ENTRY_SIZE

    private fun slotEntryOffset(slotIndex: Int): Int =
        HEADER_SIZE + slotIndex * SLOT_ENTRY_SIZE

    private fun writeSlot(slotIndex: Int, recordOffset: Int, recordLength: Int) {
        val off = slotEntryOffset(slotIndex)
        buffer.putShort(off, recordOffset.toShort())
        buffer.putShort(off + 2, recordLength.toShort())
    }

    private fun getSlotRecordOffset(slotIndex: Int): Int =
        buffer.getShort(slotEntryOffset(slotIndex)).toInt() and 0xFFFF

    private fun writeLeafRecord(recordOffset: Int, key: ByteArray, value: ByteArray) {
        buffer.putShort(recordOffset, key.size.toShort())
        for (i in key.indices) buffer.put(recordOffset + 2 + i, key[i])
        val valueStart = recordOffset + 2 + key.size
        buffer.putShort(valueStart, value.size.toShort())
        for (i in value.indices) buffer.put(valueStart + 2 + i, value[i])
    }

    private fun readLeafKey(slotIndex: Int): ByteArray {
        val recordOffset = getSlotRecordOffset(slotIndex)
        val keyLen = buffer.getShort(recordOffset).toInt() and 0xFFFF
        val out = ByteArray(keyLen)
        for (i in 0 until keyLen) out[i] = buffer.get(recordOffset + 2 + i)
        return out
    }

    private fun readLeafValue(slotIndex: Int): ByteArray {
        val recordOffset = getSlotRecordOffset(slotIndex)
        val keyLen = buffer.getShort(recordOffset).toInt() and 0xFFFF
        val valueStart = recordOffset + 2 + keyLen
        val valueLen = buffer.getShort(valueStart).toInt() and 0xFFFF
        val out = ByteArray(valueLen)
        for (i in 0 until valueLen) out[i] = buffer.get(valueStart + 2 + i)
        return out
    }

    private fun keysEqual(a: ByteArray, b: ByteArray): Boolean =
        a.contentEquals(b)

    companion object {
        /** 페이지 ID sentinel: 없음/미지정 */
        const val INVALID_PAGE_ID: Int = -1

        /** B+Tree 노드 헤더 크기(바이트) */
        const val HEADER_SIZE: Int = 18

        /** 슬롯 디렉터리 1개 엔트리 크기: recordOffset(2) + recordLength(2) */
        const val SLOT_ENTRY_SIZE: Int = 4

        /** 리프 레코드 고정 오버헤드: keyLen(2) + valueLen(2) */
        const val LEAF_RECORD_OVERHEAD: Int = 4

        private const val NODE_TYPE_INTERNAL = 0
        private const val NODE_TYPE_LEAF = 1

        private const val OFFSET_NODE_TYPE = 0
        private const val OFFSET_KEY_COUNT = 2
        private const val OFFSET_PARENT_PAGE_ID = 4
        private const val OFFSET_NEXT_LEAF_PAGE_ID = 8
        private const val OFFSET_RIGHTMOST_CHILD_PAGE_ID = 12
        private const val OFFSET_FREE_SPACE_OFFSET = 16

        private const val PAGE_SIZE = DiskManager.PAGE_SIZE
    }
}
