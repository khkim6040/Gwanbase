package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HeapFileTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createHeapFile(poolSize: Int = 64): Pair<HeapFile, BufferPoolManager> {
        val dm = DiskManager(tempDir.resolve("heap_test.db"))
        val bpm = BufferPoolManager(dm, poolSize)
        val heapFile = HeapFile.createNew(bpm)
        return heapFile to bpm
    }

    @Test
    fun `빈 HeapFile - scan 결과 없음`() {
        val (heapFile, _) = createHeapFile()
        val results = heapFile.scan().asSequence().toList()
        results shouldHaveSize 0
    }

    @Test
    fun `단일 삽입 및 조회`() {
        val (heapFile, _) = createHeapFile()
        val data = "Hello, HeapFile!".toByteArray()

        val rid = heapFile.insertTuple(data)
        rid.shouldNotBeNull()

        val result = heapFile.getTuple(rid)
        result shouldBe data
    }

    @Test
    fun `여러 건 삽입 후 모두 조회`() {
        val (heapFile, _) = createHeapFile()
        val entries = (0 until 100).map { "record-$it".toByteArray() }
        val rids = entries.map { heapFile.insertTuple(it) }

        rids.zip(entries).forEach { (rid, expected) ->
            heapFile.getTuple(rid) shouldBe expected
        }
    }

    @Test
    fun `삭제 후 getTuple은 null`() {
        val (heapFile, _) = createHeapFile()
        val rid = heapFile.insertTuple("delete-me".toByteArray())

        heapFile.deleteTuple(rid) shouldBe true
        heapFile.getTuple(rid).shouldBeNull()
    }

    @Test
    fun `삭제 후 재삽입 - 공간 재활용`() {
        val (heapFile, _) = createHeapFile()

        val rids = (0 until 50).map { heapFile.insertTuple(ByteArray(50) { it.toByte() }) }
        rids.take(10).forEach { heapFile.deleteTuple(it) }

        val newRids = (0 until 10).map { heapFile.insertTuple(ByteArray(50) { it.toByte() }) }
        newRids.forEach { rid ->
            heapFile.getTuple(rid).shouldNotBeNull()
        }
    }

    @Test
    fun `여러 페이지에 걸친 scan`() {
        val (heapFile, _) = createHeapFile()
        // 각 레코드 200바이트 × 100건 → 여러 페이지 필요
        val entries = (0 until 100).map { i ->
            ByteArray(200) { (i % 128).toByte() }
        }
        val rids = entries.map { heapFile.insertTuple(it) }

        val scanned = heapFile.scan().asSequence().toList()
        scanned shouldHaveSize 100
        scanned.map { it.first }.toSet() shouldBe rids.toSet()
    }

    @Test
    fun `updateTuple - 같은 크기 데이터`() {
        val (heapFile, _) = createHeapFile()
        val rid = heapFile.insertTuple("old-value".toByteArray())

        val newRid = heapFile.updateTuple(rid, "new-value".toByteArray())
        heapFile.getTuple(newRid) shouldBe "new-value".toByteArray()
    }

    @Test
    fun `updateTuple - 페이지에 공간이 부족하면 다른 페이지에 삽입`() {
        val (heapFile, _) = createHeapFile()
        // 38 × 100바이트로 페이지를 채움 (freeSpace=136, Free List 잔류)
        repeat(38) { heapFile.insertTuple(ByteArray(100)) }
        val rid = heapFile.insertTuple(ByteArray(10))

        // 같은 페이지에 들어가지 않는 큰 데이터로 업데이트
        val newRid = heapFile.updateTuple(rid, ByteArray(3000))
        // 원래 RID는 삭제됨
        heapFile.getTuple(rid).shouldBeNull()
        // 새 위치에서 조회 가능
        heapFile.getTuple(newRid) shouldBe ByteArray(3000)
        // 다른 페이지에 삽입됨
        newRid.pageId shouldNotBe rid.pageId
    }

    @Test
    fun `대량 삽입 10000건 - 전체 scan 일치`() {
        val (heapFile, _) = createHeapFile()
        val count = 10_000
        val rids = (0 until count).map { i ->
            heapFile.insertTuple("row-$i".toByteArray())
        }

        val scanned = heapFile.scan().asSequence().toList()
        scanned shouldHaveSize count
    }

    @Test
    fun `Buffer Pool 압박 - poolSize 4로 정상 동작`() {
        val dm = DiskManager(tempDir.resolve("small_pool.db"))
        val bpm = BufferPoolManager(dm, 4)
        val heapFile = HeapFile.createNew(bpm)

        val count = 500
        val rids = (0 until count).map { i ->
            heapFile.insertTuple("row-$i".toByteArray())
        }

        rids.forEach { rid ->
            heapFile.getTuple(rid).shouldNotBeNull()
        }
    }
}
