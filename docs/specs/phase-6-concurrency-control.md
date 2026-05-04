# Phase 6: Concurrency Control

## 목표

다중 트랜잭션이 동시에 실행될 수 있도록 동시성 제어를 구현한다.
Strict Two-Phase Locking (S2PL)으로 Serializable 격리 수준을 보장하고,
Waits-For Graph 기반 데드락 감지로 교착 상태를 해결한다.

## 비기능 요구사항

- Phase 1~5의 모든 기존 테스트가 수정 없이 통과해야 한다.
- 기존 `Database.executeSql()` API 호환성을 유지한다.
- 동시성 제어로 인한 단일 트랜잭션 성능 저하를 최소화한다.

## 인터페이스 설계 (public API)

### DatabaseSession

세션별 트랜잭션 상태를 관리하는 진입점. `gwanbase.txn` 패키지에 위치한다.

```kotlin
class DatabaseSession(
    private val database: Database,
    private val lockManager: LockManager,
) : AutoCloseable {

    /** 명시적 트랜잭션을 시작한다. 이미 활성 트랜잭션이 있으면 예외. */
    fun begin()

    /** 활성 트랜잭션을 커밋하고 모든 잠금을 해제한다. */
    fun commit()

    /** 활성 트랜잭션을 롤백하고 모든 잠금을 해제한다. */
    fun rollback()

    /**
     * SQL 문을 실행한다.
     *
     * BEGIN/COMMIT/ROLLBACK은 트랜잭션 제어로 처리한다.
     * 활성 트랜잭션이 없으면 auto-commit 모드로 실행한다.
     */
    fun executeSql(sql: String): ExecuteResult

    /** 세션을 닫는다. 활성 트랜잭션이 있으면 자동 rollback. */
    override fun close()
}
```

### LockManager

RID 기반 행 수준 잠금을 관리한다. `gwanbase.txn` 패키지에 위치한다.

```kotlin
data class LockTarget(val tableName: String, val rid: RID)

enum class LockMode { SHARED, EXCLUSIVE }

class DeadlockException(val victimTxnId: Int) : RuntimeException(
    "데드락 감지: 트랜잭션 $victimTxnId 이 victim으로 선택됨"
)

class LockManager {

    /**
     * 잠금을 획득한다.
     *
     * 호환되지 않는 잠금이 있으면 대기한다.
     * 대기 전 Waits-For Graph에서 사이클을 검사하고,
     * 데드락 발견 시 DeadlockException을 발생시킨다.
     *
     * @throws DeadlockException 데드락이 감지된 경우
     */
    fun acquire(txnId: Int, target: LockTarget, mode: LockMode)

    /**
     * 트랜잭션의 모든 잠금을 해제하고 대기 중인 트랜잭션을 깨운다.
     */
    fun releaseAll(txnId: Int)
}
```

### Database 변경

```kotlin
class Database {
    // 신규
    fun createSession(): DatabaseSession

    // 기존 유지 (내부적으로 세션 생성하여 실행)
    fun executeSql(sql: String): ExecuteResult

    // 내부: AtomicInteger로 변경
    internal fun allocateTxnId(): Int
}
```

### SQL 파싱 확장

```kotlin
// Statement에 추가
sealed class Statement {
    object Begin : Statement()
    object Commit : Statement()
    object Rollback : Statement()
}

// ExecuteResult에 추가
sealed class ExecuteResult {
    object TransactionStarted : ExecuteResult()
    object TransactionCommitted : ExecuteResult()
    object TransactionRolledBack : ExecuteResult()
}

// TokenType에 추가
enum class TokenType {
    BEGIN, COMMIT, ROLLBACK,
}
```

## 내부 설계

### DatabaseSession 내부

- `currentTxn: TransactionContext?` — 현재 활성 트랜잭션. 세션당 하나만 존재.
- `sqlExecutor: SqlExecutor` — 세션 전용 인스턴스.

**auto-commit 흐름:**
```
executeSql("INSERT ...") → currentTxn == null이므로
  → begin() → execute(statement) → commit()
  → 실패 시 rollback()
```

