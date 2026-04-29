package gwanbase.txn

import gwanbase.table.RID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
}
