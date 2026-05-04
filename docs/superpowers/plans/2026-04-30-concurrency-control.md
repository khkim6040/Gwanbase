# Phase 6: Concurrency Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 다중 트랜잭션이 Strict 2PL로 Serializable 격리 수준을 보장하며 동시 실행되도록 한다.

**Architecture:** DatabaseSession이 세션별 트랜잭션을 관리하고, LockManager가 RID 기반 S/X 잠금을 제공한다. Database는 세션 팩토리로 축소되며, 기존 executeSql()은 내부적으로 세션을 생성하여 호환성을 유지한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions, java.util.concurrent (CountDownLatch, ConcurrentHashMap, AtomicInteger)

---

## File Structure

### 신규 파일
- `core/src/main/kotlin/gwanbase/txn/LockManager.kt` — S/X 행 잠금, Waits-For Graph 데드락 감지
- `core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt` — 세션별 트랜잭션 관리, SQL 실행 진입점
- `core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt` — LockManager 단위 테스트
- `core/src/test/kotlin/gwanbase/txn/DatabaseSessionTest.kt` — DatabaseSession 단위 테스트
- `core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt` — 동시성/데드락/격리 통합 테스트

### 수정 파일
- `core/src/main/kotlin/gwanbase/sql/Token.kt` — BEGIN, COMMIT, ROLLBACK 토큰 추가
- `core/src/main/kotlin/gwanbase/sql/Lexer.kt` — 키워드 맵에 3개 추가
- `core/src/main/kotlin/gwanbase/sql/Ast.kt` — Statement.Begin/Commit/Rollback 추가
- `core/src/main/kotlin/gwanbase/sql/Parser.kt` — parseStatement()에 분기 추가
- `core/src/main/kotlin/gwanbase/sql/Binder.kt` — Begin/Commit/Rollback 통과 처리
- `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` — ExecuteResult 3종 추가, Database 대신 DatabaseSession 참조 옵션
- `core/src/main/kotlin/gwanbase/table/Database.kt` — createSession(), AtomicInteger, ThreadLocal, 트랜잭션 로직 이동

---

### Task 1: LockManager 기본 잠금 (S/X 호환성)

**Files:**
- Create: `core/src/main/kotlin/gwanbase/txn/LockManager.kt`
- Create: `core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt`

- [ ] **Step 1: 잠금 호환성 테스트 작성**

```kotlin
// core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt
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
        // 해제 후 다른 트랜잭션이 X 잠금을 즉시 획득 가능
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
        // 같은 트랜잭션, 같은 대상, 같은 모드 → 즉시 반환 (블로킹 없음)
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
    }

    @Test
    fun `이미 X 잠금을 보유한 상태에서 S 요청은 즉시 반환한다`() {
        val target = LockTarget("t", RID(1, 0))
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
        // X는 S를 포함하므로 즉시 반환
        lm.acquire(txnId = 1, target = target, mode = LockMode.SHARED)
        lm.releaseAll(txnId = 1)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -5`
Expected: 컴파일 실패 (LockManager 클래스 미존재)

- [ ] **Step 3: LockManager 기본 구현**

```kotlin
// core/src/main/kotlin/gwanbase/txn/LockManager.kt
package gwanbase.txn

import gwanbase.table.RID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * 잠금 대상을 식별하는 키.
 *
 * @param tableName 테이블 이름
 * @param rid 행 식별자
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
 *
 * @param victimTxnId victim으로 선택된 트랜잭션 ID
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
                // S → X 업그레이드
                if (mode == LockMode.EXCLUSIVE) {
                    val otherHolders = entry.holders.filter { it.txnId != txnId }
                    if (otherHolders.isEmpty()) {
                        // 다른 보유자 없음 → 즉시 업그레이드
                        entry.holders.remove(existing)
                        entry.holders.add(TxnHolder(txnId, LockMode.EXCLUSIVE))
                        return
                    }
                    // 다른 보유자 있음 → 대기
                    val request = LockRequest(txnId, mode, CountDownLatch(1))
                    entry.waitQueue.add(request)
                    waitForLock(entry, request, txnId)
                    // 깨어난 후 업그레이드
                    entry.holders.remove(existing)
                    entry.holders.add(TxnHolder(txnId, LockMode.EXCLUSIVE))
                    return
                }
            }

            // 호환 가능한지 확인
            if (isCompatible(entry, txnId, mode)) {
                entry.holders.add(TxnHolder(txnId, mode))
                return
            }

            // 호환 불가 → 대기
            val request = LockRequest(txnId, mode, CountDownLatch(1))
            entry.waitQueue.add(request)
            waitForLock(entry, request, txnId)
            entry.holders.add(TxnHolder(txnId, mode))
        }
    }

    /**
     * 트랜잭션의 모든 잠금을 해제하고 대기 중인 트랜잭션을 깨운다.
     *
     * @param txnId 해제할 트랜잭션 ID
     */
    fun releaseAll(txnId: Int) {
        for ((_, entry) in locks) {
            synchronized(entry) {
                val removed = entry.holders.removeAll { it.txnId == txnId }
                if (removed) {
                    grantWaiters(entry)
                }
                // 대기 큐에서도 제거 (데드락 abort된 경우)
                entry.waitQueue.removeAll { it.txnId == txnId }
            }
        }
    }

    private fun isCompatible(entry: LockEntry, txnId: Int, mode: LockMode): Boolean {
        if (entry.holders.isEmpty()) return true
        // 대기 큐가 비어있지 않으면 starvation 방지를 위해 대기
        if (entry.waitQueue.isNotEmpty()) return false
        return when (mode) {
            LockMode.SHARED -> entry.holders.all { it.mode == LockMode.SHARED || it.txnId == txnId }
            LockMode.EXCLUSIVE -> entry.holders.all { it.txnId == txnId }
        }
    }

    private fun waitForLock(entry: LockEntry, request: LockRequest, txnId: Int) {
        // synchronized 블록을 벗어나서 대기해야 다른 스레드가 release 가능
        // → 대기 전에 synchronized를 풀어야 함 (wait/notify 패턴 대신 latch 사용)
        entry.notifyAll() // 혹시 대기 중인 다른 스레드가 있으면 깨움
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
```