**명시적 트랜잭션 흐름:**
```
executeSql("BEGIN")    → begin(), TransactionStarted 반환
executeSql("INSERT")   → currentTxn != null이므로 바로 실행
executeSql("UPDATE")   → 같은 트랜잭션 내 실행
executeSql("COMMIT")   → commit(), TransactionCommitted 반환
```

**잠금 해제 시점:** `commit()` 또는 `rollback()` 에서 `lockManager.releaseAll(txnId)` 호출.
Strict 2PL이므로 트랜잭션 종료 전에는 어떤 잠금도 해제하지 않는다.

**close() 시 안전장치:** 활성 트랜잭션이 있으면 `rollback()`을 호출하여 잠금 누수를 방지한다.

### LockManager 내부

#### 잠금 테이블 구조

```kotlin
// 대상별 잠금 상태
class LockEntry {
    val holders: MutableSet<TxnHolder> = mutableSetOf()
    val waitQueue: MutableList<LockRequest> = mutableListOf()
}

data class TxnHolder(val txnId: Int, val mode: LockMode)
data class LockRequest(val txnId: Int, val mode: LockMode, val latch: CountDownLatch)
```

- `locks: ConcurrentHashMap<LockTarget, LockEntry>` — 전체 잠금 테이블
- 각 `LockEntry`는 자체 `synchronized`로 보호 (대상별 세밀한 동기화)

#### 잠금 호환성 매트릭스

| 기존 보유 \ 요청 | SHARED | EXCLUSIVE |
|---|---|---|
| **없음** | 즉시 획득 | 즉시 획득 |
| **SHARED** | 즉시 획득 | 대기 |
| **EXCLUSIVE** | 대기 | 대기 |

#### S → X 업그레이드

같은 트랜잭션이 이미 S 잠금을 보유한 대상에 X를 요청하면:
- 다른 S 보유자가 없으면: S를 X로 즉시 업그레이드
- 다른 S 보유자가 있으면: 대기 (데드락 검사 후)

#### acquire() 흐름

```
1. LockEntry를 가져오거나 생성
2. synchronized(entry) 진입
3. 이미 같은 모드 이상을 보유 중이면 → 즉시 반환
4. 호환 가능하면 → holders에 추가, 즉시 반환
5. 호환 불가 → 대기 전 데드락 검사
   5a. 사이클 발견 → DeadlockException 발생
   5b. 사이클 없음 → waitQueue에 추가, latch.await()
6. latch 해제되면 → holders에 추가
```

#### releaseAll() 흐름

```
1. 해당 txnId가 보유한 모든 LockEntry를 순회
2. holders에서 제거
3. waitQueue에서 호환 가능한 요청들의 latch를 countDown()
```

### 데드락 감지: Waits-For Graph

**그래프 구축:**
별도 자료구조를 유지하지 않고, `acquire()` 시점에 실시간으로 탐색한다.

```
waitsFor(txnA) = acquire 시 txnA가 대기해야 할 holders의 txnId 집합
```

**사이클 감지 알고리즘:**
DFS로 `요청자 → 보유자들 → 보유자들이 대기 중인 대상의 보유자들 → ...` 을 탐색.
방문한 노드에 요청자가 다시 나타나면 사이클.

```kotlin
fun detectDeadlock(requestorTxnId: Int, blockers: Set<Int>): Boolean {
    val visited = mutableSetOf(requestorTxnId)
    val stack = ArrayDeque(blockers)
    while (stack.isNotEmpty()) {
        val txnId = stack.removeFirst()
        if (txnId == requestorTxnId) return true  // 사이클!
        if (visited.add(txnId)) {
            // txnId가 대기 중인 LockEntry의 holders를 stack에 추가
            stack.addAll(getWaitingFor(txnId))
        }
    }
    return false
}
```

**Victim 선택:** 데드락을 감지한 요청자(가장 최근 잠금 요청을 시도한 트랜잭션)를
victim으로 선택한다. 대기 큐에 진입하기 전에 감지하므로 정리가 깔끔하다.

