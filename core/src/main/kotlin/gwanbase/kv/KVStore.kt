package gwanbase.kv

import gwanbase.index.BPlusTree
import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Phase 1 Persistent Key-Value Store.
 *
 * 디스크 파일 하나를 4KB 페이지 단위로 관리하며, 고정 위치 메타데이터
 * 페이지(pageId=0)와 그 뒤에 이어지는 B+Tree 페이지들로 구성된다.
 * 모든 public 메서드는 [AutoCloseable] 라이프사이클을 따르며, [close] 이후
 * 호출은 [IllegalStateException]을 발생시킨다.
 *
 * 동시성: 단일 스레드 가정 (Phase 6에서 확장).
 * Crash 일관성: Phase 5 (WAL) 도입 전까지 보장 없음.
 *
 * 파일 레이아웃:
 * ```
 * page 0: 메타데이터 (magic, version, rootPageId, ...)
 * page 1..: B+Tree 노드들
 * ```
 */
class KVStore internal constructor(
    private val diskManager: DiskManager,
    private val bpm: BufferPoolManager,
    private val tree: BPlusTree,
) : AutoCloseable {

    private var closed: Boolean = false

    /** [key]에 [value]를 저장한다. 이미 있는 키는 갱신한다. */
    fun put(key: ByteArray, value: ByteArray) {
        checkOpen()
        tree.insert(key, value)
    }

    /** [key]에 해당하는 값을 반환한다. 없으면 null. */
    fun get(key: ByteArray): ByteArray? {
        checkOpen()
        return tree.search(key)
    }

    /**
     * [key]를 제거한다.
     * @return 해당 키가 있었으면 true, 없었으면 false
     */
    fun delete(key: ByteArray): Boolean {
        checkOpen()
        return tree.delete(key)
    }

    /**
     * [startKey] 이상 [endKey] 미만 범위의 (key, value) 쌍을 키 오름차순으로
     * 반환하는 lazy iterator.
     */
    fun scan(startKey: ByteArray, endKey: ByteArray): Iterator<Pair<ByteArray, ByteArray>> {
        checkOpen()
        return tree.scan(startKey, endKey)
    }

    override fun close() {
        if (closed) return
        // 루트가 바뀌었을 수 있으므로 항상 메타데이터를 다시 써둔다.
        writeMetadataPage(bpm, tree.rootPageId)
        bpm.flushAllPages()
        diskManager.close()
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "KVStore is closed" }
    }

    companion object {
        /** 메타데이터 페이지는 파일 맨 앞에 고정된 페이지 ID로 존재한다. */
        const val METADATA_PAGE_ID: Int = 0

        /** 파일 포맷 식별자: "GWNB" */
        const val MAGIC: Int = 0x47574E42

        /** 파일 포맷 버전 */
        const val VERSION: Short = 1

        private const val OFFSET_MAGIC = 0
        private const val OFFSET_VERSION = 4
        private const val OFFSET_ROOT_PAGE_ID = 6
        private const val OFFSET_FIRST_LEAF_PAGE_ID = 10

        /**
         * [path]에 위치한 파일을 열어 KVStore를 반환한다.
         *
         * - 파일이 비어 있으면 메타데이터 페이지와 새 B+Tree 루트를 만든다.
         * - 기존 파일이면 메타데이터에서 rootPageId를 복원한다.
         *
         * @param bufferPoolSize 버퍼 풀에 유지할 페이지 수 (기본 256 = 1MB)
         */
        fun open(path: Path, bufferPoolSize: Int = 256): KVStore {
            val diskManager = DiskManager(path)
            val bpm = BufferPoolManager(diskManager, bufferPoolSize)

            val tree = if (diskManager.pageCount == 0) {
                createFreshStore(bpm)
            } else {
                loadExistingStore(bpm)
            }

            return KVStore(diskManager, bpm, tree)
        }

        private fun createFreshStore(bpm: BufferPoolManager): BPlusTree {
            // 메타데이터 페이지(pageId=0)를 먼저 할당한다.
            val metaPage = bpm.newPage() ?: error("메타데이터 페이지 할당 실패")
            check(metaPage.pageId == METADATA_PAGE_ID) {
                "메타데이터 페이지는 pageId 0이어야 한다 (got ${metaPage.pageId})"
            }
            bpm.unpinPage(METADATA_PAGE_ID, isDirty = true)

            // B+Tree 루트 페이지(pageId=1 이상)를 생성한다.
            val tree = BPlusTree.createNew(bpm)

            // 메타데이터를 채워 쓴다.
            writeMetadataPage(bpm, tree.rootPageId)
            return tree
        }

        private fun loadExistingStore(bpm: BufferPoolManager): BPlusTree {
            val metaPage = bpm.fetchPage(METADATA_PAGE_ID)
                ?: error("메타데이터 페이지 조회 실패")
            val rootPageId: Int
            try {
                val buffer = metaPage.data
                buffer.order(ByteOrder.BIG_ENDIAN)
                val magic = buffer.getInt(OFFSET_MAGIC)
                check(magic == MAGIC) {
                    "KVStore 파일 식별자 불일치: expected ${MAGIC.toString(16)}, got ${magic.toString(16)}"
                }
                val version = buffer.getShort(OFFSET_VERSION).toInt() and 0xFFFF
                check(version == VERSION.toInt()) {
                    "지원하지 않는 KVStore 파일 버전: $version"
                }
                rootPageId = buffer.getInt(OFFSET_ROOT_PAGE_ID)
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = false)
            }
            return BPlusTree(bpm, rootPageId)
        }

        private fun writeMetadataPage(bpm: BufferPoolManager, rootPageId: Int) {
            val page = bpm.fetchPage(METADATA_PAGE_ID)
                ?: error("메타데이터 페이지 조회 실패")
            try {
                val buffer = page.data
                buffer.order(ByteOrder.BIG_ENDIAN)
                buffer.putInt(OFFSET_MAGIC, MAGIC)
                buffer.putShort(OFFSET_VERSION, VERSION)
                buffer.putInt(OFFSET_ROOT_PAGE_ID, rootPageId)
                buffer.putInt(OFFSET_FIRST_LEAF_PAGE_ID, -1)
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = true)
            }
        }
    }
}