위 코드는 기본 뼈대이다. 단일 스레드에서 즉시 획득 가능한 경우만 처리한다.
대기(blocking) 로직은 Task 3에서 멀티스레드 테스트와 함께 완성한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -10`
Expected: 5 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/txn/LockManager.kt core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt
git commit -m "[Phase 6] LockManager 기본 S/X 잠금 구현"
```

---

### Task 2: LockManager 멀티스레드 대기 및 S→X 업그레이드

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/txn/LockManager.kt`
- Modify: `core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt`

- [ ] **Step 1: 멀티스레드 대기 테스트 작성**

```kotlin
// LockManagerTest.kt에 추가

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

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
    Thread.sleep(50) // T2가 대기 상태에 진입할 시간
    acquired.get() shouldBe false // 아직 획득 못함

    lm.releaseAll(txnId = 1) // T1 해제
    t.join(2000)
    acquired.get() shouldBe true // T2 획득 완료
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
    // 다른 보유자 없음 → 즉시 업그레이드
    lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE)
    // 업그레이드 후 다른 트랜잭션이 S를 요청하면 대기해야 함
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
    blocked.get() shouldBe true // X 보유 중이므로 대기
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
        lm.acquire(txnId = 1, target = target, mode = LockMode.EXCLUSIVE) // 업그레이드 대기
        upgraded.set(true)
    }
    t.start()

    ready.await()
    Thread.sleep(50)
    upgraded.get() shouldBe false // T2가 S 보유 중이므로 대기

    lm.releaseAll(txnId = 2) // T2 해제
    t.join(2000)
    upgraded.get() shouldBe true // T1 업그레이드 완료
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -10`
Expected: 새 테스트들 FAIL (대기 로직 미완성)

- [ ] **Step 3: LockManager 대기 로직 완성**

`acquire()` 메서드의 대기 부분을 수정한다. `synchronized` 블록 안에서 `entry`를 `wait()`/`notifyAll()` 패턴으로 사용하는 대신, `CountDownLatch`를 사용하여 `synchronized` 밖에서 대기한다.

```kotlin
// LockManager.kt의 acquire() 메서드를 다음으로 교체

fun acquire(txnId: Int, target: LockTarget, mode: LockMode) {
    val entry = locks.computeIfAbsent(target) { LockEntry() }

    while (true) {
        val request: LockRequest?

        synchronized(entry) {
            // 이미 같은 모드 이상을 보유 중이면 즉시 반환
            val existing = entry.holders.find { it.txnId == txnId }
            if (existing != null) {
                if (existing.mode == mode || existing.mode == LockMode.EXCLUSIVE) {
                    return
                }
                // S → X 업그레이드
                if (mode == LockMode.EXCLUSIVE) {
                    val otherHolders = entry.holders.filter { it.txnId != txnId }
                    if (otherHolders.isEmpty()) {
                        entry.holders.remove(existing)
                        entry.holders.add(TxnHolder(txnId, LockMode.EXCLUSIVE))
                        return
                    }
                    // 대기 필요
                    val req = LockRequest(txnId, mode, CountDownLatch(1))
                    entry.waitQueue.add(req)
                    request = req
                    // synchronized 밖에서 대기
                }
                else {
                    request = null
                }
            } else if (isCompatible(entry, txnId, mode)) {
                entry.holders.add(TxnHolder(txnId, mode))
                return
            } else {
                val req = LockRequest(txnId, mode, CountDownLatch(1))
                entry.waitQueue.add(req)
                request = req
            }
        }

        // synchronized 밖에서 대기
        if (request != null) {
            request.latch.await()
            synchronized(entry) {
                // 업그레이드인 경우 기존 S를 X로 교체
                val existing = entry.holders.find { it.txnId == txnId }
                if (existing != null && mode == LockMode.EXCLUSIVE) {
                    entry.holders.remove(existing)
                }
                entry.holders.add(TxnHolder(txnId, mode))
            }
            return
        }
    }
}
```

`grantWaiters()`가 `releaseAll()` 내부에서 호출되며, 호환 가능한 대기자의 latch를 `countDown()`한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -10`
Expected: 9 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/txn/LockManager.kt core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt
git commit -m "[Phase 6] LockManager 멀티스레드 대기 및 S→X 업그레이드"
```

---

### Task 3: LockManager 데드락 감지

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/txn/LockManager.kt`
- Modify: `core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt`

