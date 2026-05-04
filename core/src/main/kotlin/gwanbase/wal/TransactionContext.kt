package gwanbase.wal

import gwanbase.storage.DiskManager
import java.nio.ByteBuffer

/**
 * 현재 활성 트랜잭션의 상태를 추적한다.
 *
 * before-image 맵으로 페이지 수정 전 상태를 보존하며,
 * prevLsn 체인을 위해 마지막 LSN을 유지한다.
 */
class TransactionContext(val txnId: Int) {

    /** 이 트랜잭션의 마지막 로그 레코드 LSN. Begin의 LSN으로 초기화. */
    var lastLsn: Int = -1

    /** pageId → before-image. 페이지가 처음 fetch될 때 캡처된다. */
    private val beforeImages = mutableMapOf<Int, ByteArray>()

    /**
     * 페이지의 before-image를 캡처한다.
     * 같은 트랜잭션 내에서 이미 캡처된 페이지는 무시한다.
     */
    fun captureBeforeImage(pageId: Int, data: ByteBuffer) {
        if (pageId in beforeImages) return
        val snapshot = ByteArray(DiskManager.PAGE_SIZE)
        val pos = data.position()
        data.rewind()
        data.get(snapshot)
        data.position(pos)
        beforeImages[pageId] = snapshot
    }

    /**
     * before-image를 가져오고 맵에서 제거한다.
     * 제거하여 같은 페이지의 다음 fetch에서 새로운 before-image를 캡처할 수 있게 한다.
     */
    fun getAndRemoveBeforeImage(pageId: Int): ByteArray? =
        beforeImages.remove(pageId)
}