### Database 리팩토링

**변경 사항:**
- `currentTxn` 필드 제거 → `DatabaseSession`으로 이동
- `beginTransaction/commitTransaction/abortTransaction` 제거 → `DatabaseSession`으로 이동
- `nextTxnId: Int` → `nextTxnId: AtomicInteger` (다중 세션 안전)
- `sqlExecutor` 필드 제거 → 각 `DatabaseSession`이 보유
- `lockManager: LockManager` 필드 추가 (Database 수명과 동일)

**WalCallback 변경:**
- 기존: `WalCallbackImpl(logManager) { db.currentTxn }`
- 변경: `txnProvider`를 `ThreadLocal` 또는 세션에서 주입하는 방식으로 변경
- 세션이 자신의 `currentTxn`을 BPM에 전달할 수 있어야 함

**WalCallback txnProvider 해결 방안:**
`Database`에 `ThreadLocal<TransactionContext?>`를 도입하고,
`DatabaseSession`이 트랜잭션 시작/종료 시 `ThreadLocal`에 설정/해제한다.
`WalCallbackImpl`의 `txnProvider`는 이 `ThreadLocal`을 읽는다.

```kotlin
// Database 내부
internal val currentTxnHolder = ThreadLocal<TransactionContext?>()

// DatabaseSession 내부
fun begin() {
    // ...
    database.currentTxnHolder.set(txn)
}
fun commit() {
    // ...
    database.currentTxnHolder.remove()
}
```

### SQL 파싱 변경

**Lexer:** 키워드 맵에 `"BEGIN" → BEGIN`, `"COMMIT" → COMMIT`, `"ROLLBACK" → ROLLBACK` 추가.

**Parser:** `parseStatement()`에서 첫 토큰이 BEGIN/COMMIT/ROLLBACK이면
해당 `Statement` 객체 반환.

**Binder:** `Begin`/`Commit`/`Rollback`은 스키마 검증이 불필요하므로 통과.

## 제약 사항 및 트레이드오프

### 행 수준 잠금 vs 테이블 수준 잠금

행 수준 잠금을 선택했다. 테이블 수준은 구현이 단순하지만 동시성이 극단적으로 낮아
학습 목적에서 Lock Manager의 본질을 보기 어렵다. 계층적 잠금(Intention Locking)은
IS/IX/SIX 모드까지 다루면 복잡도가 크게 올라가므로 Phase 6 범위 밖으로 남겨둔다.

### Strict 2PL vs Basic 2PL

Strict 2PL(커밋/롤백 시에만 잠금 해제)을 선택했다. Basic 2PL(shrinking phase에서
점진적 해제)은 cascading abort 문제가 발생할 수 있어 recovery와의 통합이 복잡하다.
PostgreSQL도 Strict 2PL을 사용한다.

### Waits-For Graph vs Wait-Die

Waits-For Graph를 선택했다. Wait-Die는 불필요한 abort가 발생할 수 있고,
교육적 가치 면에서 그래프 기반 감지가 더 풍부하다. PostgreSQL도 이 방식을 사용한다.

### WalCallback의 ThreadLocal 사용

다중 세션에서 각 스레드가 자신의 트랜잭션 컨텍스트를 `BufferPoolManager`에 전달하기
위해 `ThreadLocal`을 사용한다. 코루틴 환경에서는 `ThreadLocal`이 맞지 않지만,
Phase 8 (Networking)에서 코루틴을 도입할 때 `CoroutineContext`로 전환할 수 있다.
현재는 스레드 = 세션 모델이므로 `ThreadLocal`이 적합하다.

### SELECT 시 S 잠금의 비용

모든 읽기 행에 S 잠금을 거는 것은 Serializable 보장을 위해 필수지만,
읽기 전용 워크로드의 동시성을 떨어뜨린다. MVCC를 도입하면 읽기가 잠금 없이
가능하지만, Phase 6에서는 정확성 우선 원칙에 따라 S 잠금을 사용한다.

### Phantom Read 미처리

