# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**Gwanbase** — Kotlin으로 관계형 데이터베이스를 밑바닥부터 구현하는 학습용 프로젝트.
퍼블릭 GitHub 레포지토리로 관리하며, DB Internals 학습이 목적이다.

## 핵심 원칙

- **한국어로 커뮤니케이션한다.** 코드 내 주석, 커밋 메시지, 문서 모두 한국어 우선. 단, 코드 식별자(클래스/함수/변수명)는 영어를 사용한다.
- **각 Phase는 독립적으로 동작하는 데이터베이스다.** Phase N이 끝나면 그 시점에서 테스트 가능한 완성된 결과물이 있어야 한다.
- **정확성이 성능보다 우선이다.** 최적화는 정확한 동작이 검증된 후에 진행한다.
- **설계 결정의 이유(why)를 반드시 기록한다.** 다른 DB가 왜 다른 선택을 했는지와 비교하면 학습 효과가 극대화된다.

## 기술 스택

- Language: Kotlin 1.9.22 (JVM 17)
- Build: Gradle Kotlin DSL (멀티모듈: core, bench)
- Test: JUnit 5 + Kotest assertions + Kotest property-based testing
- Benchmark: JMH
- Logging: kotlin-logging + Logback
- Netty Buffer: `io.netty:netty-buffer:4.1.104.Final` (core 모듈, ByteBuffer 유틸리티)

## 프로젝트 구조

```
Gwanbase/                      ← 프로젝트 루트 = Gradle 프로젝트 루트
├── CLAUDE.md
├── build.gradle.kts           ← 루트 빌드 (공통 의존성, group: dev.gwanbase)
├── settings.gradle.kts
├── core/                      ← 메인 모듈
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/gwanbase/
│       │   ├── storage/       ← Phase 1: 디스크 I/O, 버퍼 풀, 페이지
│       │   ├── index/         ← Phase 1: B+Tree
│       │   ├── kv/            ← Phase 1: KVStore
│       │   ├── table/         ← Phase 2: Schema, Tuple, HeapFile, Catalog, Database
│       │   ├── sql/           ← Phase 3: Lexer, Parser, Binder, SqlExecutor
│       │   ├── execution/     ← Phase 4: Operator, Planner, ExpressionEvaluator
│       │   └── wal/           ← Phase 5: LogRecord, LogManager, RecoveryManager
│       └── test/kotlin/gwanbase/
│           ├── storage/
│           ├── index/
│           ├── kv/
│           ├── table/
│           ├── sql/
│           ├── execution/
│           └── wal/
├── bench/                     ← JMH 벤치마크 모듈
│   └── build.gradle.kts
└── docs/                      ← Phase별 스펙 문서, 아키텍처 문서
```

## 로드맵 및 현재 진행 상황

현재 작업 브랜치: `main`

```
Phase 0  Scaffolding & Tooling           ✅ 완료
Phase 1  Persistent Key-Value Store      ✅ 완료 (tag v0.1-kvstore)
Phase 2  Table Storage Engine            ✅ 완료 (tag v0.2-table)
Phase 3  SQL Frontend                    ✅ 완료 (tag v0.3-sql)
Phase 4  Query Execution Engine          ✅ 완료 (tag v0.4-execution)
Phase 5  Crash Recovery (WAL)            ✅ 완료 (tag v0.5-wal)
Phase 6  Concurrency Control             ✅ 완료 (tag v0.6-txn)
Phase 7  Query Optimizer                 ⬜ 다음 작업
Phase 8  Networking & Client Protocol    ⬜ 대기
```

### Phase 1 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| DiskManager | ✅ | `core/src/main/kotlin/gwanbase/storage/DiskManager.kt` |
| Page | ✅ | `core/src/main/kotlin/gwanbase/storage/Page.kt` |
| LruReplacer | ✅ | `core/src/main/kotlin/gwanbase/storage/LruReplacer.kt` |
| BufferPoolManager | ✅ | `core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt` |
| SlottedPage | ✅ | `core/src/main/kotlin/gwanbase/storage/SlottedPage.kt` |
| ByteBuffer 확장 함수 | ✅ | `core/src/main/kotlin/gwanbase/storage/ByteBufferExtensions.kt` |
| BPlusTreeNode | ✅ | `core/src/main/kotlin/gwanbase/index/BPlusTreeNode.kt` |
| BPlusTree | ✅ | `core/src/main/kotlin/gwanbase/index/BPlusTree.kt` |
| KVStore | ✅ | `core/src/main/kotlin/gwanbase/kv/KVStore.kt` |

