package gwanbase.storage

import java.nio.ByteBuffer

/**
 * BufferPoolManager가 WAL 계층에 이벤트를 전달하기 위한 콜백 인터페이스.
 *
 * storage 패키지가 wal 패키지에 직접 의존하지 않도록 의존성을 역전시킨다.
 * wal 패키지에서 이 인터페이스를 구현한다.
 */
interface WalCallback {
    /**
     * dirty page를 디스크에 쓰기 전에 호출된다.
     * WAL 불변식: pageLsn까지 로그가 flush되어야 한다.
     */
    fun ensureLogFlushed(pageLsn: Int)

    /**
     * 페이지가 fetch될 때 호출된다.
     * 활성 트랜잭션이 있으면 before-image를 캡처한다.
     */
    fun onPageFetched(pageId: Int, data: ByteBuffer)

    /**
     * 페이지가 dirty 상태로 unpin될 때 호출된다.
     * Update 로그 레코드를 기록하고, 할당된 LSN을 반환한다.
     * 로깅이 불필요하면 -1을 반환한다.
     */
    fun onPageDirtyUnpin(pageId: Int, data: ByteBuffer): Int
}
