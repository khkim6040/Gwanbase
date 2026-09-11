package gwanbase.txn

import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.table.RID
import gwanbase.table.Tuple
import gwanbase.table.UniqueViolationException
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

    /**
     * 잠금 최대 대기 시간 (ms). 0이면 무한 대기. PostgreSQL `lock_timeout` GUC에 해당한다.
     * 초과 시 LockTimeoutException(55P03)이 발생하고 트랜잭션은 abort된다.
     */
    var lockTimeoutMillis: Long = 0
        set(value) {
            require(value >= 0) { "lockTimeoutMillis는 0 이상이어야 한다: $value" }
            field = value
        }
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
                lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.SHARED, lockTimeoutMillis)
                return rid to tuple
            }
        }
    }

    /** INSERT 시 삽입된 행에 X 잠금을 획득하는 래퍼. */
    internal fun insertTupleWithLock(tableName: String, tuple: Tuple): RID {
        val rid = waitForConflictingRow(tableName) { database.insertTuple(tableName, tuple) }
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE, lockTimeoutMillis)
        }
        return rid
    }

    /**
     * 유일 제약 위반 시 충돌 행의 잠금을 기다린 뒤 한 번 재시도한다.
     *
     * 충돌 행이 다른 트랜잭션의 미커밋 삽입이라면 그 행에는 X 잠금이 걸려 있으므로,
     * S 잠금 요청은 상대가 commit/abort할 때까지 블록된다. 상대가 abort하면 행이 사라져
     * 재시도가 성공하고, commit하면 재시도에서 다시 위반이 발생해 그대로 전파된다.
     * PostgreSQL `_bt_check_unique()`가 `XactLockTableWait()`로 삽입 트랜잭션의 종료를
     * 기다리는 것과 같은 의미다. 충돌 행을 자기 자신이 잠그고 있으면 S 잠금이 즉시 반환되어
     * 재시도가 곧바로 다시 실패한다.
     */
    private fun <T> waitForConflictingRow(tableName: String, action: () -> T): T {
        return try {
            action()
        } catch (e: UniqueViolationException) {
            val txn = currentTxn ?: throw e
            lockManager.acquire(txn.txnId, LockTarget(tableName, e.conflictingRid), LockMode.SHARED, lockTimeoutMillis)
            action()
        }
    }

    /** DELETE 시 대상 행에 X 잠금을 획득하는 래퍼. */
    internal fun deleteTupleWithLock(tableName: String, rid: RID): Boolean {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE, lockTimeoutMillis)
        }
        return database.deleteTuple(tableName, rid)
    }

    /** UPDATE 시 대상 행에 X 잠금을 획득하는 래퍼. */
    internal fun updateTupleWithLock(tableName: String, rid: RID, tuple: Tuple): RID {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE, lockTimeoutMillis)
        }
        return database.updateTuple(tableName, rid, tuple)
    }

    /** S 잠금을 획득한다. IndexScanOperator 등에서 개별 행에 잠금을 걸 때 사용한다. */
    internal fun acquireSharedLock(tableName: String, rid: RID) {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.SHARED, lockTimeoutMillis)
        }
    }

    /** X 잠금만 획득하고 실제 업데이트는 하지 않는다. 잠금 후 재조회를 위해 사용한다. */
    internal fun acquireExclusiveLock(tableName: String, rid: RID) {
        currentTxn?.let { txn ->
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE, lockTimeoutMillis)
        }
    }

    /** 이미 X 잠금을 보유한 상태에서 튜플을 업데이트한다. WAL 로깅은 정상적으로 수행된다. */
    internal fun updateTupleWithLockAlreadyHeld(tableName: String, rid: RID, tuple: Tuple): RID {
        return waitForConflictingRow(tableName) { database.updateTuple(tableName, rid, tuple) }
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
     * WAL 프로토콜에 따라 각 undo 동작마다 CLR(Compensation Log Record)을 기록한다.
     * crash 시 Recovery의 Redo 단계에서 CLR이 재적용되어 undo의 내구성을 보장한다.
     *
     * WalCallback이 undo 중 추가 Update 로그를 기록하지 않도록 currentTxnHolder를
     * undo 시작 전에 제거하되, CLR은 LogManager에 직접 기록한다.
     */
    private fun abortInternal(txn: TransactionContext) {
        // WalCallback이 before-image 복원 시 추가 Update 로그를 쓰지 않도록 제거
        database.currentTxnHolder.remove()

        database.logManager?.let { lm ->
            // Runtime undo: Update 로그의 before-image를 버퍼 풀에 복원하고 CLR 기록
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
                        // CLR 기록: undo 동작의 내구성을 보장
                        val clrLsn = lm.appendCLR(
                            txnId = txn.txnId,
                            prevLsn = txn.lastLsn,
                            pageId = record.pageId,
                            beforeImage = record.beforeImage,
                            undoNextLsn = record.prevLsn,
                        )
                        txn.lastLsn = clrLsn
                        lsn = record.prevLsn
                    }
                    is LogRecord.Begin -> break
                    else -> lsn = record.prevLsn
                }
            }
            val abortLsn = lm.appendAbort(txn.txnId, txn.lastLsn)
            lm.flush(abortLsn)
        }
        currentTxn = null
        lockManager.releaseAll(txn.txnId)
    }
}
