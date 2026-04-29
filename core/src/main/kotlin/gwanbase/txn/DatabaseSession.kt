package gwanbase.txn

import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.table.RID
import gwanbase.table.Tuple
import gwanbase.wal.LogRecord
import gwanbase.wal.TransactionContext

/**
 * 데이터베이스 세션.
 *
 * 세션별로 독립적인 트랜잭션 상태를 관리하며, SQL 실행의 진입점이 된다.
 * auto-commit 모드와 명시적 트랜잭션(BEGIN/COMMIT/ROLLBACK)을 모두 지원한다.
 */
class DatabaseSession(
    internal val database: Database,
    private val lockManager: LockManager,
) : AutoCloseable {

    private var currentTxn: TransactionContext? = null
    private val sqlExecutor: SqlExecutor = SqlExecutor(database, session = this)

    /**
     * SQL 문을 실행한다.
     *
     * BEGIN/COMMIT/ROLLBACK은 트랜잭션 제어로 처리한다.
     * 활성 트랜잭션이 없으면 auto-commit 모드로 실행한다.
     */
    fun executeSql(sql: String): ExecuteResult {
        val tokens = Lexer(sql).tokenize()
        val statement = Parser(tokens).parse()

        return when (statement) {
            is Statement.Begin -> {
                begin()
                ExecuteResult.TransactionStarted
            }
            is Statement.Commit -> {
                commit()
                ExecuteResult.TransactionCommitted
            }
            is Statement.Rollback -> {
                rollback()
                ExecuteResult.TransactionRolledBack
            }
            else -> {
                val binder = Binder(database.getCatalog())
                binder.bind(statement)

                val autoCommit = (currentTxn == null)
                if (autoCommit) beginInternal()
                try {
                    val result = sqlExecutor.executeStatement(statement)
                    if (autoCommit) commitInternal(currentTxn!!)
                    result
                } catch (e: Throwable) {
                    if (currentTxn != null) {
                        abortInternal(currentTxn!!)
                    }
                    throw e
                }
            }
        }
    }

    /** 명시적 트랜잭션을 시작한다. */
    fun begin() {
        check(currentTxn == null) { "이미 활성 트랜잭션이 있다" }
        beginInternal()
    }

    /** 활성 트랜잭션을 커밋하고 모든 잠금을 해제한다. */
    fun commit() {
        val txn = currentTxn ?: error("활성 트랜잭션이 없다")
        commitInternal(txn)
    }

    /** 활성 트랜잭션을 롤백하고 모든 잠금을 해제한다. */
    fun rollback() {
        val txn = currentTxn ?: error("활성 트랜잭션이 없다")
        abortInternal(txn)
    }

    /** 테이블 스캔 시 각 행에 S 잠금을 획득하는 래퍼. */
    internal fun scanTableWithLock(tableName: String): Iterator<Pair<RID, Tuple>> {
        val txn = currentTxn
        val rawIter = database.scanTable(tableName)
        if (txn == null) return rawIter
        return object : Iterator<Pair<RID, Tuple>> {
            override fun hasNext() = rawIter.hasNext()
            override fun next(): Pair<RID, Tuple> {
                val (rid, tuple) = rawIter.next()
                lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.SHARED)
                return rid to tuple
            }
        }
    }

    /** INSERT 시 삽입된 행에 X 잠금을 획득하는 래퍼. */
    internal fun insertTupleWithLock(tableName: String, tuple: Tuple): RID {
        val rid = database.insertTuple(tableName, tuple)
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
        }
        return rid
    }

    /** DELETE 시 대상 행에 X 잠금을 획득하는 래퍼. */
    internal fun deleteTupleWithLock(tableName: String, rid: RID): Boolean {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
        }
        return database.deleteTuple(tableName, rid)
    }

    /** UPDATE 시 대상 행에 X 잠금을 획득하는 래퍼. */
    internal fun updateTupleWithLock(tableName: String, rid: RID, tuple: Tuple): RID {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
        }
        return database.updateTuple(tableName, rid, tuple)
    }

    override fun close() {
        if (currentTxn != null) {
            abortInternal(currentTxn!!)
        }
    }

    private fun beginInternal() {
        val txnId = database.allocateTxnId()
        val txn = TransactionContext(txnId)
        database.logManager?.let { lm ->
            txn.lastLsn = lm.appendBegin(txnId)
        }
        currentTxn = txn
        database.currentTxnHolder.set(txn)
    }

    private fun commitInternal(txn: TransactionContext) {
        database.logManager?.let { lm ->
            val commitLsn = lm.appendCommit(txn.txnId, txn.lastLsn)
            lm.flush(commitLsn)
        }
        lockManager.releaseAll(txn.txnId)
        currentTxn = null
        database.currentTxnHolder.remove()
    }

    /**
     * 트랜잭션을 중단하고 dirty page의 before-image를 복원한다.
     *
     * currentTxnHolder를 먼저 제거하여 복원 중 WalCallback이 추가 로그를 기록하지 않도록 한다.
     */
    private fun abortInternal(txn: TransactionContext) {
        currentTxn = null
        database.currentTxnHolder.remove()

        database.logManager?.let { lm ->
            // Runtime undo: Update 로그의 before-image를 버퍼 풀에 복원
            var lsn = txn.lastLsn
            while (lsn >= 0) {
                val record = lm.getRecord(lsn)
                when (record) {
                    is LogRecord.Update -> {
                        val page = database.bpm.fetchPage(record.pageId)
                        if (page != null) {
                            page.data.clear()
                            page.data.put(record.beforeImage)
                            page.data.flip()
                            database.bpm.unpinPage(record.pageId, isDirty = true)
                        }
                        lsn = record.prevLsn
                    }
                    is LogRecord.Begin -> break
                    else -> lsn = record.prevLsn
                }
            }
            val abortLsn = lm.appendAbort(txn.txnId, txn.lastLsn)
            lm.flush(abortLsn)
        }
        lockManager.releaseAll(txn.txnId)
    }
}
