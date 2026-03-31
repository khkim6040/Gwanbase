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
- 추가 의존성: Netty Buffer (core 모듈, ByteBuffer 유틸리티)

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
│       │   └── index/         ← Phase 1: B+Tree (미구현)
│       └── test/kotlin/gwanbase/
│           └── storage/
├── bench/                     ← JMH 벤치마크 모듈
│   └── build.gradle.kts
└── docs/                      ← Phase별 스펙 문서, 아키텍처 문서
```

## 로드맵 및 현재 진행 상황

```
Phase 0  Scaffolding & Tooling           ✅ 완료
Phase 1  Persistent Key-Value Store      🔄 진행 중 (스토리지 레이어 완료, B+Tree 남음)
Phase 2  Table Storage Engine            ⬜ 대기
Phase 3  SQL Frontend                    ⬜ 대기
Phase 4  Query Execution Engine          ⬜ 대기
Phase 5  Crash Recovery (WAL)            ⬜ 대기
Phase 6  Concurrency Control             ⬜ 대기
Phase 7  Query Optimizer                 ⬜ 대기
Phase 8  Networking & Client Protocol    ⬜ 대기
```

### Phase 1 세부 진행 상황

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| DiskManager | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/DiskManager.kt` |
| Page | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/Page.kt` |
| LruReplacer | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/LruReplacer.kt` |
| BufferPoolManager | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt` |
| SlottedPage | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/SlottedPage.kt` |
| ByteBuffer 확장 함수 | ✅ 완료 | `core/src/main/kotlin/gwanbase/storage/ByteBufferExtensions.kt` |
| **B+Tree** | **⬜ 다음 작업** | `core/src/main/kotlin/gwanbase/index/BPlusTree.kt` |
| **KVStore 인터페이스** | **⬜ 다음 작업** | 위치 미정 |

## 빌드 및 테스트 명령어

```bash
./gradlew build                    # 전체 빌드
./gradlew test                     # 테스트 실행
./gradlew :core:test               # core 모듈 테스트만
./gradlew :core:test --tests "gwanbase.storage.DiskManagerTest"           # 특정 테스트 클래스
./gradlew :core:test --tests "gwanbase.storage.DiskManagerTest.빈 페이지*" # 특정 테스트 메서드
./gradlew bench:jmh                # JMH 벤치마크
```

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
- 모듈 간 의존 방향: `server → execution → sql → table → index → storage`
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

### 커밋
- 커밋 메시지 포맷: `[Phase N] 간결한 설명`
- 예: `[Phase 1] B+Tree 삽입 및 검색 구현`
- 예: `[Phase 1] BufferPoolManager eviction 버그 수정`

### 브랜치
- `main`: 안정 브랜치
- `phase-N/<기능>`: 기능 개발 브랜치
- Phase 완료 시 태그: `v0.1-kvstore`, `v0.2-table` 등

## Phase별 설계 가이드

### Phase 1: B+Tree 구현 가이드 (다음 작업)

**B+Tree 핵심 설계:**
- 리프 노드: `key-value` 쌍 저장 + 다음 리프 페이지 ID (범위 스캔용)
- 내부 노드: `key-childPageId` 쌍 저장
- 노드 1개 = 페이지 1개 (4KB). order는 페이지 크기에서 자동 계산
- 삽입 시 overflow → split, 삭제는 Phase 1에서 lazy(마킹만)
- 모든 노드는 `BufferPoolManager`를 통해 접근 (직접 디스크 I/O 금지)

**구현 순서 권장:**
1. `BPlusTreeNode` (리프/내부 노드의 페이지 내 레이아웃)
2. `BPlusTree.search(key)` — 루트에서 리프까지 탐색
3. `BPlusTree.insert(key, value)` — 삽입 + 노드 분할
4. `BPlusTree.delete(key)` — 논리 삭제 (lazy)
5. `BPlusTree.scan(startKey, endKey)` — 리프 노드 순회
6. `KVStore` — B+Tree를 감싸는 public API

**노드 페이지 레이아웃 (제안):**
```
[NodeHeader]
  nodeType: Byte        (0=internal, 1=leaf)
  keyCount: Short
  parentPageId: Int
  // leaf only:
  nextLeafPageId: Int

[Keys & Values/Children]
  내부 노드: [child0][key0][child1][key1]...[childN]
  리프 노드: [key0][val0][key1][val1]...
```

**테스트 시나리오:**
- 순차 삽입 1~1000 → 전체 검색 일치
- 랜덤 삽입 10,000건 → 전체 검색 일치
- 범위 스캔 정확성
- 삽입으로 인한 split 후 트리 무결성 (모든 키 검색 가능)
- 존재하지 않는 키 검색 → null

### Phase 2: Table Storage Engine

스펙 문서를 `docs/specs/phase-2-table-storage.md`에 작성 후 구현.
- `Schema`: 컬럼 정의 (이름, 타입, nullable)
- `Tuple`: 행 직렬화/역직렬화 + Null bitmap
- `HeapFile`: 순서 없는 레코드 저장 + 빈 공간 관리
- `Catalog`: 테이블/인덱스 메타데이터 영속 저장
- 지원 타입: `INT32`, `INT64`, `FLOAT64`, `VARCHAR(n)`, `BOOLEAN`, `TIMESTAMP`

### Phase 3: SQL Frontend

스펙 문서를 `docs/specs/phase-3-sql-frontend.md`에 작성 후 구현.
- `Lexer`: SQL → 토큰 스트림
- `Parser`: Recursive descent + Pratt parsing (표현식)
- `AST`: sealed class 기반 트리
- `Binder`: AST ↔ Catalog 대조 검증
- 최소 지원: CREATE TABLE, INSERT, SELECT (WHERE, ORDER BY, LIMIT), UPDATE, DELETE

### Phase 4: Query Execution Engine

- Volcano (Iterator) 모델: `open()`, `next(): Tuple?`, `close()`
- 연산자: SeqScan, IndexScan, Filter, Project, Sort (external merge sort), NestedLoopJoin, HashJoin
- Planner: AST → 연산자 트리
- **이 Phase 완료 = 동작하는 SQL DB**

### Phase 5: Crash Recovery (WAL)

- ARIES 프로토콜: Analysis → Redo → Undo
- LogRecord 타입: Begin, Commit, Abort, Update, CLR
- Fuzzy checkpoint
- **Crash test가 핵심**: 랜덤 시점 kill → 재시작 → 무결성 검증

### Phase 6: Concurrency Control

- 6a: 2PL (Two-Phase Locking) + Deadlock Detection
- 6b (선택): MVCC

### Phase 7: Query Optimizer

- 통계 기반 비용 모델
- Rule-based: predicate pushdown, projection pruning
- DP 기반 조인 순서 최적화
- EXPLAIN 구현

### Phase 8: Networking

- TCP 서버 (Kotlin Coroutine)
- 자체 와이어 프로토콜 또는 PostgreSQL wire protocol 호환
- CLI 클라이언트

## 문서 관리

- 각 Phase 시작 시 `docs/specs/phase-N-<name>.md`에 스펙 문서를 먼저 작성한다.
- 스펙 문서 템플릿:
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
