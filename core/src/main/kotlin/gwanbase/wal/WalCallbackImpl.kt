package gwanbase.wal

import gwanbase.storage.DiskManager
import gwanbase.storage.WalCallback
import java.nio.ByteBuffer

/**
 * [WalCallback]의 구현체.
 *
 * BPM 이벤트를 받아 LogManager에 로그 레코드를 기록하고,
 * TransactionContext에 before-image를 캡처한다.
 */
class WalCallbackImpl(
    private val logManager: LogManager,
    private val txnProvider: () -> TransactionContext?,
) : WalCallback {

    override fun ensureLogFlushed(pageLsn: Int) {
        logManager.flush(pageLsn)
    }

    override fun onPageFetched(pageId: Int, data: ByteBuffer) {
        txnProvider()?.captureBeforeImage(pageId, data)
    }

    override fun onPageDirtyUnpin(pageId: Int, data: ByteBuffer): Int {
        val txn = txnProvider() ?: return -1
        val beforeImage = txn.getAndRemoveBeforeImage(pageId) ?: return -1
        val afterImage = captureImage(data)
        val lsn = logManager.appendUpdate(
            txnId = txn.txnId,
            prevLsn = txn.lastLsn,
            pageId = pageId,
            beforeImage = beforeImage,
            afterImage = afterImage,
        )
        txn.lastLsn = lsn
        return lsn
    }

    private fun captureImage(data: ByteBuffer): ByteArray {
        val snapshot = ByteArray(DiskManager.PAGE_SIZE)
        val pos = data.position()
        data.rewind()
        data.get(snapshot)
        data.position(pos)
        return snapshot
    }
}
