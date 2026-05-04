# Phase 5: Crash Recovery (WAL)

## 목표

Write-Ahead Logging(WAL)을 도입하여 **crash 이후에도 데이터 무결성을 보장**한다.
현재 Gwanbase는 `Database.close()` 호출 시에만 모든 dirty page를 flush하므로,
프로세스가 비정상 종료되면 커밋된 데이터가 유실되거나 미커밋 데이터가 디스크에 남을 수 있다.

Phase 5 완료 시점에서 보장하는 속성:
- **Durability**: 커밋된 트랜잭션의 변경은 crash 이후에도 반드시 살아남는다.
- **Atomicity**: 미커밋 트랜잭션의 변경은 recovery 시 롤백된다.
- **Idempotent Recovery**: recovery를 여러 번 수행해도 동일한 결과를 낸다.

## 비기능 요구사항

- 단일 트랜잭션만 동시에 활성화 (동시성 제어는 Phase 6)
- Recovery 후 기존 테스트(Phase 1~4) 전체 통과
- Crash test: 임의 시점에 kill → 재시작 → 무결성 검증

## 핵심 설계 결정

### Steal / No-Force 정책

| 정책 | 선택 | 이유 |
|------|------|------|
| Steal | ✅ 허용 | Buffer Pool이 메모리 압박 시 미커밋 dirty page를 evict할 수 있어야 한다. Undo log 필요. |
| No-Force | ✅ 허용 | 커밋 시 모든 dirty page를 즉시 flush하지 않는다. Redo log 필요. |

Steal/No-Force는 ARIES의 핵심 전제이며, I/O를 트랜잭션 경계에서 분리하여
성능을 확보한다. 대안인 No-Steal/Force는 구현이 단순하지만(SimpleDB 방식)
버퍼 풀 크기만큼의 페이지를 pin해야 하므로 비현실적이다.

### 로깅 단위: 페이지 수준 물리적 로깅 (Physiological)

- **before-image + after-image**를 페이지 단위로 기록한다.
- 레코드 수준 논리적 로깅(logical logging)보다 단순하고, recovery가 직관적이다.
- 트레이드오프: 로그 크기가 커진다 (페이지당 최대 4KB × 2). Phase 7에서 delta 로깅으로 최적화 가능.

> **왜 전체 페이지 이미지(full-page write)인가?**
> 4KB 페이지 쓰기가 OS 수준에서 atomic하지 않다(torn write 가능).
> Crash 시 절반만 기록된 페이지가 남으면, diff만 있는 로그로는 복구 불가.
> PostgreSQL도 checkpoint 직후 첫 수정에서 full-page write를 사용한다.

### Checkpoint: Consistent Checkpoint (BusTub 방식)

- Checkpoint 시 새 트랜잭션을 차단하고, 모든 dirty page를 flush한 뒤 checkpoint 로그 레코드를 기록한다.
- **Fuzzy checkpoint는 Phase 6(동시 트랜잭션)에서 도입한다.** 현재는 단일 트랜잭션이므로 consistent checkpoint가 충분하다.
- Consistent checkpoint 덕분에 **Analysis 단계를 생략**하고 Redo → Undo 2단계로 recovery를 수행한다.

### Recovery: Redo → Undo (2단계)

ARIES의 3단계(Analysis → Redo → Undo) 중 Analysis를 생략한다.
Consistent checkpoint가 Dirty Page Table과 Active Transaction Table을 암묵적으로 확정하기 때문이다.

1. **Redo**: Checkpoint LSN 이후의 모든 로그 레코드를 순방향으로 재생한다. `pageLSN ≥ record.lsn`이면 skip.
2. **Undo**: 미커밋 트랜잭션의 로그를 역방향으로 순회하며 CLR을 기록하고 변경을 되돌린다.

### 다른 DB와의 비교

