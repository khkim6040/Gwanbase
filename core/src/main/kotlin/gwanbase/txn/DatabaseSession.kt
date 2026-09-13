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
 * 한 세션의 호출은 동시에 실행되면 안 되지만(호출자가 직렬화), 호출마다 다른 스레드여도 된다.
 */
class DatabaseSession(
    internal val database: Database,
    private val lockManager: LockManager,
) : AutoCloseable {

    private var currentTxn: TransactionContext? = null

    /**
     * 명시적 트랜잭션 안에서 오류가 난 뒤 ROLLBACK/COMMIT을 받기 전까지 true.
     *
     * PostgreSQL은 오류 시점에 `AbortTransaction()`으로 undo와 잠금 해제를 즉시 끝내고, 블록 상태만
     * `TBLOCK_ABORT`로 남겨 이후 문장을 25P02로 거부한다. Gwanbase도 abort는 즉시 하고 이 플래그만 남긴다.
     * https://github.com/postgres/postgres/blob/master/src/backend/access/transam/xact.c
     * (`AbortTransaction`, `AbortCurrentTransaction`, `EndTransactionBlock`의 `TBLOCK_ABORT` 분기)
     */
    private var txnFailed = false

    /** 트랜잭션 상태. PostgreSQL ReadyForQuery·libpq `PQtransactionStatus`와 같은 I(idle) / T(진행 중) / E(실패) 3값. */
    val txnStatus: Char
        get() = when {
            txnFailed -> 'E'
            currentTxn != null -> 'T'
            else -> 'I'
        }

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
        if (txnFailed) return executeInFailedTxn(sql)
        // 호출 스레드에 이 세션의 활성 트랜잭션을 바인딩하고 끝나면 반드시 푼다.
        // WalCallbackImpl은 ThreadLocal로 현재 트랜잭션을 찾으므로, 세션이 요청마다 다른
        // 스레드에서 실행되는 환경(스레드 풀)에서는 호출 단위로 바인딩해야 한다.
        database.currentTxnHolder.set(currentTxn)
        try {
            val tokens = Lexer(sql).tokenize()
            val statement = Parser(tokens).parse()
            return execute(statement)
        } catch (e: ParseException) {
            failTransaction()
            throw e
        } finally {
            database.currentTxnHolder.remove()
        }
    }

    /**
     * 실패한 트랜잭션 블록의 문장 처리. abort는 이미 끝났으므로 ROLLBACK/COMMIT은 상태만 정리한다.
     * PostgreSQL도 aborted 블록의 COMMIT은 오류 없이 `ROLLBACK` 태그를 돌려준다
     * (`EndTransactionBlock()`이 false 반환 → `standard_ProcessUtility`가 `CMDTAG_ROLLBACK` 설정).
     */
    private fun executeInFailedTxn(sql: String): ExecuteResult {
        val statement = runCatching { Parser(Lexer(sql).tokenize()).parse() }.getOrNull()
        if (statement !is Statement.Rollback && statement !is Statement.Commit) throw TransactionAbortedException()
        txnFailed = false
        return ExecuteResult.TransactionRolledBack
    }

    /** 명시적 트랜잭션 안에서 오류가 났을 때: 즉시 abort하고 실패 상태로 남긴다. auto-commit이면 아무것도 안 한다. */
    private fun failTransaction() {
        val txn = currentTxn ?: return
        abortInternal(txn)
        txnFailed = true
    }

    private fun execute(statement: Statement): ExecuteResult {
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
                val autoCommit = (currentTxn == null)
                if (autoCommit) beginInternal()
                try {
                    Binder(database.getCatalog()).bind(statement)
                    val result = sqlExecutor.executeStatement(statement)
                    if (autoCommit) commitInternal(currentTxn!!)
                    result
                } catch (e: Throwable) {
                    if (autoCommit) currentTxn?.let { abortInternal(it) } else failTransaction()
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
     * 충돌 행을 자기 자신이 잠그고 있으면 S 잠금이 즉시 반환되어 재시도가 곧바로 다시 실패한다.
     *
     * PostgreSQL은 `_bt_check_unique()`가 충돌 튜플의 삽입 xid를 돌려주면 `_bt_doinsert()`가
     * `XactLockTableWait()`로 그 트랜잭션의 종료를 기다린 뒤 재검사한다. 행 잠금 대신 xid를
     * 기다린다는 점만 다르고 의미는 같다.
     * - https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtinsert.c
     *   (`_bt_check_unique`, `_bt_doinsert`)
     * - https://github.com/postgres/postgres/blob/master/src/backend/executor/execIndexing.c
     *   (파일 상단 주석: 동시 삽입 시 유일성 보장 방식)
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
