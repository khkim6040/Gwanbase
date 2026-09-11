# Architecture

## 전체 구조

```
┌─────────────────────────────────────────────┐
│   psql / JDBC  (PostgreSQL Wire Protocol)    │  Phase 8  server/
├─────────────────────────────────────────────┤
│  DatabaseSession  (BEGIN/COMMIT, lock_timeout)│  Phase 6  txn/
├─────────────────────────────────────────────┤
│         Lexer → Parser → Binder              │  Phase 3  sql/
├─────────────────────────────────────────────┤
│   Optimizer (RBO + 통계, 조인 순서, EXPLAIN)  │  Phase 7  optimizer/
├─────────────────────────────────────────────┤
│  Planner → Operator 트리 (Volcano Model)      │  Phase 4  execution/
├─────────────────────────────────────────────┤
│  LockManager (Strict 2PL) │ WAL / Recovery   │  Phase 6 / 5  txn/, wal/
├─────────────────────────────────────────────┤
│  Database · Catalog · HeapFile │ B+Tree Index │  Phase 2 / 7  table/, index/
├─────────────────────────────────────────────┤
│             Buffer Pool Manager              │  Phase 1  storage/
├─────────────────────────────────────────────┤
│               Disk Manager                   │  Phase 1  storage/
└─────────────────────────────────────────────┘
```

## 모듈 구조

```
core/
├── storage/     Phase 1 ✅ DiskManager, BufferPool, SlottedPage
├── index/       Phase 1 ✅ B+Tree (BPlusTreeNode, BPlusTree), KeySerializer
├── kv/          Phase 1 ✅ KVStore (public Key-Value API)
├── table/       Phase 2 ✅ Schema, Tuple, HeapFile, Catalog, Database
├── sql/         Phase 3 ✅ Lexer, Parser, AST, Binder, SqlExecutor, 예외 타입
├── execution/   Phase 4 ✅ Operators (SeqScan, IndexScan, Filter, Project, Sort, Limit, NLJ), Planner
├── wal/         Phase 5 ✅ LogRecord, LogManager, RecoveryManager
├── txn/         Phase 6 ✅ LockManager, DatabaseSession
├── optimizer/   Phase 7 ✅ PlanNode, StatisticsManager, CostEstimator, PlanEnumerator
└── server/      Phase 8 ✅ GwanServer, ConnectionHandler, PgMessage, ResultFormatter
```

모듈 의존 방향: `server → txn → execution → sql → table → index → storage`
(`wal`은 `storage`의 `WalCallback` 인터페이스로 의존성을 역전시켜 연결)

## SQL 실행 경로

한 문장이 처리되는 흐름. 각 단계의 담당 클래스와 Phase를 함께 표기한다.

```
ConnectionHandler (P8)     Simple Query 메시지 수신 → 세션에 위임, 예외를 SQLSTATE로 변환
  └─ DatabaseSession (P6)  BEGIN/COMMIT/ROLLBACK 처리, auto-commit 트랜잭션 생성
       ├─ Lexer/Parser (P3)   문자열 → AST
       ├─ Binder (P3)         테이블/컬럼/타입 검증 (BindException)
       ├─ Optimizer (P7)      AST → PlanNode (인덱스 선택, 조인 순서)
       ├─ Planner (P4)        PlanNode → Operator 트리
       ├─ Operator.next() (P4) Volcano pull 실행
       │    ├─ ExpressionEvaluator  산술·비교·NULL 3값 논리 (DataException)
       │    └─ LockManager (P6)     RID 단위 S/X 잠금 (Deadlock/LockTimeout)
       └─ Database/HeapFile (P2) 튜플 읽기·쓰기
            └─ WalCallback → LogManager (P5)  변경 전 WAL 기록
                 └─ BufferPoolManager → DiskManager (P1)
```

## 에러 전달 경로

코어 예외는 타입별로 PostgreSQL SQLSTATE에 매핑되어 `ErrorResponse`로 전달된다.
클라이언트는 `SQLException.getSQLState()`로 분기할 수 있다.

| 예외 | SQLSTATE | 발생 위치 |
|------|----------|-----------|
| `ParseException` | 42601 | Parser |
| `BindException` | 42000 | Binder |
| `DataException` | 22xxx (필드) | ExpressionEvaluator, SqlExecutor.coerceValue |
| `DeadlockException` | 40P01 | LockManager |
| `LockTimeoutException` | 55P03 | LockManager |
| 그 외 | XX000 | — |