- [ ] **Step 1: 데드락 감지 테스트 작성**

```kotlin
// LockManagerTest.kt에 추가

@Test
fun `2 트랜잭션 교차 잠금 시 데드락을 감지한다`() {
    val targetA = LockTarget("t", RID(1, 0))
    val targetB = LockTarget("t", RID(1, 1))
    val deadlockDetected = AtomicBoolean(false)
    val t1Ready = CountDownLatch(1)
    val t2Ready = CountDownLatch(1)

    // T1: X(A) 획득
    lm.acquire(txnId = 1, target = targetA, mode = LockMode.EXCLUSIVE)
    // T2: X(B) 획득
    lm.acquire(txnId = 2, target = targetB, mode = LockMode.EXCLUSIVE)

    // T1: X(B) 요청 → T2 보유 중이므로 대기
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
    Thread.sleep(50) // T1이 대기 상태에 진입할 시간

    // T2: X(A) 요청 → T1 보유 중 → 데드락!
    try {
        lm.acquire(txnId = 2, target = targetA, mode = LockMode.EXCLUSIVE)
    } catch (e: DeadlockException) {
        deadlockDetected.set(true)
        lm.releaseAll(e.victimTxnId)
    }

    t1.join(2000)
    deadlockDetected.get() shouldBe true
    // 데드락 해소 후 정리
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

    // T1: X(A), T2: X(B), T3: X(C)
    lm.acquire(txnId = 1, target = targetA, mode = LockMode.EXCLUSIVE)
    lm.acquire(txnId = 2, target = targetB, mode = LockMode.EXCLUSIVE)
    lm.acquire(txnId = 3, target = targetC, mode = LockMode.EXCLUSIVE)

    // T1: X(B) 요청 → 대기
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

    // T2: X(C) 요청 → 대기
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

    // T3: X(A) 요청 → T1→T2→T3→T1 순환 → 데드락!
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -10`
Expected: 데드락 테스트 FAIL (감지 로직 미구현)

- [ ] **Step 3: Waits-For Graph 데드락 감지 구현**

`LockManager`에 다음 메서드를 추가한다:

```kotlin
// LockManager.kt에 추가

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
        if (txnId == requestorTxnId) return true // 사이클 발견
        if (!visited.add(txnId)) continue

        // txnId가 대기 중인 대상의 보유자들을 찾는다
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
        // synchronized 없이 읽기 — 감지 시점의 스냅샷 근사치
        val isWaiting = entry.waitQueue.any { it.txnId == txnId }
        if (isWaiting) {
            result.addAll(entry.holders.map { it.txnId })
        }
    }
    result.remove(txnId)
    return result
}
```

`acquire()` 메서드에서 대기 큐에 추가하기 직전에 데드락 검사를 호출한다:

```kotlin
// acquire() 내부, waitQueue에 request를 추가한 직후, synchronized 밖 대기 전에:
val blockers = entry.holders
    .filter { it.txnId != txnId }
    .map { it.txnId }
    .toSet()
if (detectDeadlock(txnId, blockers)) {
    entry.waitQueue.remove(request)
    throw DeadlockException(txnId)
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.LockManagerTest" 2>&1 | tail -10`
Expected: 11 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/txn/LockManager.kt core/src/test/kotlin/gwanbase/txn/LockManagerTest.kt
git commit -m "[Phase 6] Waits-For Graph 기반 데드락 감지 구현"
```

---

### Task 4: SQL 파싱 확장 (BEGIN/COMMIT/ROLLBACK)

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Token.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Lexer.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Ast.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Binder.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/LexerTest.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

- [ ] **Step 1: Lexer/Parser 테스트 작성**

```kotlin
// LexerTest.kt에 추가
@Test
fun `BEGIN 키워드를 토큰화한다`() {
    val tokens = Lexer("BEGIN").tokenize()
    tokens[0].type shouldBe TokenType.BEGIN
}

@Test
fun `COMMIT 키워드를 토큰화한다`() {
    val tokens = Lexer("COMMIT").tokenize()
    tokens[0].type shouldBe TokenType.COMMIT
}

@Test
fun `ROLLBACK 키워드를 토큰화한다`() {
    val tokens = Lexer("ROLLBACK").tokenize()
    tokens[0].type shouldBe TokenType.ROLLBACK
}
```

```kotlin
// ParserTest.kt에 추가
@Test
fun `BEGIN 문을 파싱한다`() {
    val stmt = Parser(Lexer("BEGIN").tokenize()).parse()
    stmt shouldBe Statement.Begin
}

@Test
fun `COMMIT 문을 파싱한다`() {
    val stmt = Parser(Lexer("COMMIT").tokenize()).parse()
    stmt shouldBe Statement.Commit
}

@Test
fun `ROLLBACK 문을 파싱한다`() {
    val stmt = Parser(Lexer("ROLLBACK").tokenize()).parse()
    stmt shouldBe Statement.Rollback
}