### Phase 2 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| DataType | ✅ | `core/src/main/kotlin/gwanbase/table/DataType.kt` |
| Column | ✅ | `core/src/main/kotlin/gwanbase/table/Column.kt` |
| Schema | ✅ | `core/src/main/kotlin/gwanbase/table/Schema.kt` |
| RID | ✅ | `core/src/main/kotlin/gwanbase/table/RID.kt` |
| Tuple | ✅ | `core/src/main/kotlin/gwanbase/table/Tuple.kt` |
| HeapPage | ✅ | `core/src/main/kotlin/gwanbase/table/HeapPage.kt` |
| HeapFile | ✅ | `core/src/main/kotlin/gwanbase/table/HeapFile.kt` |
| Catalog | ✅ | `core/src/main/kotlin/gwanbase/table/Catalog.kt` |
| Database | ✅ | `core/src/main/kotlin/gwanbase/table/Database.kt` |

### Phase 3 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| Token, TokenType | ✅ | `core/src/main/kotlin/gwanbase/sql/Token.kt` |
| ParseException, BindException | ✅ | `core/src/main/kotlin/gwanbase/sql/SqlException.kt` |
| Lexer | ✅ | `core/src/main/kotlin/gwanbase/sql/Lexer.kt` |
| AST (Statement, Expression) | ✅ | `core/src/main/kotlin/gwanbase/sql/Ast.kt` |
| Parser | ✅ | `core/src/main/kotlin/gwanbase/sql/Parser.kt` |
| Binder | ✅ | `core/src/main/kotlin/gwanbase/sql/Binder.kt` |
| SqlExecutor, ExecuteResult | ✅ | `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` |

### Phase 4 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| Operator 인터페이스 | ✅ | `core/src/main/kotlin/gwanbase/execution/Operator.kt` |
| ExpressionEvaluator | ✅ | `core/src/main/kotlin/gwanbase/execution/ExpressionEvaluator.kt` |
| SeqScanOperator | ✅ | `core/src/main/kotlin/gwanbase/execution/SeqScanOperator.kt` |
| FilterOperator | ✅ | `core/src/main/kotlin/gwanbase/execution/FilterOperator.kt` |
| ProjectOperator | ✅ | `core/src/main/kotlin/gwanbase/execution/ProjectOperator.kt` |
| SortOperator | ✅ | `core/src/main/kotlin/gwanbase/execution/SortOperator.kt` |
| LimitOperator | ✅ | `core/src/main/kotlin/gwanbase/execution/LimitOperator.kt` |
| Planner | ✅ | `core/src/main/kotlin/gwanbase/execution/Planner.kt` |

### Phase 5 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| LogRecord | ✅ | `core/src/main/kotlin/gwanbase/wal/LogRecord.kt` |
| LogManager | ✅ | `core/src/main/kotlin/gwanbase/wal/LogManager.kt` |
| TransactionContext | ✅ | `core/src/main/kotlin/gwanbase/wal/TransactionContext.kt` |
| WalCallback | ✅ | `core/src/main/kotlin/gwanbase/storage/WalCallback.kt` |
| WalCallbackImpl | ✅ | `core/src/main/kotlin/gwanbase/wal/WalCallbackImpl.kt` |
| RecoveryManager | ✅ | `core/src/main/kotlin/gwanbase/wal/RecoveryManager.kt` |

### Phase 6 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| LockManager | ✅ | `core/src/main/kotlin/gwanbase/txn/LockManager.kt` |
| DatabaseSession | ✅ | `core/src/main/kotlin/gwanbase/txn/DatabaseSession.kt` |

## 빌드 및 테스트 명령어

