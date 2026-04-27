package gwanbase.wal

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class RecoveryManagerTest {

    @TempDir lateinit var tempDir: Path

    private fun createBpmAndDm(): Pair<BufferPoolManager, DiskManager> {
        val dm = DiskManager(tempDir.resolve("test.db"))
        val bpm = BufferPoolManager(dm, 256)
        return bpm to dm
    }

    @Test
    fun `Redo — 커밋된 트랜잭션의 Update가 페이지에 반영된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지 할당 (pageId=0)
        val page = bpm.newPage()!!
        val pageId = page.pageId
        bpm.unpinPage(pageId)

        // 커밋된 트랜잭션 로그 작성
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 12345)
        val beforeImage = ByteArray(DiskManager.PAGE_SIZE)

        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = beforeImage, afterImage = afterImage)
        logManager.appendCommit(txnId = 0, prevLsn = 1)
        logManager.flush(2)

        // Recovery 실행
        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        // 페이지에 after-image가 반영되어야 한다
        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 12345
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `Undo — 미커밋 트랜잭션의 Update가 롤백된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지 할당
        val page = bpm.newPage()!!
        val pageId = page.pageId
        bpm.unpinPage(pageId)

        // 미커밋 트랜잭션 로그 (Commit 없음)
        val beforeImage = ByteArray(DiskManager.PAGE_SIZE)
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 99999)

        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = beforeImage, afterImage = afterImage)
        logManager.flush(1)

        // Recovery — Redo로 afterImage 적용 후, Undo로 beforeImage 복원
        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 0 // before-image (zeros) 복원
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `Redo skip — pageLsn이 이미 높으면 재적용하지 않는다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지를 할당하고 pageLsn을 높게 설정
        val page = bpm.newPage()!!
        val pageId = page.pageId
        page.data.order(ByteOrder.BIG_ENDIAN)
        page.data.putInt(0, 77777)
        page.pageLsn = 100
        bpm.unpinPage(pageId, isDirty = true)
        bpm.flushPage(pageId)

        // LSN 1인 Update — pageLsn(100) > record.lsn(1)이므로 skip
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 12345)
        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE), afterImage = afterImage)
        logManager.appendCommit(txnId = 0, prevLsn = 1)
        logManager.flush(2)

        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 77777 // redo skip
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `빈 로그에서 recovery는 아무 작업 없이 성공한다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        val recoveryManager = RecoveryManager(logManager, bpm)
        val nextTxnId = recoveryManager.recover()
        nextTxnId shouldBe 0

        logManager.close()
        dm.close()
    }

    @Test
    fun `CLR — Undo 시 CLR이 기록된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        val page = bpm.newPage()!!
        bpm.unpinPage(page.pageId)

        // 미커밋 트랜잭션
        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = page.pageId,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE))
        logManager.flush(1)

        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        // Recovery 후 로그에 CLR과 Abort가 추가되어야 한다
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.any { it is LogRecord.CLR } shouldBe true
        records.any { it is LogRecord.Abort } shouldBe true

        logManager.close()
        dm.close()
    }
}