행 수준 잠금만으로는 Phantom Read를 완전히 방지할 수 없다 (다른 트랜잭션이
새 행을 삽입하면 재스캔 시 보일 수 있음). 완벽한 Serializable을 위해서는
Predicate Locking이나 Index-Range Locking이 필요하지만, Phase 6 범위에서는
행 수준 잠금으로 Dirty Read와 Lost Update 방지에 집중한다.
Phantom 방지는 향후 고도화 대상으로 남긴다.

## 테스트 시나리오

### LockManager 단위 테스트 (`LockManagerTest`)

- S 잠금 획득 및 해제
- X 잠금 획득 및 해제
- S+S 동시 보유 (호환)
- S+X 충돌 시 대기 후 획득
- X+X 충돌 시 대기 후 획득
- S → X 업그레이드 (다른 보유자 없을 때 즉시)
- S → X 업그레이드 (다른 S 보유자 있을 때 대기)
- releaseAll 후 대기자 깨우기
- 데드락 감지 (2 트랜잭션 교차 잠금)
- 데드락 감지 (3 트랜잭션 순환 대기)
- 이미 보유한 잠금 재요청 시 즉시 반환

### DatabaseSession 단위 테스트 (`DatabaseSessionTest`)

- auto-commit 모드로 INSERT 실행
- auto-commit 모드로 SELECT 실행
- 명시적 BEGIN → INSERT → COMMIT
- 명시적 BEGIN → INSERT → ROLLBACK → 데이터 미존재 확인
- BEGIN 중복 호출 시 예외
- 활성 트랜잭션 없이 COMMIT 시 예외
- 활성 트랜잭션 없이 ROLLBACK 시 예외
- close() 시 활성 트랜잭션 자동 rollback

### 동시성 통합 테스트 (`ConcurrencyTest`)

- 두 세션이 같은 행을 동시에 SELECT (S+S 호환)
- 한 세션이 UPDATE 중 다른 세션의 SELECT가 대기 후 획득
- 두 세션이 다른 행을 동시에 UPDATE (충돌 없음)
- 두 세션이 같은 행을 동시에 UPDATE (X+X 대기 후 순차 실행)

### 데드락 통합 테스트 (`DeadlockTest`)

- 두 세션 교차 잠금 → 한쪽 DeadlockException → rollback → 다른 쪽 정상 진행
- 데드락 victim rollback 후 재시도 성공

### 격리 수준 테스트 (`IsolationTest`)

- Dirty Read 방지: 미커밋 변경이 다른 세션에 보이지 않음
- Lost Update 방지: 두 세션의 동시 UPDATE가 하나만 반영되지 않음
- Repeatable Read: 같은 트랜잭션 내 동일 행 재조회 시 같은 값

### 기존 테스트 호환성

- Phase 1~5의 모든 기존 테스트가 수정 없이 통과

## 벤치마크 목표

- 단일 트랜잭션 throughput: 잠금 오버헤드로 인한 성능 저하 < 20%
- 동시 읽기 (S+S): 선형 확장 (2 세션 ≈ 2x throughput)
- 동시 쓰기 (같은 행): 직렬화되므로 throughput 변화 미미

## 구현 순서

1. **DatabaseSession** — 세션 객체 도입, 기존 Database에서 트랜잭션 로직 이동
2. **LockManager** — S/X 잠금, 호환성 검사, 대기/해제
3. **Database 리팩토링** — 세션 팩토리, AtomicInteger, ThreadLocal
4. **BEGIN/COMMIT/ROLLBACK 파싱** — Lexer/Parser/Binder 확장
5. **데드락 감지** — Waits-For Graph DFS
6. **통합 테스트** — 동시성, 데드락, 격리 수준

## 참고 자료

- *Database Internals* (Alex Petrov) — Chapter 5: Transaction Processing and Recovery
- CMU 15-445 Lecture 16: Two-Phase Locking
- CMU 15-445 Lecture 17: Deadlock Detection
- PostgreSQL 소스 코드 — `lock.c`, `deadlock.c`
- *Architecture of a Database System* (Hellerstein) — Section 6: Transaction Management