```bash
./gradlew build                    # 전체 빌드
./gradlew test                     # 테스트 실행
./gradlew :core:test               # core 모듈 테스트만
./gradlew :core:test --tests "gwanbase.storage.DiskManagerTest"           # 특정 테스트 클래스
./gradlew :core:test --tests "gwanbase.storage.DiskManagerTest.빈 페이지*" # 특정 테스트 메서드
./gradlew bench:jmh                # JMH 벤치마크
```

## 작업 완료 체크리스트

코드 변경 후 반드시 다음을 확인한다:
1. `./gradlew :core:test` 전체 통과
2. 새로 추가한 public 클래스/함수에 KDoc 한국어 주석 존재
3. 테스트 메서드명이 백틱 한국어 형식 (예: `` `삽입 후 조회 시 동일한 값 반환` ``)
4. `require()`/`check()`로 전제조건·상태 검증 포함
5. 커밋 메시지가 `[Phase N] 설명` 형식

## 아키텍처: 스토리지 레이어 (Phase 1 완료 부분)

계층 구조 (아래→위 방향으로 의존):

```
BufferPoolManager  ← 메모리 페이지 풀, fetchPage/newPage/unpinPage (@Synchronized)
  ├── DiskManager  ← 파일 기반 고정 크기(4KB) 페이지 I/O, AutoCloseable
  ├── Page         ← 메모리 상의 페이지 프레임 (pin count, dirty flag, RW latch)
  └── LruReplacer  ← eviction 정책 (victim/pin/unpin, @Synchronized)

SlottedPage        ← 가변 길이 레코드의 페이지 내 저장 (슬롯 디렉토리, 역방향 배치)
ByteBufferExtensions ← length-prefixed 읽기/쓰기, newPageBuffer() 유틸리티
```

핵심 설계 결정:
- 페이지 크기: 4KB 고정 (`DiskManager.PAGE_SIZE`)
- ByteOrder: BIG_ENDIAN (SlottedPage, ByteBufferExtensions)
- Page.data: `ByteBuffer.allocateDirect` 사용
- BufferPoolManager: 모든 public 메서드에 `@Synchronized` (단일 락)
- SlottedPage: 논리 삭제 방식 (DELETED_MARKER = -1), compaction은 미구현

## 코딩 컨벤션

### 코드 스타일
- Kotlin 공식 코딩 컨벤션을 따른다.
- 클래스/함수에 KDoc 주석을 작성한다. 주석 내용은 한국어로 쓴다.
- `require()`로 전제 조건을 검증하고, `check()`로 상태를 검증한다.
- `AutoCloseable`을 적극 활용한다 (DiskManager, KVStore 등).

### 패키지 구조
- 패키지는 `gwanbase.<모듈명>`으로 시작한다. (예: `gwanbase.storage`, `gwanbase.index`)
- 계획된 패키지: `gwanbase.table`, `gwanbase.sql`, `gwanbase.execution`, `gwanbase.txn`, `gwanbase.optimizer`, `gwanbase.server`
- 모듈 간 의존 방향: `server → txn → execution → sql → table → index → storage`
- 하위 모듈이 상위 모듈을 참조하면 안 된다.

### 테스트 (TDD)
- **테스트를 먼저 작성한다 (Red → Green → Refactor).** 구현 코드보다 테스트를 먼저 작성하고, 테스트가 실패하는 것을 확인한 후 구현한다.
- 새 기능/버그 수정 시 반드시 실패하는 테스트부터 작성한다. 테스트 없이 구현 코드를 먼저 작성하지 않는다.
- 모든 public API에 대해 단위 테스트를 작성한다.
- 테스트 클래스명: `{대상클래스}Test` (예: `BPlusTreeTest`)
- 테스트 메서드명: 백틱으로 한국어 설명 (예: `` `삽입 후 조회 시 동일한 값 반환` ``)
- `@TempDir`을 사용하여 테스트 간 파일 격리를 보장한다.
- 엣지 케이스를 반드시 포함한다: 빈 상태, 경계값, 대량 데이터, 에러 케이스.
- 가능하면 Kotest property-based testing으로 불변 조건(invariant)을 검증한다.

