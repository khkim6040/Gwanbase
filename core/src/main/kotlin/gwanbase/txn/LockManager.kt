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

        while (true) {
            var request: LockRequest? = null
            var blockers: Set<Int> = emptySet()

            synchronized(entry) {
                val existing = entry.holders.find { it.txnId == txnId }
                when {
                    existing != null && (existing.mode == mode || existing.mode == LockMode.EXCLUSIVE) -> {
                        // 이미 같은 모드 이상을 보유 중이면 즉시 반환
                        return
                    }
                    existing != null && mode == LockMode.EXCLUSIVE -> {
                        // S → X 업그레이드
                        val otherHolders = entry.holders.filter { it.txnId != txnId }
                        if (otherHolders.isEmpty()) {
                            // 다른 보유자 없음: 즉시 업그레이드
                            entry.holders.remove(existing)
                            entry.holders.add(TxnHolder(txnId, LockMode.EXCLUSIVE))
                            return
                        }
                        // 다른 보유자 있음: 대기 요청 생성 후 synchronized 블록 밖에서 대기
                        request = LockRequest(txnId, mode, CountDownLatch(1))
                        entry.waitQueue.add(request!!)
                        // blockers를 synchronized 블록 안에서 캡처
                        blockers = otherHolders.map { it.txnId }.toSet()
                    }
                    isCompatible(entry, txnId, mode) -> {
                        // 호환 가능: 즉시 획득
                        entry.holders.add(TxnHolder(txnId, mode))
                        return
                    }
                    else -> {
                        // 호환 불가: 대기 요청 생성 후 synchronized 블록 밖에서 대기
                        request = LockRequest(txnId, mode, CountDownLatch(1))
                        entry.waitQueue.add(request!!)
                        // blockers를 synchronized 블록 안에서 캡처
                        blockers = entry.holders
                            .filter { it.txnId != txnId }
                            .map { it.txnId }
                            .toSet()
                    }
                }
            }

            // synchronized 블록 밖에서 데드락 감지 후 대기 — releaseAll()이 진입할 수 있도록
            if (request != null) {
                if (detectDeadlock(txnId, blockers)) {
                    synchronized(entry) {
                        entry.waitQueue.removeAll { it.txnId == txnId }
                    }
                    throw DeadlockException(txnId)
                }
                request!!.latch.await()
                // 깨어난 후 holders에 추가
                synchronized(entry) {
                    val existing = entry.holders.find { it.txnId == txnId }
                    if (existing != null && mode == LockMode.EXCLUSIVE) {
                        // S→X 업그레이드: 기존 S 잠금 제거 후 X 잠금 추가
                        entry.holders.remove(existing)
                    }
                    entry.holders.add(TxnHolder(txnId, mode))
                }
                return
            }
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

    /**
     * 새 잠금 요청이 현재 보유자 및 대기열과 호환되는지 확인한다.
     * 대기열이 비어 있지 않으면 공정성을 위해 false를 반환한다 (큐 점프 방지).
     */
    private fun isCompatible(entry: LockEntry, txnId: Int, mode: LockMode): Boolean {
        if (entry.holders.isEmpty()) return true
        if (entry.waitQueue.isNotEmpty()) return false
        return isCompatibleWithHolders(entry, txnId, mode)
    }

    /**
     * 대기열을 무시하고 현재 보유자와만 호환성을 확인한다.
     * grantWaiters 내부에서 대기 요청을 평가할 때 사용한다.
     */
    private fun isCompatibleWithHolders(entry: LockEntry, txnId: Int, mode: LockMode): Boolean {
        if (entry.holders.isEmpty()) return true
        return when (mode) {
            LockMode.SHARED -> entry.holders.all { it.mode == LockMode.SHARED || it.txnId == txnId }
            LockMode.EXCLUSIVE -> entry.holders.all { it.txnId == txnId }
        }
    }

    private fun grantWaiters(entry: LockEntry) {
        val iterator = entry.waitQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            // 대기 요청을 먼저 제거한 뒤 호환성을 확인해야
            // isCompatible이 자기 자신을 대기열에서 보지 않는다
            iterator.remove()
            if (isCompatibleWithHolders(entry, request.txnId, request.mode)) {
                request.latch.countDown()
            } else {
                // 호환 불가: 다시 대기열 앞에 복원하고 중단 (FIFO 순서 유지)
                entry.waitQueue.add(0, request)
                break
            }
        }
    }

    /**
     * 요청자 트랜잭션에서 시작하는 Waits-For 사이클이 있는지 검사한다.
     *
     * @param requestorTxnId 잠금을 요청한 트랜잭션 ID
     * @param blockers 요청자를 차단하는 트랜잭션 ID 집합
     * @return 사이클이 존재하면 true
     */
    private fun detectDeadlock(requestorTxnId: Int, blockers: Set<Int>): Boolean {
        val visited = mutableSetOf(requestorTxnId)
        val stack = ArrayDeque(blockers.toList())

        while (stack.isNotEmpty()) {
            val txnId = stack.removeFirst()
            if (txnId == requestorTxnId) return true
            if (!visited.add(txnId)) continue
            stack.addAll(getWaitingFor(txnId))
        }
        return false
    }

    /**
     * 주어진 트랜잭션이 대기 중인 대상의 보유자 트랜잭션 ID 집합을 반환한다.
     */
    private fun getWaitingFor(txnId: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for ((_, entry) in locks) {
            val isWaiting = entry.waitQueue.any { it.txnId == txnId }
            if (isWaiting) {
                result.addAll(entry.holders.map { it.txnId })
            }
        }
        result.remove(txnId)
        return result
    }
}
