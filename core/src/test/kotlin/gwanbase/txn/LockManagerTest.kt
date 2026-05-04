package gwanbase.txn

import gwanbase.table.RID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LockManagerTest {

    private lateinit var lm: LockManager

    @BeforeEach
    fun setUp() {
        lm = LockManager()
    }

    @Test
    fun `S 잠금을 획득하고 해제할 수 있다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
        lm.acquire(txnId = 2, target = target, mode = LockMode.EXCLUSIVE)
        lm.releaseAll(txnId = 2)
    }

    @Test
    fun `X 잠금을 획득하고 해제할 수 있다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
        lm.releaseAll(txnId = 1)
        lm.acquire(txnId = 2, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 2)
    }

    @Test
    fun `같은 대상에 S+S 잠금은 호환된다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.acquire(txnId = 2, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
        lm.releaseAll(txnId = 2)
    }

    @Test
    fun `이미 보유한 S 잠금을 재요청하면 즉시 반환한다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
    }

    @Test
    fun `이미 X 잠금을 보유한 상태에서 S 요청은 즉시 반환한다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
    }

    @Test
    fun `X 잠금이 보유된 상태에서 S 요청은 해제 후 획득된다`() {
        val target = LockTarget("t", RID(1, 0))
        val acquired = AtomicBoolean(false)
        val ready = CountDownLatch(1)

        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)

        val t = Thread {
            ready.countDown()
            lm.acquire(txnId = 2, target = target, mode = LockMode.SHARED)
            acquired.set(true)
        }
        t.start()

        ready.await()
        Thread.sleep(50)
        acquired.get() shouldBe false

        lm.releaseAll(txnId = 1)
        t.join(2000)
        acquired.get() shouldBe true
    }

    @Test
    fun `S+X 충돌 시 S 해제 후 X가 획득된다`() {
        val target = LockTarget("t", RID(1, 0))
        val acquired = AtomicBoolean(false)
        val ready = CountDownLatch(1)

        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)

        val t = Thread {
            ready.countDown()
            lm.acquire(txnId = 2, target = target, mode = LockMode.EXCLUSIVE)
            acquired.set(true)
        }
        t.start()

        ready.await()
        Thread.sleep(50)
        acquired.get() shouldBe false

        lm.releaseAll(txnId = 1)
        t.join(2000)
        acquired.get() shouldBe true
    }

    @Test
    fun `S→X 업그레이드 - 다른 S 보유자가 없으면 즉시 업그레이드`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
        val blocked = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val t = Thread {
            ready.countDown()
            lm.acquire(txnId = 2, target = target, mode = LockMode.SHARED)
            blocked.set(false)
        }
        t.start()
        ready.await()
        Thread.sleep(50)
        blocked.get() shouldBe true
        lm.releaseAll(txnId = 1)
        t.join(2000)
        blocked.get() shouldBe false
    }

    @Test
    fun `S→X 업그레이드 - 다른 S 보유자가 있으면 해제 후 업그레이드`() {
        val target = LockTarget("t", RID(1, 0))
        val upgraded = AtomicBoolean(false)
        val ready = CountDownLatch(1)

        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.acquire(txnId = 2, target = target, mode = LockMode.SHARED)

        val t = Thread {
            ready.countDown()
            lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
            upgraded.set(true)
        }
        t.start()

        ready.await()
        Thread.sleep(50)
        upgraded.get() shouldBe false

        lm.releaseAll(txnId = 2)
        t.join(2000)
        upgraded.get() shouldBe true
    }

    @Test
    fun `2 트랜잭션 교차 잠금 시 데드락을 감지한다`() {
        val targetA = LockTarget("t", RID(1, 0))
        val targetB = LockTarget("t", RID(1, 1))
        val deadlockDetected = AtomicBoolean(false)
        val t1Ready = CountDownLatch(1)
        val t2Ready = CountDownLatch(1)

        lm.acquire(txnId = 1, target = targetA, mode = LockMode.EXCLUSIVE)
        lm.acquire(txnId = 2, target = targetB, mode = LockMode.EXCLUSIVE)

        val t1 = Thread {
            t1Ready.countDown()
            try {
                lm.acquire(txnId = 1, target = targetB, mode = LockMode.EXCLUSIVE)
            } catch (e: DeadlockException) {
                deadlockDetected.set(true)
            }
        }
        t1.start()
        t1Ready.await()
        Thread.sleep(50)

        try {
            lm.acquire(txnId = 2, target = targetA, mode = LockMode.EXCLUSIVE)
        } catch (e: DeadlockException) {
            deadlockDetected.set(true)
            lm.releaseAll(e.victimTxnId)
        }

        t1.join(2000)
        deadlockDetected.get() shouldBe true
        lm.releaseAll(txnId = 1)
        lm.releaseAll(txnId = 2)
    }

    @Test
    fun `3 트랜잭션 순환 대기 시 데드락을 감지한다`() {
        val targetA = LockTarget("t", RID(1, 0))
        val targetB = LockTarget("t", RID(1, 1))
        val targetC = LockTarget("t", RID(1, 2))
        val deadlockDetected = AtomicBoolean(false)
        val t1Ready = CountDownLatch(1)
        val t2Ready = CountDownLatch(1)

        lm.acquire(txnId = 1, target = targetA, mode = LockMode.EXCLUSIVE)
        lm.acquire(txnId = 2, target = targetB, mode = LockMode.EXCLUSIVE)
        lm.acquire(txnId = 3, target = targetC, mode = LockMode.EXCLUSIVE)

        val t1 = Thread {
            t1Ready.countDown()
            try {
                lm.acquire(txnId = 1, target = targetB, mode = LockMode.EXCLUSIVE)
            } catch (e: DeadlockException) {
                deadlockDetected.set(true)
            }
        }
        t1.start()
        t1Ready.await()
        Thread.sleep(50)

        val t2 = Thread {
            t2Ready.countDown()
            try {
                lm.acquire(txnId = 2, target = targetC, mode = LockMode.EXCLUSIVE)
            } catch (e: DeadlockException) {
                deadlockDetected.set(true)
            }
        }
        t2.start()
        t2Ready.await()
        Thread.sleep(50)

        try {
            lm.acquire(txnId = 3, target = targetA, mode = LockMode.EXCLUSIVE)
        } catch (e: DeadlockException) {
            deadlockDetected.set(true)
            lm.releaseAll(e.victimTxnId)
        }

        t1.join(2000)
        t2.join(2000)
        deadlockDetected.get() shouldBe true
        lm.releaseAll(txnId = 1)
        lm.releaseAll(txnId = 2)
        lm.releaseAll(txnId = 3)
    }

    @Test
    fun `대기열의 EXCLUSIVE 요청 두 개가 동시에 부여되지 않는다`() {
        val target = LockTarget("t", RID(1, 0))
        val concurrentHolders = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val barrier = CyclicBarrier(3)

        // txn 1이 X 잠금 보유 → txn 2, 3이 X 대기 → txn 1 해제 시 하나만 부여
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)

        val threads = (2..3).map { txnId ->
            Thread {
                barrier.await()
                lm.acquire(txnId = txnId, target = target, mode = LockMode.EXCLUSIVE)
                val current = concurrentHolders.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                // X 잠금 보유 중 잠시 유지하여 경합 유도
                Thread.sleep(50)
                concurrentHolders.decrementAndGet()
                lm.releaseAll(txnId)
            }
        }
        threads.forEach { it.start() }
        barrier.await()
        Thread.sleep(50) // 두 스레드 모두 대기열에 진입할 시간
        lm.releaseAll(txnId = 1) // 대기열에서 grant 발생
        threads.forEach { it.join(5000) }

        // EXCLUSIVE 잠금은 동시에 최대 1개만 보유해야 한다
        maxConcurrent.get() shouldBeLessThanOrEqual 1
    }

    @Test
    fun `getWaitingFor 동기화 - 다수 스레드가 동시에 잠금 획득 해제해도 예외 없이 데드락 감지한다`() {
        val numTargets = 5
        val targets = (0 until numTargets).map { LockTarget("t", RID(1, it)) }
        val errors = AtomicInteger(0)
        val deadlocks = AtomicInteger(0)
        val barrier = CyclicBarrier(numTargets)

        // 각 스레드가 자신의 target에 X 잠금을 먼저 획득한 뒤,
        // 다음 target에 대한 잠금을 동시에 요청하여 경합을 유발한다.
        // 데드락 감지 과정에서 getWaitingFor()가 동기화 없이 순회하면
        // ConcurrentModificationException이 발생할 수 있다.
        val threads = (0 until numTargets).map { i ->
            Thread {
                try {
                    lm.acquire(txnId = i + 1, target = targets[i], mode = LockMode.EXCLUSIVE)
                    barrier.await()
                    try {
                        lm.acquire(
                            txnId = i + 1,
                            target = targets[(i + 1) % numTargets],
                            mode = LockMode.EXCLUSIVE
                        )
                    } catch (_: DeadlockException) {
                        deadlocks.incrementAndGet()
                    }
                } catch (e: Exception) {
                    if (e !is DeadlockException) {
                        errors.incrementAndGet()
                    }
                } finally {
                    lm.releaseAll(i + 1)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10000) }

        // ConcurrentModificationException 등 예외가 발생하지 않아야 한다
        errors.get() shouldBe 0
        // 순환 대기이므로 최소 1개의 데드락이 감지되어야 한다
        (deadlocks.get() >= 1) shouldBe true
    }
}