상세는 `docs/specs/advanced.md`의 "Constraints & Error Semantics" 참조.

## Phase 1 완료 상태 (v0.1-kvstore)

스토리지 레이어 + B+Tree + KVStore까지 완성된 persistent key-value store.
상세 설계는 `docs/specs/phase-1-kv-store.md` 참조.

### 계층 구조 (아래 → 위 방향 의존)

```
KVStore (gwanbase.kv)        ← public API: put/get/delete/scan/close
  └── BPlusTree (gwanbase.index)
        └── BPlusTreeNode        ← 18B 고정 헤더 + 정렬 슬롯 디렉터리
                └── BufferPoolManager (gwanbase.storage)
                      ├── LruReplacer
                      └── DiskManager
                            └── FileChannel (java.nio)
```

### 파일 레이아웃

```
pageId 0         메타데이터 페이지 (magic "GWNB", version, rootPageId)
pageId 1 ..      B+Tree 노드들 (leaf / internal)
```

### 핵심 설계 결정
- B+Tree 노드는 SlottedPage 재사용 대신 **정렬 슬롯 전용 레이아웃** 사용
  (SlottedPage 슬롯 ID는 삽입 순서라 이진 탐색·split 불가)
- 내부 노드는 **leftmost-child 규약**: slot[i].child는 keys ≥ slot[i].key
  서브트리, `leftmostChildPageId`는 `< slot[0].key` 서브트리. 자식 split
  전파가 단순 `insertInternalEntry(promoteKey, newRight)` 한 번으로 끝남
- 키/값 비교는 unsigned lexicographic (0x80+ 바이트 정렬 정확성)
- 삭제는 lazy (리프 슬롯만 제거, 레코드는 dead space, merge/rebalance 없음)
- B+Tree order는 free-space 기반 동적 결정 (가변 길이 키 지원)
- 메타데이터 원자성은 Phase 5의 WAL로 보장

## Phase 2 완료 상태 (v0.2-table)

스토리지 레이어 위에 관계형 테이블 저장소 구축.
상세 설계는 `docs/specs/phase-2-table-storage.md` 참조.

### 계층 구조

```
Database (gwanbase.table)        ← 진입점: open/close, 테이블 CRUD
  ├── Catalog                    ← 메타데이터 영속 (전용 페이지)
  └── HeapFile                   ← 튜플 저장 (Free Page List)
        └── HeapPage
              └── SlottedPage (gwanbase.storage)
                    └── BufferPoolManager → DiskManager
```

### 파일 레이아웃

```
pageId 0    DB 메타데이터 (magic "GWNB", version=2, catalogPageId)
pageId 1    Catalog 페이지 (테이블/인덱스 메타데이터)
pageId 2+   HeapFile 헤더/데이터 페이지 (동적 할당)
```

### 핵심 설계 결정
- 단일 파일 레이아웃 (SQLite 방식, PostgreSQL 방식 파일 분리는 고도화 로드맵)
- RID `(pageId, slotId)` 기반 튜플 식별 (PostgreSQL 방식)
- Free Page List로 빈 공간 관리
- Catalog 전용 페이지에 바이너리 직렬화
- Tuple은 null bitmap + 스키마 순서 직렬화 (BIG_ENDIAN)
- HeapPage는 SlottedPage를 offset 4 ByteBuffer slice로 감싸서 재사용

## Phase 3 완료 상태 (v0.3-sql)

Lexer → Parser → Binder → Executor 파이프라인. 상세는 `docs/specs/phase-3-sql-frontend.md`.

- Pratt parsing으로 연산자 우선순위 처리 (OR < AND < NOT < 비교 < 산술 < 단항)
- AST에 별도 Bound 타입 없이 Binder가 검증만 수행
- SQL 3값 논리(NULL 전파) 구현

## Phase 4 완료 상태 (v0.4-execution)

Volcano(Iterator) 모델 실행 엔진. 상세는 `docs/specs/phase-4-query-execution.md`.

- `open() → next() → close()` pull 기반, Sort만 blocking operator
- Planner가 AST → `SeqScan → Filter → Sort → Limit → Project` 트리 생성
- `ExpressionEvaluator`를 분리해 Filter/Project/UPDATE에서 재사용

## Phase 5 완료 상태 (v0.5-wal)