| 결정 | Gwanbase Phase 5 | SQLite WAL | PostgreSQL | BusTub |
|------|-------------------|------------|------------|--------|
| 로깅 단위 | 페이지 수준 (full image) | 페이지 수준 | 페이지 수준 (+ diff) | 페이지 수준 |
| Checkpoint | Consistent | 자동 (1000p) | Fuzzy | Consistent |
| Recovery | Redo + Undo | WAL 스캔 (no undo) | Analysis + Redo + Undo | Redo + Undo |
| CLR | ✅ | 불필요 (no undo) | ✅ | ✅ |
| 동시성 | 단일 txn | 단일 writer | MVCC | 단일 txn (과제) |

## 인터페이스 설계 (public API)

### 패키지 구조

```
gwanbase.wal/
├── LogRecord.kt         ← sealed class: Begin, Commit, Abort, Update, CLR, Checkpoint
├── LogManager.kt        ← 로그 쓰기/읽기, LSN 관리, flush
├── RecoveryManager.kt   ← Redo + Undo 수행
└── TransactionContext.kt ← 트랜잭션 상태 (txnId, lastLsn)
```

### LogRecord (sealed class)

```kotlin
sealed class LogRecord {
    abstract val lsn: Int
    abstract val txnId: Int

    /** 트랜잭션 시작 */
    data class Begin(override val lsn: Int, override val txnId: Int) : LogRecord()

    /** 트랜잭션 커밋 */
    data class Commit(override val lsn: Int, override val txnId: Int) : LogRecord()

    /** 트랜잭션 중단 */
    data class Abort(override val lsn: Int, override val txnId: Int) : LogRecord()

    /**
     * 페이지 수정.
     * [beforeImage]와 [afterImage]는 페이지 전체(4KB).
     */
    data class Update(
        override val lsn: Int,
        override val txnId: Int,
        val pageId: Int,
        val beforeImage: ByteArray,
        val afterImage: ByteArray,
    ) : LogRecord()

    /**
     * Compensation Log Record — Undo 동작 기록.
     * [undoNextLsn]은 이 CLR이 되돌린 레코드의 이전(prevLsn)을 가리킨다.
     * CLR 자체는 절대 undo되지 않는다.
     */
    data class CLR(
        override val lsn: Int,
        override val txnId: Int,
        val pageId: Int,
        val beforeImage: ByteArray,
        val undoNextLsn: Int,
    ) : LogRecord()

    /** Consistent Checkpoint */
    data class Checkpoint(override val lsn: Int) : LogRecord() {
        override val txnId: Int = -1
    }
}
```

### LogManager

```kotlin
class LogManager(
    private val logPath: Path,
) : AutoCloseable {

    /** 다음에 할당할 LSN (단조 증가) */
    fun getNextLsn(): Int

    /** 로그 레코드를 추가하고 할당된 LSN을 반환한다 */
    fun append(record: LogRecord): Int

    /** 지정된 LSN까지 로그를 디스크에 flush한다 (WAL 불변식) */
    fun flush(upToLsn: Int)

    /** 전체 로그를 순방향으로 읽는 Iterator를 반환한다 */
    fun forwardIterator(fromLsn: Int = 0): Iterator<LogRecord>

    /** 전체 로그를 역방향으로 읽는 Iterator를 반환한다 */
    fun backwardIterator(): Iterator<LogRecord>

    /** 마지막 Checkpoint 레코드의 LSN을 반환한다. 없으면 0. */
    fun lastCheckpointLsn(): Int

    override fun close()
}
```

### RecoveryManager

```kotlin
class RecoveryManager(
    private val logManager: LogManager,
    private val bufferPoolManager: BufferPoolManager,
) {
    /**
     * Crash recovery를 수행한다.
     *
     * 1. Redo: lastCheckpointLsn 이후 모든 레코드 순방향 재생
     * 2. Undo: 미커밋 트랜잭션 역방향 롤백 (CLR 기록)
     *
     * @return recovery 완료 후 다음 사용할 txnId
     */
    fun recover(): Int
}
```

### TransactionContext

```kotlin
/**
 * 현재 활성 트랜잭션의 상태를 추적한다.
 * Phase 5에서는 단일 트랜잭션만 허용한다.
 */
class TransactionContext(
    val txnId: Int,
) {
    /** 이 트랜잭션의 마지막 로그 레코드 LSN */
    var lastLsn: Int = -1
}
```

