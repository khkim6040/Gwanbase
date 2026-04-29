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
}