### 금지 사항 (Don'ts)
- `BufferPoolManager`를 우회하여 `DiskManager`로 직접 I/O하지 않는다.
- 테스트에서 파일 경로를 하드코딩하지 않는다. 반드시 `@TempDir`을 사용한다.
- `Page.data`를 수정한 후 `dirty = true` 설정을 빠뜨리지 않는다.
- `ByteBuffer`의 position/limit을 조작한 후 복원하지 않고 방치하지 않는다.
- 구현 코드를 테스트 없이 먼저 작성하지 않는다. (TDD 섹션과 중복이지만 강조)

### 커밋
- 커밋 메시지 포맷: `[Phase N] 간결한 설명`
- 예: `[Phase 1] B+Tree 삽입 및 검색 구현`
- 예: `[Phase 1] BufferPoolManager eviction 버그 수정`

### 브랜치
- `main`: 안정 브랜치
- `phase-N/<기능>`: 기능 개발 브랜치
- Phase 완료 시 태그: `v0.1-kvstore`, `v0.2-table` 등

## Phase별 설계 가이드

현재 진행 중인 Phase의 상세 가이드만 이 파일에 포함한다. 완료된 Phase와
이후 Phase는 `docs/specs/phase-N-<name>.md`를 참조한다.

### Phase 1 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-1-kv-store.md`
참조. 완료된 주요 결정 요약:

- 4KB 고정 페이지 × B+Tree × LRU 버퍼 풀
- 내부 노드는 **leftmost-child 규약** (slot[i].child가 keys ≥ slot[i].key
  담당, leftmostChildPageId는 `< slot[0].key` 담당) — 자식 split 시 부모
  업데이트가 `insertInternalEntry(promoteKey, newRight)` 단일 호출로 끝남
- 리프 삭제는 lazy (슬롯 디렉터리에서만 제거, 레코드는 dead space)
- 메타데이터 페이지(pageId=0)가 magic/version/rootPageId 보존
- WAL 미도입 상태이므로 crash 일관성 미보장 (Phase 5에서 추가)

### Phase 2 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-2-table-storage.md`
참조. 완료된 주요 결정 요약:

- 단일 파일 레이아웃 (SQLite 방식) — **향후 PostgreSQL 방식(테이블/인덱스별 파일 분리)으로 고도화 예정**
- RID `(pageId, slotId)` 기반 튜플 식별 (PostgreSQL 방식)
- Free Page List로 빈 공간 관리
- Catalog 전용 페이지에 바이너리 직렬화
- HeapPage는 SlottedPage를 ByteBuffer slice로 감싸서 재사용
- Tuple 직렬화: null bitmap + 스키마 순서 직렬화 (BIG_ENDIAN)

### Phase 3 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-3-sql-frontend.md`
참조. 완료된 주요 결정 요약:

- Lexer → Parser → Binder → Executor 4단계 파이프라인
- Pratt parsing으로 연산자 우선순위 처리 (OR < AND < NOT < 비교 < 산술 < 단항)
- AST에 별도 Bound 타입 없이 검증만 수행 (지원 문법이 작아 충분)
- Executor는 전체 스캔 후 인메모리 처리 (Phase 4 Volcano 모델 도입 시 교체)
- SQL 3값 논리(NULL 전파) 구현, SELECT 결과는 List에 전부 적재
- Phase 4 전환 시 Executor만 교체, Lexer/Parser/Binder는 그대로 재사용

### Phase 4 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-4-query-execution.md`
참조. 완료된 주요 결정 요약:

- Volcano (Iterator) 모델: `open() → next() → close()` pull 기반 실행
- 연산자: SeqScan, Filter, Project, Sort (in-memory), Limit
- Planner가 AST → 연산자 트리 변환 (SeqScan → Filter → Sort → Limit → Project)
- Project를 트리 최상단에 배치 — Sort가 정렬 키 접근 가능하도록
- ExpressionEvaluator를 SqlExecutor에서 분리 — 여러 연산자에서 재사용
- SELECT는 Volcano 모델, UPDATE/DELETE는 RID 필요로 직접 스캔 + ExpressionEvaluator
- DDL/INSERT는 연산자 트리 미사용 (스캔 불필요한 단순 명령)
- Sort는 blocking operator (전체 materialization), 나머지는 pipeline operator