### 기존 컴포넌트 수정

#### Page — `pageLsn` 필드 추가

```kotlin
class Page(...) {
    /** 이 페이지를 마지막으로 수정한 로그 레코드의 LSN */
    @Volatile
    var pageLsn: Int = 0
}
```

`reset()` 시 `pageLsn = 0`으로 초기화.

#### BufferPoolManager — WAL 불변식 적용

**핵심 변경**: dirty page를 evict하기 전에 `logManager.flush(page.pageLsn)`을 호출한다.
이것이 WAL 불변식(Write-Ahead Logging Protocol)의 구현이다.

```kotlin
// findFreeFrame() 내 eviction 로직 변경
if (victimPage.isDirty) {
    logManager.flush(victimPage.pageLsn)  // ← WAL 불변식
    diskManager.writePage(victimPage.pageId, victimPage.data)
}
```

`flushAllPages()` 호출 시에도 동일하게 해당 page의 `pageLsn`까지 flush.

#### Database — LogManager/RecoveryManager 통합

- `open()`: LogManager 초기화 → RecoveryManager.recover() 호출 → 정상 운영 시작
- `close()`: Checkpoint 기록 → flushAllPages() → LogManager.close()
- `executeSQL()`: 각 DML 문을 Begin/Commit으로 감싸는 auto-commit 모드

#### SqlExecutor — 로그 기록 통합

DML(INSERT/UPDATE/DELETE) 실행 시:
1. `Begin` 로그
2. 페이지 수정 전 before-image 캡처
3. 페이지 수정 후 after-image 캡처 → `Update` 로그
4. `Commit` 로그 + `logManager.flush(commitLsn)`

DDL(CREATE TABLE/DROP TABLE)도 동일하게 로깅한다 (Catalog 페이지 + HeapFile 헤더 페이지 수정).

## 내부 설계

### 로그 파일 레이아웃

데이터 파일(`*.db`)과 별도의 로그 파일(`*.db.wal`)을 사용한다.

```
[RecordLength(4B)][RecordType(1B)][LSN(4B)][TxnId(4B)][...payload...][RecordLength(4B)]
```

- 선두와 후미에 RecordLength를 배치하여 순방향/역방향 순회를 모두 지원한다.
- RecordType: BEGIN=0, COMMIT=1, ABORT=2, UPDATE=3, CLR=4, CHECKPOINT=5

#### Update 레코드 payload

```
[PageId(4B)][BeforeImage(4096B)][AfterImage(4096B)]
```

#### CLR 레코드 payload

```
[PageId(4B)][BeforeImage(4096B)][UndoNextLsn(4B)]
```

#### Checkpoint 레코드 payload

```
(비어있음 — Consistent Checkpoint이므로 DPT/ATT 불필요)
```

### LSN 관리

- LSN은 0부터 시작하는 단조 증가 정수(Int).
- 로그 파일 내 byte offset이 아닌 논리적 시퀀스 번호를 사용한다.
  - 이유: 구현 단순성. byte offset 방식은 세그먼트 파일 도입 시 전환 가능.
- `pageLsn`은 해당 페이지를 수정한 가장 최근 `Update`/`CLR` 레코드의 LSN.
- Redo 단계에서 `pageLsn >= record.lsn`이면 해당 레코드 skip (이미 반영됨).

### Consistent Checkpoint 알고리즘

```
1. 새 트랜잭션 시작을 차단한다
2. 현재 활성 트랜잭션이 없음을 확인한다 (Phase 5: 단일 txn, auto-commit)
3. BufferPoolManager.flushAllPages() — 모든 dirty page를 디스크에 기록
4. DiskManager.sync() — OS 수준 fsync
5. Checkpoint 로그 레코드를 append
6. LogManager.flush(checkpointLsn)
7. 메타데이터 페이지에 checkpointLsn 기록 (recovery 시작점)
```

### Recovery 알고리즘

