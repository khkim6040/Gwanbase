package gwanbase.txn

import gwanbase.table.RID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * 잠금 대상을 식별하는 키.
 */
data class LockTarget(val tableName: String, val rid: RID)

/**
 * 잠금 모드.
 */
enum class LockMode {
    /** 공유 잠금 (읽기). 다른 SHARED와 호환. */
    SHARED,
    /** 배타 잠금 (쓰기). 다른 모든 잠금과 비호환. */
    EXCLUSIVE,
}

/**
 * 데드락 발생 시 던지는 예외.
 */
class DeadlockException(val victimTxnId: Int) : RuntimeException(
    "데드락 감지: 트랜잭션 $victimTxnId 이 victim으로 선택됨"
)

/**
 * RID 기반 행 수준 잠금 관리자.
 *
 * Strict 2PL을 지원한다. 잠금은 acquire()로 획득하고,
 * 트랜잭션 종료 시 releaseAll()로 일괄 해제한다.
 */
class LockManager {

    /** 잠금 보유자 정보. */
    internal data class TxnHolder(val txnId: Int, val mode: LockMode)

    /** 잠금 대기 요청. */
    internal data class LockRequest(val txnId: Int, val mode: LockMode, val latch: CountDownLatch)

    /** 대상별 잠금 상태. */
    internal class LockEntry {
        val holders: MutableSet<TxnHolder> = mutableSetOf()
        val waitQueue: MutableList<LockRequest> = mutableListOf()
    }

    private val locks = ConcurrentHashMap<LockTarget, LockEntry>()

    /**
     * 잠금을 획득한다.
     *
     * 호환되지 않는 잠금이 있으면 대기한다.
     *
     * @param txnId 트랜잭션 ID
     * @param target 잠금 대상
     * @param mode 잠금 모드
     * @throws DeadlockException 데드락이 감지된 경우
     */
    fun acquire(txnId: Int, target: LockTarget, mode: LockMode) {
        val entry = locks.computeIfAbsent(target) { LockEntry() }

        synchronized(entry) {
            // 이미 같은 모드 이상을 보유 중이면 즉시 반환
            val existing = entry.holders.find { it.txnId == txnId }
            if (existing != null) {
                if (existing.mode == mode || existing.mode == LockMode.EXCLUSIVE) {
                    return
                }
                // S → X 업그레이드: 다른 보유자가 없으면 즉시 업그레이드
                if (mode == LockMode.EXCLUSIVE) {
                    val otherHolders = entry.holders.filter { it.txnId != txnId }
                    if (otherHolders.isEmpty()) {
                        entry.holders.remove(existing)
                        entry.holders.add(TxnHolder(txnId, LockMode.EXCLUSIVE))
                        return
                    }
                    // 다른 보유자 있음 → 대기 (Task 2에서 구현)
                    val request = LockRequest(txnId, mode, CountDownLatch(1))
                    entry.waitQueue.add(request)
                    // TODO: Task 2에서 대기 로직 완성
                    return
                }
            }

            // 호환 가능한지 확인
            if (isCompatible(entry, txnId, mode)) {
                entry.holders.add(TxnHolder(txnId, mode))
                return
            }

            // 호환 불가 → 대기 (Task 2에서 구현)
            val request = LockRequest(txnId, mode, CountDownLatch(1))
            entry.waitQueue.add(request)
            // TODO: Task 2에서 대기 로직 완성
        }
    }

    /**
     * 트랜잭션의 모든 잠금을 해제하고 대기 중인 트랜잭션을 깨운다.
     */
    fun releaseAll(txnId: Int) {
        for ((_, entry) in locks) {
            synchronized(entry) {
                val removed = entry.holders.removeAll { it.txnId == txnId }
                if (removed) {
                    grantWaiters(entry)
                }
                entry.waitQueue.removeAll { it.txnId == txnId }
            }
        }
    }

    private fun isCompatible(entry: LockEntry, txnId: Int, mode: LockMode): Boolean {
        if (entry.holders.isEmpty()) return true
        if (entry.waitQueue.isNotEmpty()) return false
        return when (mode) {
            LockMode.SHARED -> entry.holders.all { it.mode == LockMode.SHARED || it.txnId == txnId }
            LockMode.EXCLUSIVE -> entry.holders.all { it.txnId == txnId }
        }
    }

    private fun grantWaiters(entry: LockEntry) {
        val iterator = entry.waitQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (isCompatible(entry, request.txnId, request.mode)) {
                iterator.remove()
                request.latch.countDown()
            }
        }
    }
}