### Phase 5 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-5-crash-recovery.md`
참조. 완료된 주요 결정 요약:

- Steal/No-Force 정책으로 페이지 수준 물리적 로깅 (full before/after image)
- Consistent Checkpoint: 단일 트랜잭션이므로 fuzzy checkpoint 불필요
- 2단계 Recovery: Redo + Undo with CLR (Analysis 단계 생략)
- WalCallback 인터페이스로 storage와 wal 의존성 역전
- Database.executeSql()이 auto-commit 트랜잭션으로 DML/DDL 실행
- Database.close()에서 Checkpoint, open()에서 Recovery 수행

### Phase 6 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-6-concurrency-control.md`
참조. 완료된 주요 결정 요약:

- Strict 2PL로 Serializable 격리 수준 보장
- RID 기반 행 수준 S/X 잠금, Waits-For Graph DFS 데드락 감지
- DatabaseSession 객체로 세션별 독립 트랜잭션 관리
- BEGIN/COMMIT/ROLLBACK SQL 지원, auto-commit 호환 유지
- Database는 세션 팩토리로 축소, ThreadLocal로 WalCallback 연동
- UPDATE/DELETE는 변경 시점에만 X 잠금 (S→X 업그레이드 데드락 방지)

### Phase 7 (다음 작업 — 대기)

Phase 7 시작 시점에 스펙을 작성하고 TDD로 진행한다.

## 문서 관리

- **각 Phase에는 단 하나의 스펙 문서만 존재한다** (`docs/specs/phase-N-<name>.md`).
  Phase 시작 시점에 초안을 작성하고, 이후 구현 과정에서 알게 된 세부 설계·결정·
  트레이드오프는 **기존 문서를 확장(extend)** 하는 방식으로 반영한다.
- **신규 서브 스펙 문서를 만들지 않는다.** 예컨대 Phase 1에서 B+Tree 상세 설계가
  필요하면 `phase-1-bplustree.md`를 새로 만들지 말고 `phase-1-kv-store.md`의
  "내부 설계" 하위 섹션을 확장한다. 관심사가 커지면 같은 문서 내에서
  섹션/서브섹션으로 구조화한다.
- 스펙 문서 템플릿 (초안 단계):
  ```
  # Phase N: <기능명>
  ## 목표
  ## 비기능 요구사항
  ## 인터페이스 설계 (public API)
  ## 내부 설계 (데이터 구조, 알고리즘)
  ## 제약 사항 및 트레이드오프
  ## 테스트 시나리오
  ## 벤치마크 목표
  ## 참고 자료
  ```
- 구현이 진행되면 "내부 설계"는 컴포넌트별 하위 섹션으로, "테스트 시나리오"는
  컴포넌트/레이어별로 확장된다. 문서가 길어지는 것은 정상이며, 분할보다
  잘 짜인 목차를 우선한다.
- 스펙 문서 변경 이력은 git history로 추적한다. 문서 내부에 "Changelog" 섹션을
  두지 않는다.
- Phase 완료 시 `docs/ARCHITECTURE.md`를 업데이트한다.
- 설계 결정을 내릴 때는 "왜 이 선택인가? 다른 DB는 어떻게 했나?"를 기록한다.

## 참고 자료

- *Database Internals* (Alex Petrov) — 스토리지 엔진 중심
- *Architecture of a Database System* (Hellerstein) — 전체 아키텍처 (무료 논문)
- *Crafting Interpreters* (Robert Nystrom) — SQL 파서 구현 참고
- CMU 15-445 (Andy Pavlo) — 유튜브 무료 강의, 실습 프로젝트
- CMU 15-721 — 고급 주제 (MVCC, 컴파일, 벡터화)
- SQLite 소스 코드 (btree.c, pager.c) — 실제 구현 참고
- toydb (Rust), SimpleDB (Java), bustub (C++) — 교육용 DB 프로젝트
