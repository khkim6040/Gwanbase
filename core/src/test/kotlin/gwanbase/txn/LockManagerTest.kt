package gwanbase.txn

import gwanbase.table.RID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

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
}
