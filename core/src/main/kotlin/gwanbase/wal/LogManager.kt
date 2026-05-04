package gwanbase.wal

import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * WAL(Write-Ahead Log) 로그 매니저.
 *
 * 로그 레코드를 인메모리 리스트에 보관하고, [flush] 호출 시 디스크에 영속화한다.
 * LSN(Log Sequence Number)은 0부터 시작하는 단조 증가 정수이며, 리스트의 인덱스와 동일하다.
 *
 * 파일 포맷은 [LogRecord.serialize]가 생성하는 바이너리 레코드를 순서대로 이어 붙인 형태다.
 * 재시작 시 파일이 존재하면 [loadFromDisk]로 전체 레코드를 복원한다.
 *
 * @param path WAL 파일 경로
 */
class LogManager(private val path: Path) : AutoCloseable {

    private val records: MutableList<LogRecord> = mutableListOf()
    private var nextLsn: Int = 0
    private var flushedLsn: Int = -1
    private var _lastCheckpointLsn: Int = -1

    private val channel: FileChannel = FileChannel.open(
        path,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
    )

    init {
        if (channel.size() > 0) {
            loadFromDisk()
        }
    }

    // ─── append 메서드 ─────────────────────────────────────────────────────────

    /**
     * 트랜잭션 시작 레코드를 추가하고 할당된 LSN을 반환한다.
     */
    @Synchronized
    fun appendBegin(txnId: Int): Int {
        val lsn = nextLsn++
        records.add(LogRecord.Begin(lsn = lsn, txnId = txnId))
        logger.debug { "appendBegin: lsn=$lsn txnId=$txnId" }
        return lsn
    }

    /**
     * 트랜잭션 커밋 레코드를 추가하고 할당된 LSN을 반환한다.
     */
    @Synchronized
    fun appendCommit(txnId: Int, prevLsn: Int): Int {
        val lsn = nextLsn++
        records.add(LogRecord.Commit(lsn = lsn, txnId = txnId, prevLsn = prevLsn))
        logger.debug { "appendCommit: lsn=$lsn txnId=$txnId" }
        return lsn
    }

    /**
     * 트랜잭션 중단 레코드를 추가하고 할당된 LSN을 반환한다.
     */
    @Synchronized
    fun appendAbort(txnId: Int, prevLsn: Int): Int {
        val lsn = nextLsn++
        records.add(LogRecord.Abort(lsn = lsn, txnId = txnId, prevLsn = prevLsn))
        logger.debug { "appendAbort: lsn=$lsn txnId=$txnId" }
        return lsn
    }

    /**
     * 페이지 수정 레코드를 추가하고 할당된 LSN을 반환한다.
     *
     * @param beforeImage 수정 이전 페이지 전체(4KB)
     * @param afterImage 수정 이후 페이지 전체(4KB)
     */
    @Synchronized
    fun appendUpdate(
        txnId: Int,
        prevLsn: Int,
        pageId: Int,
        beforeImage: ByteArray,
        afterImage: ByteArray,
    ): Int {
        val lsn = nextLsn++
        records.add(
            LogRecord.Update(
                lsn = lsn,
                txnId = txnId,
                prevLsn = prevLsn,
                pageId = pageId,
                beforeImage = beforeImage,
                afterImage = afterImage,
            )
        )
        logger.debug { "appendUpdate: lsn=$lsn txnId=$txnId pageId=$pageId" }
        return lsn
    }

    /**
     * CLR(Compensation Log Record)을 추가하고 할당된 LSN을 반환한다.
     *
     * @param beforeImage undo 후 페이지 상태(4KB)
     * @param undoNextLsn 다음에 undo할 레코드의 LSN
     */
    @Synchronized
    fun appendCLR(
        txnId: Int,
        prevLsn: Int,
        pageId: Int,
        beforeImage: ByteArray,
        undoNextLsn: Int,
    ): Int {
        val lsn = nextLsn++
        records.add(
            LogRecord.CLR(
                lsn = lsn,
                txnId = txnId,
                prevLsn = prevLsn,
                pageId = pageId,
                beforeImage = beforeImage,
                undoNextLsn = undoNextLsn,
            )
        )
        logger.debug { "appendCLR: lsn=$lsn txnId=$txnId pageId=$pageId" }
        return lsn
    }