@Test
fun `BEGIN 문 뒤에 세미콜론을 허용한다`() {
    val stmt = Parser(Lexer("BEGIN;").tokenize()).parse()
    stmt shouldBe Statement.Begin
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" --tests "gwanbase.sql.ParserTest" 2>&1 | tail -10`
Expected: 컴파일 실패 (BEGIN/COMMIT/ROLLBACK 미정의)

- [ ] **Step 3: Token, AST, ExecuteResult에 타입 추가**

```kotlin
// Token.kt — TokenType enum에 추가 (키워드 섹션 끝에)
// IS, 뒤에 추가:
BEGIN, COMMIT, ROLLBACK,
```

```kotlin
// Ast.kt — Statement sealed class에 추가
/** 트랜잭션 시작. */
data object Begin : Statement()

/** 트랜잭션 커밋. */
data object Commit : Statement()

/** 트랜잭션 롤백. */
data object Rollback : Statement()
```

```kotlin
// SqlExecutor.kt — ExecuteResult sealed class에 추가
/** BEGIN 결과. */
data object TransactionStarted : ExecuteResult()

/** COMMIT 결과. */
data object TransactionCommitted : ExecuteResult()

/** ROLLBACK 결과. */
data object TransactionRolledBack : ExecuteResult()
```

- [ ] **Step 4: Lexer, Parser, Binder 수정**

```kotlin
// Lexer.kt — KEYWORDS 맵에 추가 ("TIMESTAMP" 뒤에)
"BEGIN" to TokenType.BEGIN,
"COMMIT" to TokenType.COMMIT,
"ROLLBACK" to TokenType.ROLLBACK,
```

```kotlin
// Parser.kt — parseStatement()에 분기 추가
TokenType.BEGIN -> { advance(); Statement.Begin }
TokenType.COMMIT -> { advance(); Statement.Commit }
TokenType.ROLLBACK -> { advance(); Statement.Rollback }
```

```kotlin
// Binder.kt — bind() 메서드의 when에 추가
is Statement.Begin -> { /* 검증 불필요 */ }
is Statement.Commit -> { /* 검증 불필요 */ }
is Statement.Rollback -> { /* 검증 불필요 */ }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.*" 2>&1 | tail -10`
Expected: 모든 sql 패키지 테스트 PASSED

- [ ] **Step 6: 기존 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -10`
Expected: 전체 테스트 PASSED (기존 테스트 깨지지 않음)

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Token.kt \
        core/src/main/kotlin/gwanbase/sql/Lexer.kt \
        core/src/main/kotlin/gwanbase/sql/Ast.kt \
        core/src/main/kotlin/gwanbase/sql/Parser.kt \
        core/src/main/kotlin/gwanbase/sql/Binder.kt \
        core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
        core/src/test/kotlin/gwanbase/sql/LexerTest.kt \
        core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 6] BEGIN/COMMIT/ROLLBACK SQL 파싱 지원"
```

---

### Task 5: DatabaseSession 기본 구현

**Files:**
- Create: `core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt`
- Create: `core/src/test/kotlin/gwanbase/txn/DatabaseSessionTest.kt`
- Modify: `core/src/main/kotlin/gwanbase/table/Database.kt`

- [ ] **Step 1: DatabaseSession 단위 테스트 작성**

```kotlin
// core/src/test/kotlin/gwanbase/txn/DatabaseSessionTest.kt
package gwanbase.txn

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DatabaseSessionTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `auto-commit 모드로 INSERT를 실행한다`() {
        val session = db.createSession()
        session.use {
            val result = it.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            result.shouldBeInstanceOf<ExecuteResult.Inserted>()
        }
        // 커밋되었으므로 다른 세션에서 조회 가능
        val session2 = db.createSession()
        session2.use {
            val result = it.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 1
        }
    }

    @Test
    fun `명시적 BEGIN - INSERT - COMMIT`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN").shouldBeInstanceOf<ExecuteResult.TransactionStarted>()
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            session.executeSql("COMMIT").shouldBeInstanceOf<ExecuteResult.TransactionCommitted>()
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 1
        }
    }

    @Test
    fun `명시적 BEGIN - INSERT - ROLLBACK 후 데이터 미존재`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            session.executeSql("ROLLBACK").shouldBeInstanceOf<ExecuteResult.TransactionRolledBack>()
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 0
        }
    }

    @Test
    fun `BEGIN 중복 호출 시 예외`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            shouldThrow<IllegalStateException> {
                session.executeSql("BEGIN")
            }
            session.executeSql("ROLLBACK")
        }
    }

    @Test
    fun `활성 트랜잭션 없이 COMMIT 시 예외`() {
        db.createSession().use { session ->
            shouldThrow<IllegalStateException> {
                session.executeSql("COMMIT")
            }
        }
    }

    @Test
    fun `활성 트랜잭션 없이 ROLLBACK 시 예외`() {
        db.createSession().use { session ->
            shouldThrow<IllegalStateException> {
                session.executeSql("ROLLBACK")
            }
        }
    }

    @Test
    fun `close 시 활성 트랜잭션이 자동 rollback된다`() {
        db.createSession().use { session ->
            session.executeSql("BEGIN")
            session.executeSql("INSERT INTO t (id, name) VALUES (1, 'alice')")
            // close() 호출 → 자동 rollback
        }
        db.createSession().use { session ->
            val result = session.executeSql("SELECT * FROM t") as ExecuteResult.Selected
            result.rows.size shouldBe 0
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.DatabaseSessionTest" 2>&1 | tail -5`
Expected: 컴파일 실패 (DatabaseSession 미존재, createSession 미존재)

- [ ] **Step 3: Database에 세션 팩토리 및 ThreadLocal 추가**

```kotlin
// Database.kt 수정사항:
// 1. import 추가
import gwanbase.txn.DatabaseSession
import gwanbase.txn.LockManager
import java.util.concurrent.atomic.AtomicInteger

// 2. 필드 변경
//    - private var nextTxnId: Int = 0  →  삭제
//    + internal val nextTxnId = AtomicInteger(0)
//    + internal val lockManager = LockManager()
//    + internal val currentTxnHolder = ThreadLocal<TransactionContext?>()

// 3. 신규 메서드
/** 새 세션을 생성한다. */
fun createSession(): DatabaseSession {
    checkOpen()
    return DatabaseSession(this, lockManager)
}

/** 트랜잭션 ID를 할당한다. */
internal fun allocateTxnId(): Int = nextTxnId.getAndIncrement()

// 4. executeSql() 변경 — 내부적으로 세션 사용
fun executeSql(sql: String): ExecuteResult {
    return createSession().use { session ->
        session.executeSql(sql)
    }
}

// 5. 기존 beginTransaction/commitTransaction/abortTransaction 삭제
//    (DatabaseSession으로 이동)

// 6. open() 내 nextTxnId 설정 변경
//    db.nextTxnId = nextTxnId  →  db.nextTxnId.set(nextTxnId)

// 7. WalCallback 변경
//    bpm.walCallback = WalCallbackImpl(logManager) { db.currentTxn }
//    →  bpm.walCallback = WalCallbackImpl(logManager) { db.currentTxnHolder.get() }

// 8. close() 내 check(currentTxn == null) 제거
//    (세션이 트랜잭션을 관리하므로 Database는 더 이상 추적하지 않음)
```

- [ ] **Step 4: DatabaseSession 구현**

```kotlin
// core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt
package gwanbase.txn

import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.wal.TransactionContext

/**
 * 데이터베이스 세션.
 *
 * 세션별로 독립적인 트랜잭션 상태를 관리하며, SQL 실행의 진입점이 된다.
 * auto-commit 모드와 명시적 트랜잭션(BEGIN/COMMIT/ROLLBACK)을 모두 지원한다.
 *
 * @param database 공유 데이터베이스
 * @param lockManager 공유 잠금 관리자
 */
class DatabaseSession(
    internal val database: Database,
    private val lockManager: LockManager,
) : AutoCloseable {

    private var currentTxn: TransactionContext? = null
    private val sqlExecutor: SqlExecutor = SqlExecutor(database)

    /**
     * 명시적 트랜잭션을 시작한다.
     *
     * @throws IllegalStateException 이미 활성 트랜잭션이 있는 경우
     */
    fun begin() {
        check(currentTxn == null) { "이미 활성 트랜잭션이 있다" }
        beginInternal()
    }

    /**
     * 활성 트랜잭션을 커밋하고 모든 잠금을 해제한다.
     *
     * @throws IllegalStateException 활성 트랜잭션이 없는 경우
     */
    fun commit() {
        val txn = currentTxn ?: error("활성 트랜잭션이 없다")
        commitInternal(txn)
    }

    /**
     * 활성 트랜잭션을 롤백하고 모든 잠금을 해제한다.
     *
     * @throws IllegalStateException 활성 트랜잭션이 없는 경우
     */
    fun rollback() {
        val txn = currentTxn ?: error("활성 트랜잭션이 없다")
        abortInternal(txn)
    }

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

    private fun abortInternal(txn: TransactionContext) {
        currentTxn = null
        database.currentTxnHolder.remove()

        database.logManager?.let { lm ->
            // Runtime undo: before-image 복원
            var lsn = txn.lastLsn
            while (lsn >= 0) {
                val record = lm.getRecord(lsn)
                when (record) {
                    is gwanbase.wal.LogRecord.Update -> {
                        val page = database.bpm.fetchPage(record.pageId)
                        if (page != null) {
                            page.data.clear()
                            page.data.put(record.beforeImage)
                            page.data.flip()
                            database.bpm.unpinPage(record.pageId, isDirty = true)
                        }
                        lsn = record.prevLsn
                    }
                    is gwanbase.wal.LogRecord.Begin -> break
                    else -> lsn = record.prevLsn
                }
            }
            val abortLsn = lm.appendAbort(txn.txnId, txn.lastLsn)
            lm.flush(abortLsn)
        }
        lockManager.releaseAll(txn.txnId)
    }
}
```

이를 위해 `Database`의 `logManager`와 `bpm` 필드를 `internal`로 변경해야 한다:

```kotlin
// Database.kt 필드 가시성 변경
internal val logManager: LogManager?   // private → internal
internal val bpm: BufferPoolManager    // private → internal
```

또한 `SqlExecutor.executeStatement()`를 `internal`로 공개해야 한다:

```kotlin
// SqlExecutor.kt에서 executeStatement의 가시성 변경
internal fun executeStatement(stmt: Statement): ExecuteResult {
    // 기존 코드 그대로
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.DatabaseSessionTest" 2>&1 | tail -10`
Expected: 7 tests PASSED

- [ ] **Step 6: 기존 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -10`
Expected: 전체 테스트 PASSED (기존 API 호환성 유지)

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt \
        core/src/test/kotlin/gwanbase/txn/DatabaseSessionTest.kt \
        core/src/main/kotlin/gwanbase/table/Database.kt \
        core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt
git commit -m "[Phase 6] DatabaseSession 구현 및 Database 세션 팩토리 전환"
```

---

### Task 6: 동시성 통합 테스트 (잠금 연동)

**Files:**
- Create: `core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt`
- Modify: `core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt` (잠금 acquire 호출 추가)
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` (잠금 콜백 연동)

이 Task에서 실제로 SQL 실행 경로에 잠금을 걸어서 동시성을 제어한다.

- [ ] **Step 1: 동시성 테스트 작성**

```kotlin
// core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt
package gwanbase.txn

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ConcurrencyIntegrationTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        db.executeSql("CREATE TABLE t (id INT NOT NULL, val INT NOT NULL)")
        db.executeSql("INSERT INTO t (id, val) VALUES (1, 100)")
        db.executeSql("INSERT INTO t (id, val) VALUES (2, 200)")
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `두 세션이 같은 행을 동시에 SELECT 할 수 있다`() {
        val s1 = db.createSession()
        val s2 = db.createSession()

        s1.executeSql("BEGIN")
        s2.executeSql("BEGIN")

        val r1 = s1.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
        val r2 = s2.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected

        r1.rows.size shouldBe 1
        r2.rows.size shouldBe 1

        s1.executeSql("COMMIT")
        s2.executeSql("COMMIT")
        s1.close()
        s2.close()
    }

    @Test
    fun `두 세션이 다른 행을 동시에 UPDATE 할 수 있다`() {
        val s1Done = AtomicBoolean(false)
        val s2Done = AtomicBoolean(false)
        val s1Ready = CountDownLatch(1)
        val s2Ready = CountDownLatch(1)

        val t1 = Thread {
            db.createSession().use { s1 ->
                s1.executeSql("BEGIN")
                s1Ready.countDown()
                s2Ready.await()
                s1.executeSql("UPDATE t SET val = 111 WHERE id = 1")
                s1.executeSql("COMMIT")
                s1Done.set(true)
            }
        }

        val t2 = Thread {
            db.createSession().use { s2 ->
                s2.executeSql("BEGIN")
                s2Ready.countDown()
                s1Ready.await()
                s2.executeSql("UPDATE t SET val = 222 WHERE id = 2")
                s2.executeSql("COMMIT")
                s2Done.set(true)
            }
        }

        t1.start()
        t2.start()
        t1.join(5000)
        t2.join(5000)

        s1Done.get() shouldBe true
        s2Done.get() shouldBe true

        // 결과 확인
        val result = db.executeSql("SELECT * FROM t ORDER BY id") as ExecuteResult.Selected
        result.rows[0][1] shouldBe 111
        result.rows[1][1] shouldBe 222
    }

    @Test
    fun `Dirty Read 방지 - 미커밋 변경이 다른 세션에 보이지 않는다`() {
        val s2SawDirty = AtomicBoolean(false)
        val s1Updated = CountDownLatch(1)
        val s2Read = CountDownLatch(1)

        val t1 = Thread {
            db.createSession().use { s1 ->
                s1.executeSql("BEGIN")
                s1.executeSql("UPDATE t SET val = 999 WHERE id = 1")
                s1Updated.countDown()
                s2Read.await() // S2가 읽을 때까지 커밋하지 않음
                s1.executeSql("ROLLBACK")
            }
        }

        val t2 = Thread {
            db.createSession().use { s2 ->
                s1Updated.await() // S1이 업데이트한 후
                s2.executeSql("BEGIN")
                val result = s2.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
                if (result.rows[0][1] == 999) {
                    s2SawDirty.set(true) // Dirty Read 발생!
                }
                s2Read.countDown()
                s2.executeSql("COMMIT")
            }
        }

        t1.start()
        t2.start()
        t1.join(5000)
        t2.join(5000)

        s2SawDirty.get() shouldBe false // Dirty Read가 발생하지 않아야 한다
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.ConcurrencyIntegrationTest" 2>&1 | tail -10`
Expected: FAIL (잠금이 아직 SQL 실행 경로에 연동되지 않음)

- [ ] **Step 3: DatabaseSession에 잠금 연동 구현**

SQL 실행 경로에서 튜플에 접근할 때 잠금을 획득하도록 한다. DatabaseSession이 잠금을 관리하므로, Database의 low-level 메서드를 호출할 때 세션을 통해 잠금을 걸어야 한다.

접근 방식: `DatabaseSession`에 잠금이 연동된 wrapper 메서드를 추가하고, `SqlExecutor`가 `DatabaseSession`을 통해 데이터에 접근하도록 한다.

```kotlin
// DatabaseSession.kt에 잠금 연동 메서드 추가

/** 테이블 스캔 시 각 행에 S 잠금을 획득하는 래퍼. */
internal fun scanTableWithLock(tableName: String): Iterator<Pair<gwanbase.table.RID, gwanbase.table.Tuple>> {
    val txn = currentTxn
    val rawIter = database.scanTable(tableName)
    if (txn == null) return rawIter
    return object : Iterator<Pair<gwanbase.table.RID, gwanbase.table.Tuple>> {
        override fun hasNext() = rawIter.hasNext()
        override fun next(): Pair<gwanbase.table.RID, gwanbase.table.Tuple> {
            val (rid, tuple) = rawIter.next()
            lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.SHARED)
            return rid to tuple
        }
    }
}

/** INSERT 시 삽입된 행에 X 잠금을 획득하는 래퍼. */
internal fun insertTupleWithLock(tableName: String, tuple: gwanbase.table.Tuple): gwanbase.table.RID {
    val rid = database.insertTuple(tableName, tuple)
    currentTxn?.let { txn ->
        lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
    }
    return rid
}

/** DELETE 시 대상 행에 X 잠금을 획득하는 래퍼. */
internal fun deleteTupleWithLock(tableName: String, rid: gwanbase.table.RID): Boolean {
    currentTxn?.let { txn ->
        lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
    }
    return database.deleteTuple(tableName, rid)
}

/** UPDATE 시 대상 행에 X 잠금을 획득하는 래퍼. */
internal fun updateTupleWithLock(tableName: String, rid: gwanbase.table.RID, tuple: gwanbase.table.Tuple): gwanbase.table.RID {
    currentTxn?.let { txn ->
        lockManager.acquire(txn.txnId, LockTarget(tableName, rid), LockMode.EXCLUSIVE)
    }
    return database.updateTuple(tableName, rid, tuple)
}
```

`SqlExecutor`를 `DatabaseSession`도 참조하도록 변경한다:

```kotlin
// SqlExecutor.kt 변경
class SqlExecutor(
    private val database: Database,
    private val session: DatabaseSession? = null,  // Phase 6에서 추가
) {
    // executeInsert()에서:
    //   database.insertTuple(...)  →  session?.insertTupleWithLock(...) ?: database.insertTuple(...)
    // executeUpdate()에서:
    //   database.scanTable(...)  →  session?.scanTableWithLock(...) ?: database.scanTable(...)
    //   database.updateTuple(...)  →  session?.updateTupleWithLock(...) ?: database.updateTuple(...)
    // executeDelete()에서:
    //   database.scanTable(...)  →  session?.scanTableWithLock(...) ?: database.scanTable(...)
    //   database.deleteTuple(...)  →  session?.deleteTupleWithLock(...) ?: database.deleteTuple(...)
    // executeSelect()에서:
    //   SeqScanOperator가 database.scanTable()을 사용하므로, session이 있으면
    //   잠금이 걸리는 scan을 사용하도록 Planner에 session을 전달
}
```

SELECT의 잠금은 `SeqScanOperator`가 `database.scanTable()`을 사용하므로, session이 있을 때 scanTableWithLock을 사용하도록 `SeqScanOperator`에 session을 선택적 파라미터로 추가하거나, `Planner`를 통해 전달한다.

```kotlin
// SeqScanOperator.kt 변경
class SeqScanOperator(
    private val database: Database,
    private val tableName: String,
    private val session: DatabaseSession? = null,  // Phase 6 추가
) : Operator {
    override fun open() {
        iterator = session?.scanTableWithLock(tableName)
            ?: database.scanTable(tableName)
    }
    // 나머지 동일
}

// Planner.kt 변경
class Planner(
    private val database: Database,
    private val session: DatabaseSession? = null,  // Phase 6 추가
) {
    fun planSelect(stmt: Statement.Select): Operator {
        var op: Operator = SeqScanOperator(database, stmt.tableName, session)
        // 나머지 동일
    }
}
```

`DatabaseSession`의 `SqlExecutor`와 `Planner` 생성:

```kotlin
// DatabaseSession.kt 변경
class DatabaseSession(...) {
    private val sqlExecutor: SqlExecutor = SqlExecutor(database, session = this)
    // executeSql()에서 Planner도 세션을 받도록:
    // planner는 SqlExecutor 내부에서 생성되므로, SqlExecutor 생성자에서 처리
}

// SqlExecutor.kt 변경
class SqlExecutor(
    private val database: Database,
    private val session: DatabaseSession? = null,
) {
    private val planner = Planner(database, session)
    // ...
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.ConcurrencyIntegrationTest" 2>&1 | tail -10`
Expected: 3 tests PASSED

- [ ] **Step 5: 기존 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -10`
Expected: 전체 테스트 PASSED

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt \
        core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
        core/src/main/kotlin/gwanbase/execution/SeqScanOperator.kt \
        core/src/main/kotlin/gwanbase/execution/Planner.kt \
        core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt
git commit -m "[Phase 6] SQL 실행 경로에 행 수준 잠금 연동"
```

---

### Task 7: 데드락 및 격리 수준 통합 테스트

**Files:**
- Modify: `core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt`

- [ ] **Step 1: 데드락 통합 테스트 작성**

```kotlin
// ConcurrencyIntegrationTest.kt에 추가

@Test
fun `교차 UPDATE 시 데드락 감지 후 한쪽이 rollback된다`() {
    val deadlockOccurred = AtomicBoolean(false)
    val t1HoldsLock = CountDownLatch(1)
    val t2HoldsLock = CountDownLatch(1)
    val error = AtomicReference<Throwable?>(null)

    val t1 = Thread {
        try {
            db.createSession().use { s1 ->
                s1.executeSql("BEGIN")
                s1.executeSql("UPDATE t SET val = 111 WHERE id = 1") // X(row1)
                t1HoldsLock.countDown()
                t2HoldsLock.await()
                Thread.sleep(50)
                s1.executeSql("UPDATE t SET val = 112 WHERE id = 2") // X(row2) → 대기 or 데드락
                s1.executeSql("COMMIT")
            }
        } catch (e: DeadlockException) {
            deadlockOccurred.set(true)
        } catch (e: Throwable) {
            if (e.cause is DeadlockException || e.message?.contains("데드락") == true) {
                deadlockOccurred.set(true)
            } else {
                error.set(e)
            }
        }
    }

    val t2 = Thread {
        try {
            db.createSession().use { s2 ->
                s2.executeSql("BEGIN")
                s2.executeSql("UPDATE t SET val = 222 WHERE id = 2") // X(row2)
                t2HoldsLock.countDown()
                t1HoldsLock.await()
                Thread.sleep(50)
                s2.executeSql("UPDATE t SET val = 221 WHERE id = 1") // X(row1) → 대기 or 데드락
                s2.executeSql("COMMIT")
            }
        } catch (e: DeadlockException) {
            deadlockOccurred.set(true)
        } catch (e: Throwable) {
            if (e.cause is DeadlockException || e.message?.contains("데드락") == true) {
                deadlockOccurred.set(true)
            } else {
                error.set(e)
            }
        }
    }

    t1.start()
    t2.start()
    t1.join(5000)
    t2.join(5000)

    error.get()?.let { throw it }
    deadlockOccurred.get() shouldBe true
}

@Test
fun `Lost Update 방지 - 두 세션의 동시 UPDATE가 직렬화된다`() {
    // 초기값: val = 100
    val t1Done = CountDownLatch(1)
    val t2Done = CountDownLatch(1)

    val t1 = Thread {
        db.createSession().use { s ->
            s.executeSql("UPDATE t SET val = val + 10 WHERE id = 1")
        }
        t1Done.countDown()
    }

    val t2 = Thread {
        db.createSession().use { s ->
            s.executeSql("UPDATE t SET val = val + 20 WHERE id = 1")
        }
        t2Done.countDown()
    }

    t1.start()
    t2.start()
    t1Done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    t2Done.await(5, java.util.concurrent.TimeUnit.SECONDS)

    val result = db.executeSql("SELECT * FROM t WHERE id = 1") as ExecuteResult.Selected
    // 직렬화 보장: 100+10+20=130 또는 100+20+10=130
    result.rows[0][1] shouldBe 130
}
```

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.txn.ConcurrencyIntegrationTest" 2>&1 | tail -10`
Expected: 5 tests PASSED

- [ ] **Step 3: 기존 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -10`
Expected: 전체 테스트 PASSED

- [ ] **Step 4: 커밋**

```bash
git add core/src/test/kotlin/gwanbase/txn/ConcurrencyIntegrationTest.kt
git commit -m "[Phase 6] 데드락 및 격리 수준 통합 테스트"
```

---

### Task 8: CLAUDE.md 및 스펙 문서 업데이트

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/specs/phase-6-concurrency-control.md` (구현 과정에서 발견한 변경 반영)

- [ ] **Step 1: CLAUDE.md 로드맵 및 컴포넌트 테이블 업데이트**

```markdown
# 로드맵에서 Phase 6 상태 변경
Phase 6  Concurrency Control             ✅ 완료 (tag v0.6-txn)

# Phase 6 컴포넌트 테이블 추가
### Phase 6 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| LockManager | ✅ | `core/src/main/kotlin/gwanbase/txn/LockManager.kt` |
| DatabaseSession | ✅ | `core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt` |

# Phase 6 설계 가이드 추가
### Phase 6 (완료)
- Strict 2PL로 Serializable 격리 수준 보장
- RID 기반 행 수준 S/X 잠금, Waits-For Graph 데드락 감지
- DatabaseSession 객체로 세션별 트랜잭션 관리
- BEGIN/COMMIT/ROLLBACK SQL 지원, auto-commit 호환 유지
```

- [ ] **Step 2: 기존 전체 테스트 최종 확인**

Run: `./gradlew :core:test 2>&1 | tail -10`
Expected: 전체 테스트 PASSED

- [ ] **Step 3: 커밋 및 태그**

```bash
git add CLAUDE.md docs/specs/phase-6-concurrency-control.md
git commit -m "[Phase 6] CLAUDE.md 로드맵·컴포넌트·설계 가이드 업데이트"
git tag v0.6-txn
```
