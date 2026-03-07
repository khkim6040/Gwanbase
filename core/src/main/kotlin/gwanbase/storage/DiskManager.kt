package gwanbase.storage

import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * 디스크의 데이터 파일을 고정 크기 페이지 단위로 관리한다.
 *
 * 모든 디스크 I/O는 이 클래스를 통해 이루어진다.
 * 페이지 ID는 0부터 시작하며, 파일 내 오프셋은 pageId * PAGE_SIZE로 결정된다.
 */
class DiskManager(
    private val dbPath: Path,
) : AutoCloseable {

    companion object {
        const val PAGE_SIZE: Int = 4096 // 4KB
    }

    private val channel: FileChannel = FileChannel.open(
        dbPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    )

    /** 현재 파일이 가진 총 페이지 수 */
    val pageCount: Int
        get() = (channel.size() / PAGE_SIZE).toInt()

    /**
     * 지정된 페이지를 읽어 ByteBuffer에 담아 반환한다.
     * 반환되는 버퍼의 position은 0, limit은 PAGE_SIZE이다.
     */
    fun readPage(pageId: Int): ByteBuffer {
        require(pageId >= 0) { "pageId must be >= 0, got $pageId" }
        val buffer = ByteBuffer.allocate(PAGE_SIZE)
        val offset = pageId.toLong() * PAGE_SIZE
        val bytesRead = channel.read(buffer, offset)
        if (bytesRead < PAGE_SIZE) {
            // 파일 끝을 넘어선 읽기 → 나머지는 0으로 채워짐
            logger.debug { "Page $pageId: read $bytesRead bytes (padded to $PAGE_SIZE)" }
        }
        buffer.flip()
        return buffer
    }

    /**
     * 지정된 페이지 위치에 버퍼 내용을 기록한다.
     * 버퍼의 position부터 remaining 바이트를 기록한다.
     */
    fun writePage(pageId: Int, buffer: ByteBuffer) {
        require(pageId >= 0) { "pageId must be >= 0, got $pageId" }
        require(buffer.remaining() == PAGE_SIZE) {
            "Buffer must have exactly $PAGE_SIZE bytes remaining, got ${buffer.remaining()}"
        }
        val offset = pageId.toLong() * PAGE_SIZE
        channel.write(buffer, offset)
    }

    /**
     * 새 페이지를 파일 끝에 할당하고 해당 페이지 ID를 반환한다.
     */
    fun allocatePage(): Int {
        val newPageId = pageCount
        val emptyPage = ByteBuffer.allocate(PAGE_SIZE)
        writePage(newPageId, emptyPage)
        logger.debug { "Allocated new page: $newPageId" }
        return newPageId
    }

    /** 디스크에 즉시 flush */
    fun sync() {
        channel.force(true)
    }

    override fun close() {
        channel.close()
    }
}