    /**
     * Consistent Checkpoint 레코드를 추가하고 할당된 LSN을 반환한다.
     * [_lastCheckpointLsn]도 갱신한다.
     */
    @Synchronized
    fun appendCheckpoint(): Int {
        val lsn = nextLsn++
        records.add(LogRecord.Checkpoint(lsn = lsn))
        _lastCheckpointLsn = lsn
        logger.debug { "appendCheckpoint: lsn=$lsn" }
        return lsn
    }

    // ─── 조회 메서드 ───────────────────────────────────────────────────────────

    /**
     * 지정한 LSN의 레코드를 반환한다.
     *
     * @throws IndexOutOfBoundsException LSN이 범위를 벗어난 경우
     */
    @Synchronized
    fun getRecord(lsn: Int): LogRecord = records[lsn]

    /**
     * [fromLsn] 이상인 레코드를 순서대로 순회하는 이터레이터를 반환한다.
     * [fromLsn]이 현재 레코드 수 이상이면 빈 이터레이터를 반환한다.
     */
    @Synchronized
    fun forwardIterator(fromLsn: Int): Iterator<LogRecord> {
        if (fromLsn >= records.size) return emptyList<LogRecord>().iterator()
        return records.subList(fromLsn, records.size).iterator()
    }

    /**
     * 마지막 체크포인트 레코드의 LSN을 반환한다.
     * 체크포인트가 없으면 -1을 반환한다.
     */
    fun lastCheckpointLsn(): Int = _lastCheckpointLsn

    /**
     * 현재 인메모리 레코드 수를 반환한다.
     */
    @Synchronized
    fun recordCount(): Int = records.size

    // ─── I/O 메서드 ────────────────────────────────────────────────────────────

    /**
     * [upToLsn] 이하의 미플러시 레코드를 디스크에 기록하고 fsync한다.
     *
     * 이미 플러시된 레코드는 건너뛰고, 새로 기록된 레코드만 추가로 파일에 쓴다.
     */
    @Synchronized
    fun flush(upToLsn: Int) {
        val from = flushedLsn + 1
        val to = minOf(upToLsn, records.size - 1)
        if (from > to) return

        for (lsn in from..to) {
            val bytes = LogRecord.serialize(records[lsn])
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
        }
        channel.force(true)
        flushedLsn = to
        logger.debug { "flush: flushedLsn=$flushedLsn" }
    }

    /**
     * FileChannel을 닫는다.
     */
    override fun close() {
        channel.close()
    }

    // ─── 내부 메서드 ───────────────────────────────────────────────────────────

    /**
     * 파일 전체를 읽어 인메모리 레코드 리스트를 복원한다.
     * nextLsn, flushedLsn, _lastCheckpointLsn을 파일 내용에 맞게 초기화한다.
     */
    private fun loadFromDisk() {
        val fileSize = channel.size()
        val buf = ByteBuffer.allocate(fileSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        channel.position(0)
        while (buf.hasRemaining()) {
            val read = channel.read(buf)
            if (read == -1) break
        }
        buf.flip()

        while (buf.hasRemaining()) {
            val record = LogRecord.deserialize(buf)
            records.add(record)
            if (record is LogRecord.Checkpoint) {
                _lastCheckpointLsn = record.lsn
            }
        }

        nextLsn = records.size
        flushedLsn = records.size - 1
        logger.debug { "loadFromDisk: 레코드 ${records.size}개 복원, nextLsn=$nextLsn" }
    }
}
