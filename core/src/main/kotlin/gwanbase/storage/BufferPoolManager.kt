package gwanbase.storage

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

private val logger = KotlinLogging.logger {}

/**
 * 메모리에 고정 크기의 페이지 풀을 유지하며, 디스크 I/O를 최소화한다.
 *
 * @param diskManager 디스크 I/O 담당
 * @param poolSize 메모리에 유지할 최대 페이지 수 (= 프레임 수)
 */
class BufferPoolManager(
    private val diskManager: DiskManager,
    private val poolSize: Int,
) {
    // 프레임: 메모리 상의 페이지 슬롯
    private val pages: Array<Page> = Array(poolSize) { Page() }

    // pageId → frameId 매핑
    private val pageTable = ConcurrentHashMap<Int, Int>()

    // 사용 가능한 프레임 목록
    private val freeList = LinkedBlockingDeque<Int>()

    // LRU eviction
    private val replacer = LruReplacer(poolSize)

    /** WAL 콜백. Phase 5에서 주입된다. null이면 WAL 미사용. */
    var walCallback: WalCallback? = null

    init {
        // 모든 프레임을 free list에 등록
        for (i in 0 until poolSize) {
            freeList.add(i)
        }
    }

    /**
     * 페이지를 가져온다. 이미 버퍼에 있으면 그대로, 없으면 디스크에서 읽어온다.
     * 반환된 페이지는 pin된 상태이므로 사용 후 반드시 [unpinPage]를 호출해야 한다.
     */
    @Synchronized
    fun fetchPage(pageId: Int): Page? {
        // 1. 이미 버퍼에 있는 경우
        pageTable[pageId]?.let { frameId ->
            val page = pages[frameId]
            page.pin()
            replacer.pin(frameId)
            walCallback?.onPageFetched(pageId, page.data)
            return page
        }

        // 2. 빈 프레임 확보
        val frameId = findFreeFrame() ?: return null
        val page = pages[frameId]

        // 3. 디스크에서 읽기
        page.reset()
        page.pageId = pageId
        val diskData = diskManager.readPage(pageId)
        page.data.clear()
        page.data.put(diskData)
        page.data.flip()
        page.pin()

        pageTable[pageId] = frameId
        replacer.pin(frameId)

        logger.debug { "Fetched page $pageId into frame $frameId" }
        walCallback?.onPageFetched(pageId, page.data)
        return page
    }

    /**
     * 새 페이지를 할당한다. 디스크에 빈 페이지를 만들고 버퍼에 올린다.
     */
    @Synchronized
    fun newPage(): Page? {
        val frameId = findFreeFrame() ?: return null
        val page = pages[frameId]

        val newPageId = diskManager.allocatePage()
        page.reset()
        page.pageId = newPageId
        page.pin()
        page.isDirty = true

        pageTable[newPageId] = frameId
        replacer.pin(frameId)

        logger.debug { "Created new page $newPageId in frame $frameId" }
        walCallback?.onPageFetched(newPageId, page.data)
        return page
    }

    /**
     * 페이지 사용 완료를 알린다.
     * @param isDirty 이 페이지에 쓰기를 했으면 true
     */
    @Synchronized
    fun unpinPage(pageId: Int, isDirty: Boolean = false): Boolean {
        val frameId = pageTable[pageId] ?: return false
        val page = pages[frameId]

        if (page.pinCount <= 0) return false

        if (isDirty) {
            page.isDirty = true
            val lsn = walCallback?.onPageDirtyUnpin(pageId, page.data) ?: -1
            if (lsn >= 0) page.pageLsn = lsn
        }
        page.unpin()

        if (page.pinCount == 0) {
            replacer.unpin(frameId)
        }
        return true
    }

    /**
     * 특정 페이지를 디스크에 기록한다.
     */
    @Synchronized
    fun flushPage(pageId: Int): Boolean {
        val frameId = pageTable[pageId] ?: return false
        val page = pages[frameId]

        walCallback?.ensureLogFlushed(page.pageLsn)
        page.data.rewind()
        diskManager.writePage(pageId, page.data)
        page.data.rewind()
        page.isDirty = false

        logger.debug { "Flushed page $pageId" }
        return true
    }

    /**
     * 모든 페이지를 디스크에 기록한다.
     *
     * 다른 public API와 락 정책을 일치시키기 위해 @Synchronized를 적용한다.
     * (락을 잡지 않으면 순회 중 다른 스레드가 pageTable을 변경해 flush가 누락될 수 있다.)
     */
    @Synchronized
    fun flushAllPages() {
        // toList로 스냅샷을 만들어 내부 flushPage 호출 중 map 변경 가능성을 차단한다.
        pageTable.keys.toList().forEach { flushPage(it) }
        diskManager.sync()
    }

    private fun findFreeFrame(): Int? {
        // free list에서 먼저 찾기
        freeList.pollFirst()?.let { return it }

        // LRU victim 선택
        val victimFrameId = replacer.victim() ?: return null
        val victimPage = pages[victimFrameId]

        // dirty면 디스크에 기록
        if (victimPage.isDirty) {
            walCallback?.ensureLogFlushed(victimPage.pageLsn)
            victimPage.data.rewind()
            diskManager.writePage(victimPage.pageId, victimPage.data)
            victimPage.data.rewind()
        }

        pageTable.remove(victimPage.pageId)
        logger.debug { "Evicted page ${victimPage.pageId} from frame $victimFrameId" }
        return victimFrameId
    }
}
