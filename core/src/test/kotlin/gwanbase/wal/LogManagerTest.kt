package gwanbase.wal

import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LogManagerTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var logManager: LogManager

    @BeforeEach
    fun setUp() {
        logManager = LogManager(tempDir.resolve("test.wal"))
    }

    @AfterEach
    fun tearDown() {
        logManager.close()
    }

    @Test
    fun `append 후 getRecord로 동일한 레코드 조회`() {
        val lsn = logManager.appendBegin(txnId = 1)
        lsn shouldBe 0
        val record = logManager.getRecord(0)
        record shouldBe LogRecord.Begin(lsn = 0, txnId = 1)
    }

    @Test
    fun `LSN은 append 순서대로 단조 증가한다`() {
        val lsn0 = logManager.appendBegin(txnId = 1)
        val lsn1 = logManager.appendUpdate(
            txnId = 1, prevLsn = 0, pageId = 5,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE),
        )
        val lsn2 = logManager.appendCommit(txnId = 1, prevLsn = lsn1)
        lsn0 shouldBe 0
        lsn1 shouldBe 1
        lsn2 shouldBe 2
    }

    @Test
    fun `flush 후 파일을 다시 열어도 레코드가 유지된다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.flush(1)
        logManager.close()

        val reopened = LogManager(tempDir.resolve("test.wal"))
        val records = reopened.forwardIterator(0).asSequence().toList()
        records.size shouldBe 2
        records[0] shouldBe LogRecord.Begin(lsn = 0, txnId = 1)
        records[1] shouldBe LogRecord.Commit(lsn = 1, txnId = 1, prevLsn = 0)
        reopened.close()
    }

    @Test
    fun `forwardIterator — fromLsn 이후 레코드만 순회한다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendUpdate(
            txnId = 1, prevLsn = 0, pageId = 1,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE),
        )
        logManager.appendCommit(txnId = 1, prevLsn = 1)
        logManager.flush(2)

        val records = logManager.forwardIterator(fromLsn = 1).asSequence().toList()
        records.size shouldBe 2
        records[0].lsn shouldBe 1
        records[1].lsn shouldBe 2
    }

    @Test
    fun `빈 로그 파일에서 forwardIterator는 빈 결과를 반환한다`() {
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.size shouldBe 0
    }

    @Test
    fun `lastCheckpointLsn — checkpoint가 없으면 -1`() {
        logManager.appendBegin(txnId = 1)
        logManager.lastCheckpointLsn() shouldBe -1
    }

    @Test
    fun `lastCheckpointLsn — checkpoint 이후 정확한 LSN 반환`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.appendCheckpoint()
        logManager.lastCheckpointLsn() shouldBe 2
    }

    @Test
    fun `flush 후 nextLsn이 보존된다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.flush(1)
        logManager.close()

        val reopened = LogManager(tempDir.resolve("test.wal"))
        val lsn = reopened.appendBegin(txnId = 2)
        lsn shouldBe 2
        reopened.close()
    }
}