WAL 기반 크래시 복구. 상세는 `docs/specs/phase-5-crash-recovery.md`.

- Steal/No-Force, 페이지 수준 물리적 로깅 (full before/after image)
- 2단계 Recovery: Redo + Undo with CLR
- `WalCallback` 인터페이스로 storage ↔ wal 의존성 역전
- `Database.close()`에서 Checkpoint, `open()`에서 Recovery

## Phase 6 완료 상태 (v0.6-txn)

Strict 2PL 동시성 제어. 상세는 `docs/specs/phase-6-concurrency-control.md`.

- RID 기반 행 수준 S/X 잠금, Waits-For Graph DFS로 데드락 감지
- `DatabaseSession`이 세션별 트랜잭션 상태 보유, `Database`는 세션 팩토리
- UPDATE/DELETE는 변경 시점에만 X 잠금 (S→X 업그레이드 데드락 방지)
- 세션별 `lockTimeoutMillis` (기본 0=무한) — 초과 시 `LockTimeoutException`

## Phase 7 완료 상태 (v0.7-optimizer)

RBO + 통계 기반 옵티마이저. 상세는 `docs/specs/phase-7-query-optimizer.md`.

- `AST → Optimizer → PlanNode → Planner → Operator` 파이프라인
- Secondary Index (B+Tree → RID), 단일 컬럼 등가 조건
- INNER JOIN + Nested Loop Join, greedy 조인 순서
- `ANALYZE`로 통계 수집, `EXPLAIN`으로 계획 출력

## Phase 8 완료 상태 (v0.8-networking)

PostgreSQL Wire Protocol v3.0 Simple Query 서브셋. 상세는 `docs/specs/phase-8-networking.md`.

- Thread-per-connection, 연결당 `DatabaseSession` 바인딩
- trust 인증 (AuthenticationOk 고정), 텍스트 포맷 결과
- `psql`, JDBC(`org.postgresql`)로 접속 가능

## 고도화 (Phase 8 이후)

MVP 완성 후에는 `docs/specs/advanced.md` 로드맵을 따라 PostgreSQL internals에
가깝게 고도화한다. 진행 상황은 해당 문서의 ✅ 표시로 추적한다.

## 설계 원칙

1. **Page-oriented**: 모든 데이터는 고정 크기(4KB) 페이지 단위로 관리
2. **Buffer Pool 중심**: 디스크 접근은 반드시 Buffer Pool을 통해서만 수행
3. **Volcano Model**: 쿼리 실행은 iterator 기반 pull model
4. **WAL first**: 데이터 변경 전에 로그를 먼저 디스크에 기록
5. **정확성 우선**: 최적화는 정확한 동작이 검증된 후에 진행

## 핵심 설계 결정 기록

### 왜 4KB 페이지인가?
- OS 페이지 크기와 동일하여 I/O 효율이 좋다
- SSD의 일반적인 쓰기 단위와도 일치
- SQLite(4KB), PostgreSQL(8KB), MySQL InnoDB(16KB) 등 참고

### 왜 SlottedPage인가?
- 가변 길이 레코드를 효율적으로 지원
- 레코드 이동 시 슬롯만 갱신하면 되므로 외부 참조(인덱스)가 깨지지 않음
- PostgreSQL, SQLite 모두 유사한 구조 사용

### 왜 B+Tree인가?
- 범위 쿼리에 유리 (리프 노드가 연결 리스트)
- 디스크 기반 DB의 사실상 표준 인덱스 구조
- Hash Index는 동등 비교만 지원하므로 보조 수단으로 추후 추가 가능

### 왜 Strict 2PL인가? (MVCC 대신)
- 단일 락 매니저로 Serializable을 보장하는 가장 단순한 방법
- MVCC는 버전 체인·가비지 컬렉션·스냅샷이 필요해 학습 순서상 뒤로 미룸
- PostgreSQL은 MVCC + 행 잠금 혼합, 고도화 로드맵(`advanced.md`)에서 다룸

### 왜 예외 타입에 SQLSTATE를 싣는가?
- 에러 종류마다 클래스를 만들면 서버 매핑 표가 무한히 자라므로,
  `DataException`처럼 코드를 필드로 가진 타입 하나로 한 Class를 덮는다
- PostgreSQL의 `ereport(ERROR, errcode(...))`가 코드를 값으로 다루는 방식과 같다
