package gwanbase.storage

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Buffer Pool에서 관리되는 메모리 상의 페이지.
 *
 * [data]는 디스크 페이지와 1:1 대응되는 바이트 버퍼이다.
 * pin count가 0보다 크면 이 페이지는 eviction 대상에서 제외된다.
 */
class Page(
    var pageId: Int = INVALID_PAGE_ID,
) {
    companion object {
        const val INVALID_PAGE_ID: Int = -1
    }

    val data: ByteBuffer = ByteBuffer.allocateDirect(DiskManager.PAGE_SIZE)

    /** 이 페이지를 사용 중인 스레드 수 */
    @Volatile
    var pinCount: Int = 0
        private set

    /** 디스크에 쓰기가 필요한지 여부 */
    @Volatile
    var isDirty: Boolean = false

    private val latch = ReentrantReadWriteLock()

    fun pin() {
        pinCount++
    }

    fun unpin() {
        check(pinCount > 0) { "Cannot unpin a page with pinCount 0" }
        pinCount--
    }

    /** 읽기 락을 잡고 블록을 실행 */
    fun <T> readLatch(block: () -> T): T = latch.read(block)

    /** 쓰기 락을 잡고 블록을 실행 */
    fun <T> writeLatch(block: () -> T): T = latch.write(block)

    /** 페이지 내용과 메타데이터를 초기화 */
    fun reset() {
        pageId = INVALID_PAGE_ID
        data.clear()
        // zero fill
        val zeros = ByteArray(DiskManager.PAGE_SIZE)
        data.put(zeros)
        data.flip()
        pinCount = 0
        isDirty = false
    }
}