```
function recover():
    checkpointLsn = logManager.lastCheckpointLsn()
    committedTxns = {}
    activeTxns = {}     // txnId → lastLsn

    // === Redo 단계 ===
    for record in logManager.forwardIterator(checkpointLsn):
        match record:
            Begin(txnId):
                activeTxns[txnId] = record.lsn
            Commit(txnId):
                committedTxns.add(txnId)
                activeTxns.remove(txnId)
            Abort(txnId):
                activeTxns.remove(txnId)
            Update(txnId, pageId, before, after):
                activeTxns[txnId] = record.lsn
                page = bpm.fetchPage(pageId)
                if page.pageLsn < record.lsn:
                    page.data = after
                    page.pageLsn = record.lsn
                    page.isDirty = true
                bpm.unpinPage(pageId)
            CLR(txnId, pageId, before, undoNextLsn):
                activeTxns[txnId] = record.lsn
                page = bpm.fetchPage(pageId)
                if page.pageLsn < record.lsn:
                    page.data = before
                    page.pageLsn = record.lsn
                    page.isDirty = true
                bpm.unpinPage(pageId)
            Checkpoint:
                pass  // consistent checkpoint — 정보 추가 불필요

    // === Undo 단계 ===
    // activeTxns에 남아있는 = 미커밋 트랜잭션 → 롤백
    while activeTxns is not empty:
        // 가장 큰 lastLsn부터 undo (역순)
        (txnId, lastLsn) = activeTxns에서 lastLsn가 가장 큰 항목
        record = logManager.getRecord(lastLsn)
        match record:
            Update(pageId, before, after):
                page = bpm.fetchPage(pageId)
                page.data = before  // undo: before-image 복원
                clrLsn = logManager.append(CLR(txnId, pageId, before, prevLsn(record)))
                page.pageLsn = clrLsn
                page.isDirty = true
                bpm.unpinPage(pageId)
                activeTxns[txnId] = prevLsn(record)
            CLR(undoNextLsn):
                activeTxns[txnId] = undoNextLsn  // CLR은 undo하지 않음
            Begin:
                logManager.append(Abort(txnId))
                activeTxns.remove(txnId)
        if activeTxns[txnId]의 값이 -1 (시작 이전):
            logManager.append(Abort(txnId))
            activeTxns.remove(txnId)

    bpm.flushAllPages()
    diskManager.sync()
```

> **CLR이 왜 필수인가?** Recovery 도중(Undo 단계) crash가 다시 발생하면,
> CLR이 없으면 이미 undo한 작업을 다시 undo하는 무한 루프에 빠진다.
> CLR의 `undoNextLsn`이 "다음에 undo할 레코드"를 가리키므로
> 이미 처리된 부분을 건너뛸 수 있다.

### prevLsn 체인

각 트랜잭션의 로그 레코드는 `prevLsn` 필드로 링크드 리스트를 형성한다.
Undo 단계에서 해당 트랜잭션의 이전 레코드를 빠르게 찾기 위한 것이다.

LogRecord에 `prevLsn: Int` 필드 추가 (Begin의 prevLsn은 -1).

### 메타데이터 페이지 확장

```
offset  size  field
0       4     magic (0x47574E42)
4       2     version (3 → Phase 5에서 버전 업)
6       4     catalogPageId
10      4     checkpointLsn    ← 신규
```

## 제약 사항 및 트레이드오프

| 제약 | 이유 | 해소 시점 |
|------|------|-----------|
| 단일 트랜잭션만 지원 | 동시성 제어 미구현 | Phase 6 |
| full-page image 로깅 (로그 크기 큼) | Torn write 방지, 구현 단순 | Phase 7에서 delta 로깅 |
| Consistent checkpoint (stop-the-world) | 단일 txn이므로 fuzzy 불필요 | Phase 6 |
| 로그 세그먼트 분할 없음 | 단일 파일로 충분 | Phase 8 |
| 로그 truncation 미구현 | checkpoint 이전 로그 삭제 가능하나 구현 연기 | Phase 7+ |
| auto-commit만 지원 | 명시적 BEGIN/COMMIT은 Phase 6 | Phase 6 |

## 테스트 시나리오

### LogManager 단위 테스트

