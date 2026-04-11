package gwanbase.storage

import mu.KotlinLogging
import java.io.IOException
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
     *
     * 페이지 단위 I/O 계약: 반환 버퍼는 항상 position=0, limit=PAGE_SIZE를 만족한다.
     * [FileChannel.read]는 단발 호출로 요청 바이트를 모두 채우는 것을 보장하지 않으므로
     * 부분 읽기/EOF를 고려해 루프를 돌며 읽는다. EOF를 만나면 나머지 영역은 0으로 유지된다.
     */
    fun readPage(pageId: Int): ByteBuffer {
        require(pageId >= 0) { "pageId must be >= 0, got $pageId" }
        val buffer = ByteBuffer.allocate(PAGE_SIZE)
        val startOffset = pageId.toLong() * PAGE_SIZE

        var totalRead = 0
        while (totalRead < PAGE_SIZE) {
            val bytesRead = channel.read(buffer, startOffset + totalRead)
            if (bytesRead <= 0) {
                // bytesRead == -1: EOF, bytesRead == 0: 더 이상 읽을 수 없음
                break
            }
            totalRead += bytesRead
        }

        if (totalRead < PAGE_SIZE) {
            logger.debug { "Page $pageId: read $totalRead bytes (padded to $PAGE_SIZE)" }
        }

        // 계약: position=0, limit=PAGE_SIZE. 부분 읽기 후에도 동일하게 유지한다.
        buffer.position(0)
        buffer.limit(PAGE_SIZE)
        return buffer
    }

    /**
     * 지정된 페이지 위치에 버퍼 내용을 기록한다.
     *
     * [FileChannel.write]는 단발 호출로 remaining 바이트 전체를 기록하는 것을 보장하지 않으므로,
     * PAGE_SIZE 바이트가 모두 쓰일 때까지 루프를 돈다. 진행이 없는 경우([IOException]) 예외를 던진다.
     * 호출 후 버퍼의 position은 limit과 동일한 상태가 된다.
     */
    fun writePage(pageId: Int, buffer: ByteBuffer) {
        require(pageId >= 0) { "pageId must be >= 0, got $pageId" }
        require(buffer.remaining() == PAGE_SIZE) {
            "Buffer must have exactly $PAGE_SIZE bytes remaining, got ${buffer.remaining()}"
        }
        val startOffset = pageId.toLong() * PAGE_SIZE

        var totalWritten = 0
        while (totalWritten < PAGE_SIZE) {
            val bytesWritten = channel.write(buffer, startOffset + totalWritten)
            if (bytesWritten <= 0) {
                throw IOException(
                    "writePage($pageId) failed: wrote $totalWritten of $PAGE_SIZE bytes",
                )
            }
            totalWritten += bytesWritten
        }
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
