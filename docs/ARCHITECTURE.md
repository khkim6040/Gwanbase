# Architecture

## 전체 구조

```
┌─────────────────────────────────────────────┐
│                SQL Client                    │  Phase 8
├─────────────────────────────────────────────┤
│              SQL Parser / Binder             │  Phase 3
├─────────────────────────────────────────────┤
│         Query Optimizer (Cost-based)         │  Phase 7
├─────────────────────────────────────────────┤
│       Execution Engine (Volcano Model)       │  Phase 4
├─────────────────────────────────────────────┤
│  Transaction Manager │ Lock Manager / MVCC   │  Phase 5, 6
├─────────────────────────────────────────────┤
│    Table Storage     │   Index (B+Tree)      │  Phase 2
├─────────────────────────────────────────────┤
│             Buffer Pool Manager              │  Phase 1
├─────────────────────────────────────────────┤
│               Disk Manager                   │  Phase 1
└─────────────────────────────────────────────┘
```

## 모듈 구조

```
core/
├── storage/     Phase 1 ✅ DiskManager, BufferPool, SlottedPage
├── index/       Phase 1 ✅ B+Tree (BPlusTreeNode, BPlusTree)
├── kv/          Phase 1 ✅ KVStore (public Key-Value API)
├── table/       Phase 2 ⬜ Schema, Tuple, Catalog, HeapFile
├── sql/         Phase 3 ⬜ Lexer, Parser, AST, Binder
├── execution/   Phase 4 ⬜ Operators (Scan, Filter, Join, Sort...)
├── txn/         Phase 5,6 ⬜ WAL, Recovery, LockManager
├── optimizer/   Phase 7 ⬜ Statistics, CostModel, RuleOptimizer
└── server/      Phase 8 ⬜ TCP Server, Wire Protocol
```

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
- **메타데이터 원자성 미보장** — WAL은 Phase 5에서 추가 예정

## 설계 원칙

1. **Page-oriented**: 모든 데이터는 고정 크기(4KB) 페이지 단위로 관리
2. **Buffer Pool 중심**: 디스크 접근은 반드시 Buffer Pool을 통해서만 수행
3. **Volcano Model**: 쿼리 실행은 iterator 기반 pull model
4. **WAL first**: 데이터 변경 전에 로그를 먼저 디스크에 기록 (Phase 5~)

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
