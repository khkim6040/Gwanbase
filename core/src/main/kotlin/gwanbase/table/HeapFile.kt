package gwanbase.table

import gwanbase.storage.BufferPoolManager
import java.nio.ByteOrder

/**
 * 순서 없는 튜플 저장소 (Heap File).
 *
 * 헤더 페이지 1개와 데이터 페이지 N개로 구성된다.
 * Free Page List로 빈 공간이 있는 데이터 페이지를 관리한다.
 *
 * 헤더 페이지 레이아웃:
 * ```
 * offset  size  field
 * 0       4     firstFreePageId
 * 4       4     dataPageCount
 * 8       4×N   dataPageIds[] (데이터 페이지 ID 배열)
 * ```
 *
 * 데이터 페이지 ID 배열을 헤더에 직접 저장하므로 페이지가 비연속
 * 할당되어도 scan이 정확하다. 최대 약 1022개 데이터 페이지를 지원한다.
 */
class HeapFile(
    private val bpm: BufferPoolManager,
    val firstPageId: Int,
) {

    companion object {
        private const val OFFSET_FIRST_FREE_PAGE_ID = 0
        private const val OFFSET_DATA_PAGE_COUNT = 4
        private const val OFFSET_DATA_PAGE_IDS = 8

        /** 새 HeapFile을 생성한다. */
        fun createNew(bpm: BufferPoolManager): HeapFile {
            val headerPage = bpm.newPage() ?: error("HeapFile 헤더 페이지 할당 실패")
            val pageId = headerPage.pageId
            try {
                val buf = headerPage.data
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putInt(OFFSET_FIRST_FREE_PAGE_ID, HeapPage.INVALID_PAGE_ID)
                buf.putInt(OFFSET_DATA_PAGE_COUNT, 0)
                headerPage.isDirty = true
            } finally {
                bpm.unpinPage(pageId, isDirty = true)
            }
            return HeapFile(bpm, pageId)
        }
    }

    /** 튜플을 삽입하고 RID를 반환한다. */
    fun insertTuple(data: ByteArray): RID {
        // Free List에서 공간 있는 페이지 찾기
        var freePageId = readFirstFreePageId()

        while (freePageId != HeapPage.INVALID_PAGE_ID) {
            val page = bpm.fetchPage(freePageId) ?: error("페이지 $freePageId 조회 실패")
            try {
                val heapPage = HeapPage(page.data)
                val slotId = heapPage.insertRecord(data)
                if (slotId >= 0) {
                    page.isDirty = true
                    // 페이지가 가득 찼으면 Free List에서 제거
                    if (heapPage.freeSpace < data.size + 4) {
                        writeFirstFreePageId(heapPage.nextFreePageId)
                        heapPage.nextFreePageId = HeapPage.INVALID_PAGE_ID
                    }
                    return RID(freePageId, slotId)
                }
                // 이 페이지는 가득 참 → 다음으로
                val nextFree = heapPage.nextFreePageId
                writeFirstFreePageId(nextFree)
                heapPage.nextFreePageId = HeapPage.INVALID_PAGE_ID
                page.isDirty = true
                freePageId = nextFree
            } finally {
                bpm.unpinPage(page.pageId, isDirty = page.isDirty)
            }
        }

        // Free List가 비었으면 새 페이지 할당
        return allocateAndInsert(data)
    }

    /** RID로 튜플을 조회한다. */
    fun getTuple(rid: RID): ByteArray? {
        val page = bpm.fetchPage(rid.pageId) ?: return null
        try {
            val heapPage = HeapPage(page.data)
            return heapPage.getRecord(rid.slotId)
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = false)
        }
    }

    /** RID의 튜플을 삭제한다. */
    fun deleteTuple(rid: RID): Boolean {
        val page = bpm.fetchPage(rid.pageId) ?: return false
        try {
            val heapPage = HeapPage(page.data)
            val deleted = heapPage.deleteRecord(rid.slotId)
            if (deleted) {
                page.isDirty = true
                addToFreeListIfNeeded(rid.pageId, heapPage)
            }
            return deleted
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = page.isDirty)
        }
    }

    /**
     * 튜플을 갱신한다. 같은 슬롯에 들어가면 제자리 갱신, 아니면 delete + insert.
     * @return 갱신된 튜플의 RID
     */
    fun updateTuple(rid: RID, data: ByteArray): RID {
        val page = bpm.fetchPage(rid.pageId) ?: error("페이지 ${rid.pageId} 조회 실패")
        try {
            val heapPage = HeapPage(page.data)
            heapPage.deleteRecord(rid.slotId)
            page.isDirty = true
            // 같은 페이지에 재삽입 시도
            val slotId = heapPage.insertRecord(data)
            if (slotId >= 0) {
                return RID(rid.pageId, slotId)
            }
            addToFreeListIfNeeded(rid.pageId, heapPage)
        } finally {
            bpm.unpinPage(rid.pageId, isDirty = page.isDirty)
        }
        return insertTuple(data)
    }

    /** 전체 튜플을 순회하는 iterator를 반환한다. */
    fun scan(): Iterator<Pair<RID, ByteArray>> = HeapFileIterator()

    // --- Header 접근 ---

    private fun readFirstFreePageId(): Int {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            return page.data.getInt(OFFSET_FIRST_FREE_PAGE_ID)
        } finally {
            bpm.unpinPage(firstPageId, isDirty = false)
        }
    }

    private fun writeFirstFreePageId(pageId: Int) {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            page.data.putInt(OFFSET_FIRST_FREE_PAGE_ID, pageId)
            page.isDirty = true
        } finally {
            bpm.unpinPage(firstPageId, isDirty = true)
        }
    }

    private fun readDataPageIds(): List<Int> {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            val count = page.data.getInt(OFFSET_DATA_PAGE_COUNT)
            return (0 until count).map { i ->
                page.data.getInt(OFFSET_DATA_PAGE_IDS + i * 4)
            }
        } finally {
            bpm.unpinPage(firstPageId, isDirty = false)
        }
    }

    private fun appendDataPageId(dataPageId: Int) {
        val page = bpm.fetchPage(firstPageId) ?: error("헤더 페이지 조회 실패")
        try {
            page.data.order(ByteOrder.BIG_ENDIAN)
            val count = page.data.getInt(OFFSET_DATA_PAGE_COUNT)
            page.data.putInt(OFFSET_DATA_PAGE_IDS + count * 4, dataPageId)
            page.data.putInt(OFFSET_DATA_PAGE_COUNT, count + 1)
            page.isDirty = true
        } finally {
            bpm.unpinPage(firstPageId, isDirty = true)
        }
    }

    // --- Private helpers ---

    private fun allocateAndInsert(data: ByteArray): RID {
        val newPage = bpm.newPage() ?: error("데이터 페이지 할당 실패")
        val newPageId = newPage.pageId
        try {
            val heapPage = HeapPage(newPage.data)
            heapPage.init()
            val slotId = heapPage.insertRecord(data)
            check(slotId >= 0) { "새 페이지에 레코드 삽입 실패: 레코드가 페이지 크기를 초과" }
            newPage.isDirty = true

            // 아직 공간이 남아있으면 Free List에 추가
            addToFreeListIfNeeded(newPageId, heapPage)

            appendDataPageId(newPageId)
            return RID(newPageId, slotId)
        } finally {
            bpm.unpinPage(newPageId, isDirty = newPage.isDirty)
        }
    }

    private fun addToFreeListIfNeeded(dataPageId: Int, heapPage: HeapPage) {
        val currentFirst = readFirstFreePageId()
        // 이미 Free List에 있으면 건너뛴다
        if (dataPageId == currentFirst) return
        if (heapPage.nextFreePageId != HeapPage.INVALID_PAGE_ID) return
        heapPage.nextFreePageId = currentFirst
        writeFirstFreePageId(dataPageId)
    }

    private inner class HeapFileIterator : Iterator<Pair<RID, ByteArray>> {
        private val dataPageIds = readDataPageIds()
        private var pageIndex = 0
        private var currentRecords: List<Pair<Int, ByteArray>> = emptyList()
        private var recordIndex = 0
        private var currentDataPageId = -1

        init {
            advanceToNextNonEmptyPage()
        }

        override fun hasNext(): Boolean = recordIndex < currentRecords.size

        override fun next(): Pair<RID, ByteArray> {
            if (!hasNext()) throw NoSuchElementException()
            val (slotId, data) = currentRecords[recordIndex]
            val rid = RID(currentDataPageId, slotId)
            recordIndex++
            if (recordIndex >= currentRecords.size) {
                advanceToNextNonEmptyPage()
            }
            return rid to data
        }

        private fun advanceToNextNonEmptyPage() {
            while (pageIndex < dataPageIds.size) {
                val pageId = dataPageIds[pageIndex]
                pageIndex++
                val page = bpm.fetchPage(pageId) ?: continue
                try {
                    val heapPage = HeapPage(page.data)
                    val records = heapPage.allRecords()
                    if (records.isNotEmpty()) {
                        currentRecords = records
                        recordIndex = 0
                        currentDataPageId = pageId
                        return
                    }
                } finally {
                    bpm.unpinPage(pageId, isDirty = false)
                }
            }
            currentRecords = emptyList()
            recordIndex = 0
        }
    }
}
