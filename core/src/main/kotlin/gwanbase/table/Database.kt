package gwanbase.table

import gwanbase.sql.ExecuteResult
import gwanbase.sql.SqlExecutor
import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import gwanbase.wal.LogManager
import gwanbase.wal.RecoveryManager
import gwanbase.wal.TransactionContext
import gwanbase.wal.WalCallbackImpl
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Phase 2 데이터베이스 진입점.
 *
 * 단일 DB 파일을 관리하며 테이블 생성, 튜플 CRUD, 스캔 기능을 제공한다.
 *
 * 파일 레이아웃:
 * ```
 * pageId 0    DB 메타데이터 (magic, version, catalogPageId)
 * pageId 1    Catalog 페이지
 * pageId 2+   HeapFile 페이지들
 * ```
 */
class Database private constructor(
    private val diskManager: DiskManager,
    private val bpm: BufferPoolManager,
    private val catalog: Catalog,
    private val logManager: LogManager?,
) : AutoCloseable {

    private var closed = false
    private val sqlExecutor: SqlExecutor = SqlExecutor(this)
    private var currentTxn: TransactionContext? = null
    private var nextTxnId: Int = 0

    companion object {
        const val METADATA_PAGE_ID = 0
        const val MAGIC = 0x47574E42  // "GWNB"
        const val VERSION: Short = 3  // Phase 5: WAL 도입

        private const val OFFSET_MAGIC = 0
        private const val OFFSET_VERSION = 4
        private const val OFFSET_CATALOG_PAGE_ID = 6

        /**
         * DB 파일을 열거나 새로 생성한다.
         */
        fun open(path: Path, bufferPoolSize: Int = 256): Database {
            val dm = DiskManager(path)
            try {
                val bpm = BufferPoolManager(dm, bufferPoolSize)

                val catalog = if (dm.pageCount == 0) {
                    createFresh(bpm)
                } else {
                    loadExisting(bpm)
                }

                val logPath = path.resolveSibling(path.fileName.toString() + ".wal")
                val logManager = LogManager(logPath)

                // Recovery 수행 (WAL 콜백 연결 전 — recovery 중 추가 로깅 방지)
                val recoveryManager = RecoveryManager(logManager, bpm)
                val nextTxnId = recoveryManager.recover()

                // Catalog 재로드 (recovery가 catalog 페이지를 변경했을 수 있음)
                val recoveredCatalog = if (dm.pageCount > 0) {
                    loadExisting(bpm)
                } else {
                    catalog
                }

                val db = Database(dm, bpm, recoveredCatalog, logManager)
                db.nextTxnId = nextTxnId

                // WAL 콜백 연결 (recovery 완료 후)
                bpm.walCallback = WalCallbackImpl(logManager) { db.currentTxn }

                return db
            } catch (e: Throwable) {
                dm.close()
                throw e
            }
        }

        private fun createFresh(bpm: BufferPoolManager): Catalog {
            // pageId 0: 메타데이터 페이지
            val metaPage = bpm.newPage() ?: error("메타데이터 페이지 할당 실패")
            check(metaPage.pageId == METADATA_PAGE_ID)
            bpm.unpinPage(METADATA_PAGE_ID, isDirty = false)

            // pageId 1: Catalog 페이지
            val catalog = Catalog.createNew(bpm)

            // 메타데이터 기록
            writeMetadata(bpm, catalog.catalogPageId)
            return catalog
        }

        private fun loadExisting(bpm: BufferPoolManager): Catalog {
            val page = bpm.fetchPage(METADATA_PAGE_ID) ?: error("메타데이터 페이지 조회 실패")
            val catalogPageId: Int
            try {
                val buf = page.data
                buf.order(ByteOrder.BIG_ENDIAN)
                val magic = buf.getInt(OFFSET_MAGIC)
                check(magic == MAGIC) {
                    "DB 파일 식별자 불일치: expected ${MAGIC.toString(16)}, got ${magic.toString(16)}"
                }
                val version = buf.getShort(OFFSET_VERSION)
                check(version == VERSION || version == 2.toShort()) {
                    "DB 파일 버전 불일치: expected $VERSION, got $version"
                }
                catalogPageId = buf.getInt(OFFSET_CATALOG_PAGE_ID)
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = false)
            }
            return Catalog.load(bpm, catalogPageId)
        }

        private fun writeMetadata(bpm: BufferPoolManager, catalogPageId: Int) {
            val page = bpm.fetchPage(METADATA_PAGE_ID) ?: error("메타데이터 페이지 조회 실패")
            try {
                val buf = page.data
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putInt(OFFSET_MAGIC, MAGIC)
                buf.putShort(OFFSET_VERSION, VERSION)
                buf.putInt(OFFSET_CATALOG_PAGE_ID, catalogPageId)
                page.isDirty = true
            } finally {
                bpm.unpinPage(METADATA_PAGE_ID, isDirty = true)
            }
        }
    }

    /** 테이블을 생성한다. */
    fun createTable(name: String, schema: Schema): TableInfo {
        checkOpen()
        return catalog.createTable(name, schema)
    }

    /** 이름으로 테이블 정보를 조회한다. */
    fun getTable(name: String): TableInfo? {
        checkOpen()
        return catalog.getTable(name)
    }

    /** 튜플을 삽입하고 RID를 반환한다. */
    fun insertTuple(tableName: String, tuple: Tuple): RID {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        return heapFile.insertTuple(tuple.serialize())
    }

    /** RID로 튜플을 조회한다. */
    fun getTuple(tableName: String, rid: RID): Tuple? {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        val data = heapFile.getTuple(rid) ?: return null
        return Tuple.deserialize(info.schema, data)
    }

    /** RID의 튜플을 삭제한다. */
    fun deleteTuple(tableName: String, rid: RID): Boolean {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        return heapFile.deleteTuple(rid)
    }

    /** 테이블의 모든 튜플을 순회하는 iterator를 반환한다. */
    fun scanTable(tableName: String): Iterator<Pair<RID, Tuple>> {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        val rawIter = heapFile.scan()
        return object : Iterator<Pair<RID, Tuple>> {
            override fun hasNext() = rawIter.hasNext()
            override fun next(): Pair<RID, Tuple> {
                val (rid, data) = rawIter.next()
                return rid to Tuple.deserialize(info.schema, data)
            }
        }
    }

    /** 테이블을 삭제한다. 존재하지 않으면 false를 반환한다. */
    fun dropTable(name: String): Boolean {
        checkOpen()
        return catalog.dropTable(name)
    }

    /** 튜플을 업데이트한다. 내부적으로 삭제 후 재삽입한다. */
    fun updateTuple(tableName: String, rid: RID, tuple: Tuple): RID {
        checkOpen()
        val info = catalog.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
        return heapFile.updateTuple(rid, tuple.serialize())
    }

    /** Catalog 인스턴스를 반환한다. Binder에서 스키마 검증용으로 사용한다. */
    fun getCatalog(): Catalog {
        checkOpen()
        return catalog
    }

    /**
     * SQL 문을 auto-commit 트랜잭션으로 실행한다.
     */
    fun executeSql(sql: String): ExecuteResult {
        beginTransaction()
        try {
            val result = sqlExecutor.execute(sql)
            commitTransaction()
            return result
        } catch (e: Throwable) {
            abortTransaction()
            throw e
        }
    }

    private fun beginTransaction() {
        val txnId = nextTxnId++
        val txn = TransactionContext(txnId)
        val lm = logManager
        if (lm != null) {
            txn.lastLsn = lm.appendBegin(txnId)
        }
        currentTxn = txn
    }

    private fun commitTransaction() {
        val txn = currentTxn ?: return
        val lm = logManager
        if (lm != null) {
            val commitLsn = lm.appendCommit(txn.txnId, txn.lastLsn)
            lm.flush(commitLsn)
        }
        currentTxn = null
    }

    private fun abortTransaction() {
        val txn = currentTxn ?: return
        val lm = logManager
        if (lm != null) {
            lm.appendAbort(txn.txnId, txn.lastLsn)
        }
        currentTxn = null
    }

    override fun close() {
        if (closed) return
        bpm.flushAllPages()
        logManager?.let { lm ->
            lm.appendCheckpoint()
            lm.flush(lm.recordCount() - 1)
        }
        logManager?.close()
        diskManager.close()
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "Database is closed" }
    }
}
