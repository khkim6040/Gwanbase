package gwanbase.wal

import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LogRecordTest {

    @Test
    fun `Begin 레코드 직렬화 후 역직렬화 시 동일한 값 반환`() {
        val record = LogRecord.Begin(lsn = 0, txnId = 1)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Commit 레코드 직렬화 round-trip`() {
        val record = LogRecord.Commit(lsn = 5, txnId = 1, prevLsn = 3)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Abort 레코드 직렬화 round-trip`() {
        val record = LogRecord.Abort(lsn = 6, txnId = 2, prevLsn = 4)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Update 레코드 직렬화 round-trip`() {
        val before = ByteArray(DiskManager.PAGE_SIZE) { 0xAA.toByte() }
        val after = ByteArray(DiskManager.PAGE_SIZE) { 0xBB.toByte() }
        val record = LogRecord.Update(lsn = 1, txnId = 1, prevLsn = 0, pageId = 42, beforeImage = before, afterImage = after)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) as LogRecord.Update
        deserialized.lsn shouldBe 1
        deserialized.txnId shouldBe 1
        deserialized.pageId shouldBe 42
        deserialized.beforeImage shouldBe before
        deserialized.afterImage shouldBe after
    }

    @Test
    fun `CLR 레코드 직렬화 round-trip`() {
        val before = ByteArray(DiskManager.PAGE_SIZE) { 0xCC.toByte() }
        val record = LogRecord.CLR(lsn = 7, txnId = 1, prevLsn = 5, pageId = 10, beforeImage = before, undoNextLsn = 2)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) as LogRecord.CLR
        deserialized.undoNextLsn shouldBe 2
        deserialized.beforeImage shouldBe before
    }

    @Test
    fun `Checkpoint 레코드 직렬화 round-trip`() {
        val record = LogRecord.Checkpoint(lsn = 10)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `직렬화된 레코드의 선두와 후미에 동일한 totalLength가 기록된다`() {
        val record = LogRecord.Begin(lsn = 0, txnId = 1)
        val bytes = LogRecord.serialize(record)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val headLength = buf.getInt(0)
        val tailLength = buf.getInt(bytes.size - 4)
        headLength shouldBe tailLength
        headLength shouldBe bytes.size
    }
}