- `로그 레코드 append 후 read 시 동일한 내용 반환`
- `LSN은 append 순서대로 단조 증가`
- `flush 후 파일을 다시 열어도 레코드 유지`
- `forwardIterator — checkpoint 이후 레코드만 순회`
- `backwardIterator — 역순 순회`
- `빈 로그 파일에서 Iterator는 빈 결과`

### RecoveryManager 단위 테스트

- `커밋된 트랜잭션 — crash 후 recovery 시 데이터 존재`
- `미커밋 트랜잭션 — crash 후 recovery 시 데이터 롤백`
- `Redo skip — pageLsn이 이미 높으면 재적용하지 않음`
- `CLR — Undo 도중 재crash 후 recovery 시 중복 undo 없음`
- `빈 로그 — recovery가 아무 작업 없이 성공`
- `Checkpoint 이후 crash — checkpoint 이전 변경은 이미 안전`

### 통합 테스트 (Crash Simulation)

- `INSERT 후 commit → crash → recovery → SELECT로 데이터 확인`
- `INSERT 후 commit 전 crash → recovery → SELECT로 데이터 없음 확인`
- `여러 INSERT commit → 중간에 crash → recovery → 커밋된 것만 존재`
- `UPDATE 후 commit → crash → recovery → 변경된 값 확인`
- `DELETE 후 commit → crash → recovery → 삭제 반영 확인`
- `CREATE TABLE 후 crash → recovery → 테이블 존재 확인`
- `Checkpoint 후 추가 INSERT → crash → recovery → 전체 데이터 무결성`

### Crash Simulation 방법

```kotlin
/**
 * DiskManager를 상속하여 특정 writePage 호출에서 crash를 시뮬레이션한다.
 * flush 없이 FileChannel을 강제 close하여 OS 버퍼에 남은 데이터를 유실시킨다.
 */
class CrashSimulatingDiskManager(path: Path) : DiskManager(path) {
    var crashAfterNWrites: Int = Int.MAX_VALUE
    private var writeCount = 0

    override fun writePage(pageId: Int, buffer: ByteBuffer) {
        if (++writeCount >= crashAfterNWrites) {
            // sync() 호출 없이 닫아서 OS 캐시 유실 시뮬레이션
            throw SimulatedCrashException()
        }
        super.writePage(pageId, buffer)
    }
}
```

## 구현 순서 (TDD)

```
Step 1: LogRecord 직렬화/역직렬화
Step 2: LogManager (append, flush, forward/backward iterator)
Step 3: Page에 pageLsn 필드 추가
Step 4: BufferPoolManager에 WAL 불변식 적용 (eviction 시 log flush)
Step 5: TransactionContext + Database에 auto-commit 트랜잭션 통합
Step 6: SqlExecutor에서 DML/DDL 로깅 (before/after image 캡처)
Step 7: RecoveryManager (Redo + Undo)
Step 8: Consistent Checkpoint
Step 9: Crash simulation 통합 테스트
Step 10: 메타데이터 페이지 version 업데이트 (2 → 3)
```

각 Step에서 **실패하는 테스트를 먼저 작성**하고 구현한다 (Red → Green → Refactor).

## 벤치마크 목표

- WAL 도입 전후 INSERT throughput 비교 (로깅 오버헤드 측정)
- Recovery 시간: 1000건 로그 레코드 기준 < 1초
- 로그 파일 크기: INSERT 1000건 기준 측정 (full-page image이므로 ~8MB 예상)

## 참고 자료

- Mohan et al., "ARIES: A Transaction Recovery Method" (1992) — 원본 논문
- CMU 15-445 Lecture 20: Recovery — ARIES 요약
- CMU 15-445 BusTub Project 4 (Fall 2018) — Consistent Checkpoint + Redo/Undo 과제
- Edward Sciore, *Database Design and Implementation* (SimpleDB) — Undo-only recovery
- SQLite WAL: https://sqlite.org/wal.html — 비교 참고
- PostgreSQL WAL Internals: https://www.postgresql.org/docs/current/wal-internals.html — full-page write 참고
