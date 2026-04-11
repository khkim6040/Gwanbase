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
     * 슬롯 디렉터리는 항상 키 오름차순(unsigned lexicographic)으로 유지된다.
     *
     * 이미 같은 키가 존재하면 기존 슬롯을 새 레코드로 덮어쓴다 (키 개수 불변).
     * 공간이 부족하면 false를 반환한다.
     *
     * split은 후속 TDD 사이클에서 추가된다. 중복 키 갱신 시 이전 레코드는
     * 논리적으로 dead space가 되며 compaction은 구현하지 않는다.
     */
    fun insertLeafEntry(key: ByteArray, value: ByteArray): Boolean {
        check(isLeaf) { "insertLeafEntry는 리프 노드에만 호출할 수 있다" }

        val recordSize = LEAF_RECORD_OVERHEAD + key.size + value.size
        val (insertPos, matched) = binarySearchLeafSlot(key)

        if (matched) {
            // 기존 슬롯을 재사용한다. 새 슬롯 엔트리는 필요 없다.
            if (freeSpaceTop() < recordSize) return false
            val newRecordOffset = freeSpaceOffset - recordSize
            writeLeafRecord(newRecordOffset, key, value)
            writeSlot(insertPos, newRecordOffset, recordSize)
            freeSpaceOffset = newRecordOffset
            return true
        }

        val required = recordSize + SLOT_ENTRY_SIZE
        if (freeSpaceTop() < required) return false

        val newRecordOffset = freeSpaceOffset - recordSize
        writeLeafRecord(newRecordOffset, key, value)

        shiftSlotsRight(insertPos)
        writeSlot(insertPos, newRecordOffset, recordSize)

        setKeyCount(keyCount + 1)
        freeSpaceOffset = newRecordOffset
        return true
    }

    /** 리프에서 [key]에 해당하는 값을 찾아 반환, 없으면 null */
    fun findValue(key: ByteArray): ByteArray? {
        check(isLeaf) { "findValue는 리프 노드에만 호출할 수 있다" }

        val (idx, matched) = binarySearchLeafSlot(key)
        return if (matched) readLeafValue(idx) else null
    }

    /** 리프에 저장된 모든 (key, value)를 키 오름차순으로 반환 */
    fun leafEntries(): List<Pair<ByteArray, ByteArray>> {
        check(isLeaf) { "leafEntries는 리프 노드에만 호출할 수 있다" }
        return (0 until keyCount).map { i -> readLeafKey(i) to readLeafValue(i) }
    }

    // --- Split operations ---

    /**
     * 리프 노드를 두 개로 분할한다.
     *
     * 현재(왼쪽) 노드는 엔트리의 앞쪽 절반을 유지하고,
     * [newRightNode]에는 뒤쪽 절반을 복사한다. 리프 체인
     * (nextLeafPageId)도 자동으로 갱신된다.
     *
     * @param newRightNode 새로 할당된 빈 리프 노드 (이미 initLeaf 호출됨)
     * @param newRightPageId newRightNode가 위치한 페이지 ID
     *   (현재 노드의 nextLeafPageId를 이 값으로 업데이트한다)
     * @return 새 오른쪽 노드의 첫 번째 키 (부모로 promote할 separator)
     */
    fun splitLeaf(newRightNode: BPlusTreeNode, newRightPageId: Int): ByteArray {
        check(isLeaf) { "splitLeaf는 리프 노드에만 호출할 수 있다" }
        check(newRightNode.isLeaf) { "splitLeaf의 대상 노드는 리프여야 한다" }
        check(keyCount >= 2) { "splitLeaf는 최소 2개 엔트리가 필요하다" }

        val entries = leafEntries()
        val mid = entries.size / 2
        val leftEntries = entries.subList(0, mid)
        val rightEntries = entries.subList(mid, entries.size)

        val origParent = parentPageId
        val origNext = nextLeafPageId

        // 왼쪽(현재) 노드를 리셋한 뒤 앞쪽 절반 재삽입
        initLeaf(origParent)
        nextLeafPageId = newRightPageId
        for ((k, v) in leftEntries) insertLeafEntry(k, v)

        // 오른쪽 노드 구성
        newRightNode.parentPageId = origParent
        newRightNode.nextLeafPageId = origNext
        for ((k, v) in rightEntries) newRightNode.insertLeafEntry(k, v)

        return rightEntries.first().first
    }

    /**
     * 내부 노드를 두 개로 분할한다.
     *
     * 왼쪽(현재) 노드는 앞쪽 mid-1개 슬롯을 유지하고, slots[mid].child가
     * 왼쪽의 새 rightmostChild가 된다. slots[mid].key는 부모로 promote되며
     * 양쪽 어느 노드에도 남지 않는다 (B+Tree 내부 노드의 표준 split 규약).
     * [newRightNode]는 slots[mid+1..n-1]와 원래 rightmostChild를 가져간다.
     *
     * @param newRightNode 새로 할당된 빈 내부 노드 (initInternal 호출됨)
     * @return 부모로 promote할 separator 키
     */
    fun splitInternal(newRightNode: BPlusTreeNode): ByteArray {
        check(!isLeaf) { "splitInternal은 내부 노드에만 호출할 수 있다" }
        check(!newRightNode.isLeaf) { "splitInternal의 대상 노드는 내부 노드여야 한다" }
        check(keyCount >= 2) { "splitInternal은 최소 2개 키가 필요하다" }

        val pairs = (0 until keyCount).map { i -> readInternalKey(i) to readInternalChild(i) }
        val origRightmost = rightmostChildPageId
        val origParent = parentPageId

        val mid = pairs.size / 2
        val leftPairs = pairs.subList(0, mid)
        val promoteKey = pairs[mid].first
        val midChild = pairs[mid].second
        val rightPairs = pairs.subList(mid + 1, pairs.size)

        // 왼쪽(현재) 노드를 리셋한 뒤 앞쪽 절반 재구성.
        // 왼쪽의 rightmostChild는 원래 slots[mid].child가 된다.
        initInternal(parentPageId = origParent, rightmostChildPageId = midChild)
        for ((k, c) in leftPairs) insertInternalEntry(k, c)

        // 오른쪽 노드 구성: 뒤쪽 절반 + 원래 rightmost
        newRightNode.parentPageId = origParent
        newRightNode.rightmostChildPageId = origRightmost
        for ((k, c) in rightPairs) newRightNode.insertInternalEntry(k, c)

        return promoteKey
    }

    // --- Internal node operations ---

    /**
     * 내부 노드에 (separator key, childPageId) 항목을 삽입한다.
     * 슬롯 디렉터리는 키 오름차순으로 유지된다.
     *
     * 규약: slot[i].childPageId는 key < slot[i].key를 만족하는 서브트리.
     * 가장 큰 키 이상의 영역은 [rightmostChildPageId]가 담당한다.
     *
     * B+Tree의 separator key는 유일해야 하므로 동일 키 재삽입은 허용하지 않는다.
     * 공간이 부족하면 false를 반환한다.
     */
    fun insertInternalEntry(key: ByteArray, childPageId: Int): Boolean {
        check(!isLeaf) { "insertInternalEntry는 내부 노드에만 호출할 수 있다" }

        val recordSize = INTERNAL_RECORD_OVERHEAD + key.size
        val (insertPos, matched) = binarySearchInternalSlot(key)
        check(!matched) {
            "내부 노드 separator key 중복: ${String(key, Charsets.UTF_8)}"
        }

        val required = recordSize + SLOT_ENTRY_SIZE
        if (freeSpaceTop() < required) return false

        val newRecordOffset = freeSpaceOffset - recordSize
        writeInternalRecord(newRecordOffset, key, childPageId)

        shiftSlotsRight(insertPos)
        writeSlot(insertPos, newRecordOffset, recordSize)

        setKeyCount(keyCount + 1)
        freeSpaceOffset = newRecordOffset
        return true
    }

    /**
     * 내부 노드에서 [key]를 포함하는 자식 페이지 ID를 반환한다.
     *
     * 알고리즘: slot 배열에 대해 upper_bound([key])를 구한다.
     * 즉 가장 작은 i에서 slot[i].key > key인 i를 찾는다.
     * - 그런 i가 존재하면 slot[i].childPageId를 반환
     * - 아니면(키가 모든 separator 이상) [rightmostChildPageId]를 반환
     */
    fun findChild(key: ByteArray): Int {
        check(!isLeaf) { "findChild는 내부 노드에만 호출할 수 있다" }

        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKeysUnsigned(readInternalKey(mid), key)
            if (cmp > 0) hi = mid else lo = mid + 1
        }
        return if (lo < keyCount) readInternalChild(lo) else rightmostChildPageId
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

    // --- Internal node record helpers ---

    private fun writeInternalRecord(recordOffset: Int, key: ByteArray, childPageId: Int) {
        buffer.putShort(recordOffset, key.size.toShort())
        for (i in key.indices) buffer.put(recordOffset + 2 + i, key[i])
        buffer.putInt(recordOffset + 2 + key.size, childPageId)
    }

    private fun readInternalKey(slotIndex: Int): ByteArray {
        val recordOffset = getSlotRecordOffset(slotIndex)
        val keyLen = buffer.getShort(recordOffset).toInt() and 0xFFFF
        val out = ByteArray(keyLen)
        for (i in 0 until keyLen) out[i] = buffer.get(recordOffset + 2 + i)
        return out
    }

    private fun readInternalChild(slotIndex: Int): Int {
        val recordOffset = getSlotRecordOffset(slotIndex)
        val keyLen = buffer.getShort(recordOffset).toInt() and 0xFFFF
        return buffer.getInt(recordOffset + 2 + keyLen)
    }

    /** 내부 노드 슬롯에 대한 이진 탐색 (일치/삽입 위치) */
    private fun binarySearchInternalSlot(key: ByteArray): Pair<Int, Boolean> {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKeysUnsigned(readInternalKey(mid), key)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid
                else -> return mid to true
            }
        }
        return lo to false
    }

    /**
     * 슬롯 디렉터리 안에서 [key]의 위치를 이진 탐색으로 찾는다.
     *
     * @return (insertPosition, matched). matched가 true이면 insertPosition이
     *   키와 동일한 기존 슬롯의 인덱스이다. false이면 insertPosition이 이 키를
     *   새로 삽입했을 때 자리잡을 슬롯 인덱스이다.
     */
    private fun binarySearchLeafSlot(key: ByteArray): Pair<Int, Boolean> {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKeysUnsigned(readLeafKey(mid), key)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid
                else -> return mid to true
            }
        }
        return lo to false
    }

    /** unsigned lexicographic byte 비교 */
    private fun compareKeysUnsigned(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /** 슬롯 [fromSlotIndex, keyCount)의 디렉터리 엔트리를 오른쪽으로 한 칸 밀어낸다 */
    private fun shiftSlotsRight(fromSlotIndex: Int) {
        val src = slotEntryOffset(fromSlotIndex)
        val end = slotEntryOffset(keyCount)
        for (i in end - 1 downTo src) {
            buffer.put(i + SLOT_ENTRY_SIZE, buffer.get(i))
        }
    }

    companion object {
        /** 페이지 ID sentinel: 없음/미지정 */
        const val INVALID_PAGE_ID: Int = -1

        /** B+Tree 노드 헤더 크기(바이트) */
        const val HEADER_SIZE: Int = 18

        /** 슬롯 디렉터리 1개 엔트리 크기: recordOffset(2) + recordLength(2) */
        const val SLOT_ENTRY_SIZE: Int = 4

        /** 리프 레코드 고정 오버헤드: keyLen(2) + valueLen(2) */
        const val LEAF_RECORD_OVERHEAD: Int = 4

        /** 내부 노드 레코드 고정 오버헤드: keyLen(2) + childPageId(4) */
        const val INTERNAL_RECORD_OVERHEAD: Int = 6

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
