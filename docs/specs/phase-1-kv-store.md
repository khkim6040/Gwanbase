# Phase 1: Persistent Key-Value Store

## 목표

디스크 기반으로 `put(key, value)` / `get(key)` / `delete(key)` / `scan(range)`이
동작하는 영속적 Key-Value Store를 구현한다.

## 비기능 요구사항

- 프로세스 재시작 후 데이터 영속성 보장
- 1만 건 기준 랜덤 읽기 p99 < 1ms (Buffer Pool hit 시)
- Buffer Pool 크기가 작아도 정상 동작 (성능 저하는 허용)

## 인터페이스 설계

```kotlin
interface KVStore : AutoCloseable {
    fun put(key: ByteArray, value: ByteArray)
    fun get(key: ByteArray): ByteArray?
    fun delete(key: ByteArray): Boolean
    fun scan(startKey: ByteArray, endKey: ByteArray): Iterator<Pair<ByteArray, ByteArray>>
}
```

## 내부 설계

### 컴포넌트 의존 관계

```
KVStore
  └── B+Tree (index)
        └── BufferPoolManager
              ├── LruReplacer
              └── DiskManager
                    └── FileChannel (java.nio)
```

### DiskManager
- 고정 크기(4KB) 페이지 단위 I/O
- `FileChannel` 기반, `allocatePage()` / `readPage()` / `writePage()`
- 페이지 ID = 파일 내 오프셋 / PAGE_SIZE

### BufferPoolManager
- 고정 크기 프레임 풀 (기본 256 프레임 = 1MB)
- LRU eviction 정책
- pin/unpin으로 사용 추적, dirty flag로 쓰기 추적
- fetch 시 page table에서 먼저 확인 → miss면 디스크 read → victim evict

### SlottedPage
- 가변 길이 레코드를 한 페이지 내에 저장
- 슬롯 디렉토리가 앞에서부터, 레코드 데이터가 뒤에서부터 성장
- 삭제는 논리 삭제 (DELETED_MARKER), compaction은 추후 구현

### B+Tree

#### 키/값 타입과 비교

- Key/Value 모두 `ByteArray` (가변 길이). 페이지 내 저장은 length-prefixed.
- 키 비교는 **lexicographic unsigned byte order** (`Byte.toInt() and 0xFF`).
  부호 있는 비교를 피해야 0x80 이상의 바이트가 올바른 순서로 정렬된다.
- Phase 1에서는 편의상 `String` 키/값을 UTF-8로 인코딩하여 테스트한다.
  UTF-8의 byte-wise 정렬은 code point 정렬과 일치하므로 안전하다.

#### 노드 = 자체 정렬 슬롯 레이아웃

B+Tree 노드 1개는 페이지 1개(4KB)이며, **구조적으로 SlottedPage와 유사한
자체 레이아웃**을 사용한다. 핵심 차이는 **슬롯이 키 순으로 정렬**되어 있다는
점이다. 기존 `SlottedPage`는 `slotId == 삽입 순서`로 설계되어 있어
이진 탐색과 split을 지원할 수 없기 때문에 재사용하지 않는다.

> **설계 결정: SlottedPage 재사용 대신 B+Tree 전용 노드 레이아웃**
> SlottedPage의 슬롯 ID는 삽입 순서로 부여되어 키 순으로 슬롯을 나열할
> 수 없다. B+Tree는 `slots[i]`가 sorted order의 i번째 엔트리여야
> 이진 탐색(O(log n))과 split(슬롯 절반을 그대로 이동)이 가능하다.
> 따라서 B+Tree 노드는 자체 정렬 슬롯 디렉터리를 사용한다. 구조적
> 유사성 때문에 구현 아이디어는 SlottedPage에서 차용하지만, 슬롯
> 디렉터리 조작 규칙이 다르다. SQLite btree.c도 정렬 슬롯 배열을
> 유지하는 방식이다.

페이지 레이아웃:

```
offset  size  field
0       1     nodeType          (0 = internal, 1 = leaf)
1       1     (reserved/padding)
2       2     keyCount (n)
4       4     parentPageId      (root이면 INVALID = -1)
8       4     nextLeafPageId    (leaf 전용; internal이면 미사용 = -1)
12      4     leftmostChildPageId (internal 전용; leaf이면 미사용 = -1)
16      2     freeSpaceOffset   (record 영역의 가장 낮은 offset)
18      ...   slot directory   ((recordOffset:2, recordLen:2) × n, 키 순 정렬)
...
[...free space...]
[recordLen-1][...][record1][record0]   ← 페이지 끝에서부터 역방향
```

헤더 크기: 18바이트 고정.

슬롯 엔트리(레코드 페이로드) 포맷:

- **리프 노드**: `[keyLen: Short][key bytes][valueLen: Short][value bytes]`
- **내부 노드**: `[keyLen: Short][key bytes][childPageId: Int]`
  - 각 내부 노드 슬롯은 (키, 자식 포인터) 쌍이다.
  - **규약**: slot i의 `childPageId`는 "이 슬롯의 자식 서브트리가 담는 키의
    **왼쪽 경계(lower bound, inclusive)**가 key_i"를 의미한다. 즉 slot i의
    자식 서브트리는 key_i 이상, 그리고 다음 경계(`slot[i+1].key` 또는
    무한대)보다 작은 키를 담는다.
  - 가장 작은 키 영역(< key_0인 서브트리)은 슬롯이 아닌 헤더의
    `leftmostChildPageId` 필드에 저장한다. 이 구조의 핵심 장점은 자식이
    split될 때 **부모 업데이트가 단순한 `insertInternalEntry(promoteKey,
    newRightChild)` 한 번으로 끝난다**는 것이다 (기존 왼쪽 자식의 슬롯은
    수정 없이 그대로 두어도 올바르게 동작). 루트 split 시에도 새 내부
    루트의 leftmostChild에 왼쪽 자식, slot[0]에 (promoteKey, 오른쪽 자식)을
    넣으면 된다.

`findChild(key)` 알고리즘:
1. 슬롯에 대해 upper_bound(key)를 구한다: 가장 작은 i에서 `key_i > key`.
2. 그런 i가 0이면(모든 슬롯 키보다 key가 작음): `leftmostChildPageId` 반환.
3. 아니면 slot `i-1`의 `childPageId` 반환 (즉 `key_{i-1} ≤ key < key_i`).
4. i == n이면(key가 모든 슬롯 키보다 크거나 같음): 슬롯 `n-1`의
   `childPageId` 반환.

#### Order(차수)

페이지 크기(4KB)에서 B+Tree 전용 헤더와 SlottedPage 오버헤드를 뺀 실제 가용
공간을 기준으로 **동적으로 결정**한다. 고정 order를 강제하지 않는다.

- 삽입 시 새 엔트리가 페이지에 들어가지 못하면 split 발동.
- 따라서 "order = k"를 상수로 두지 않고, `SlottedPage.freeSpace()`로 판단.

> **설계 결정: 고정 order 대신 free-space 기반 split 판정**
> 고정 order는 키 크기가 균일할 때만 타당하다. 가변 길이 키에서는
> 실제 여유 공간으로 판정해야 페이지 낭비 없이 채울 수 있다.
> bustub(CMU 15-445)는 템플릿 기반 고정 order를 사용하지만,
> 이는 고정 크기 키 가정에 의존한다. SQLite는 free-space 기반이다.

#### 탐색(search)

```
fun search(key: ByteArray): ByteArray? {
    var pageId = rootPageId
    while (true) {
        val currentId = pageId
        val node = bpm.fetchPage(currentId).asBPlusTreeNode()
        try {
            if (node.isLeaf) return node.findValue(key)  // 리프 내 이진 탐색
            pageId = node.findChild(key)                 // 내부 노드 이진 탐색
        } finally {
            bpm.unpinPage(currentId, isDirty = false)    // 항상 fetch한 페이지를 unpin
        }
    }
}
```

- 내부/리프 노드 모두 **페이지 내 이진 탐색**으로 O(log n).
- 전체 복잡도 O(log N).

#### 삽입(insert)과 split

1. search와 같은 경로로 리프까지 내려간다. 경로상의 노드 스택을 저장한다.
2. 리프에 여유 공간이 있으면 정렬 위치에 삽입하고 끝.
3. 여유 공간이 없으면:
   - 새 리프 페이지 할당 → 기존 리프의 절반을 옮긴다 (바이트 수 기준 균등 분할).
   - `nextLeafPageId` 체인을 갱신한다.
   - 새 리프의 **첫 키**를 부모로 promote (B+Tree는 리프에만 실제 값이 있고,
     내부 노드의 키는 구분자 역할).
4. 부모 내부 노드도 가득 차 있으면 같은 방식으로 재귀적으로 split.
   - 내부 노드 split은 **middle key를 부모로 promote하며 중복 저장하지 않는다**
     (B+Tree 불변식).
5. 루트가 split되면 **새 루트 내부 노드를 할당**하고 두 자식을 등록한다.
   메타데이터 페이지의 `rootPageId`를 갱신한다.

> **왜 리프의 첫 키를 promote하는가?**
> B+Tree에서 내부 노드의 키는 "오른쪽 서브트리의 모든 키 ≥ 이 키"를
> 보장하는 구분자다. 리프 split 후 오른쪽 리프의 최소 키가 이 성질을
> 자연스럽게 만족한다.

#### 삭제(delete) — lazy

Phase 1에서는 리프 슬롯만 논리 삭제(`DELETED_MARKER = -1`)로 표시하고,
underflow/merge/rebalance는 구현하지 않는다.

- 장점: 구현 단순, 트리 구조 무너질 여지 없음.
- 단점: 삭제된 슬롯이 공간을 점유하므로 delete-heavy 워크로드에서 split이
  조기에 발생할 수 있음. 학습 목적으로 수용.
- Phase 5(Crash Recovery) 이후 혹은 별도 하우스키핑 작업에서 개선 예정.

#### 범위 스캔(scan)

1. `startKey`로 리프까지 내려가서 해당 슬롯 위치를 찾는다.
2. 리프 내 정렬 순서로 순회 → 리프 끝에 도달하면 `nextLeafPageId`로 이동.
3. `endKey`를 초과하는 첫 키에서 중단.
4. `Iterator<Pair<ByteArray, ByteArray>>`로 lazy 반환. 각 단계에서
   fetch/unpin을 정확히 짝맞춘다.

#### 메타데이터 페이지

- `pageId = 0` 고정.
- 레이아웃:

  ```
  offset  size  field
  0       4     magicNumber  (0x47574E42 = "GWNB")
  4       2     version      (현재 1)
  6       4     rootPageId
  10      4     firstLeafPageId
  ```

- `KVStore.open()` 시 페이지 0을 읽어 rootPageId를 복원한다. 파일이 비어
  있으면 빈 리프 루트를 새로 만들고 메타데이터 페이지를 초기화한다.
- rootPageId가 변할 때마다 메타데이터 페이지를 갱신하고 flush한다. Phase 5
  WAL 도입 이전까지는 메타데이터 쓰기가 **atomic하다고 가정**한다 (crash 시
  일관성 보장 없음 — 이것이 WAL이 필요한 이유).

#### BufferPoolManager 연동 규약

모든 노드 접근은 다음 패턴을 **반드시** 따른다:

```kotlin
val page = bpm.fetchPage(pageId) ?: error("page not found: $pageId")
try {
    // 노드 읽기/수정
    if (modified) page.dirty = true
} finally {
    bpm.unpinPage(pageId, isDirty = page.dirty)
}
```

- 어떤 경로에서도 `unpinPage`가 누락되면 버퍼 풀에 좀비 페이지가 남아
  전체 시스템이 교착된다. 테스트에서 pinCount leak을 검증한다.
- split 중 새 페이지 할당은 `bpm.newPage()` 사용. 새 페이지도 채운 후
  반드시 unpin한다.
- 재귀 split은 상위 노드의 pin을 유지한 채 진행한다(경로 스택).

#### 동시성

Phase 1은 **단일 스레드 가정**. `BufferPoolManager`는 이미 `@Synchronized`로
보호되지만, B+Tree 자체 레벨의 latch coupling / crabbing은 Phase 6(Concurrency
Control)에서 추가한다.

## 제약 사항 및 트레이드오프

| 결정 | 선택 | 대안 | 이유 |
|---|---|---|---|
| 인덱스 | B+Tree | LSM-Tree | 읽기 성능 우선, 학습 목적 |
| Eviction | LRU | Clock, LRU-K | 구현 단순성, 추후 LRU-K로 교체 가능 |
| 삭제 | Lazy (마킹) | Immediate merge/rebalance | Phase 1 범위 제한, 추후 개선 |
| 동시성 | 단일 스레드 | Fine-grained latch (crabbing) | Phase 6에서 추가 |
| B+Tree 노드 레이아웃 | 정렬 슬롯 전용 레이아웃 | SlottedPage 재활용 | SlottedPage 슬롯은 삽입 순서라 이진 탐색 불가 |
| B+Tree order | Free-space 기반 | 고정 order | 가변 길이 키에서 공간 효율 |
| 키 비교 | Unsigned lexicographic | Signed byte 비교 | 0x80 이상 바이트 정렬 정확성 |
| 루트 관리 | 메타데이터 페이지(pageId=0) | 별도 파일 | 단일 파일 유지, 원자성은 WAL 이후 |
| 메타데이터 원자성 | 미보장 (Phase 5까지) | fsync+double-write | WAL 도입 전까지 crash 일관성 포기 |

## 테스트 시나리오

### 스토리지 레이어 (구현 완료)

기존 `DiskManagerTest`, `BufferPoolManagerTest`, `SlottedPageTest`,
`LruReplacerTest`, `ByteBufferExtensionsTest`에서 커버. 여기서는 생략.

### B+Tree 단위 테스트 (`BPlusTreeNodeTest`, `BPlusTreeTest`)

**노드 레벨 (`BPlusTreeNode`)**
1. 빈 리프 생성 → `keyCount == 0`, `findValue(any) == null`
2. 리프에 한 건 삽입 → `findValue` 일치
3. 리프에 정렬 순서대로 여러 건 삽입 → `findValue` 모두 일치, 슬롯 순서 오름차순
4. 리프에 역순 삽입 → 내부 정렬 유지 확인
5. 리프가 가득 찰 때까지 삽입 → `hasSpaceFor(entry)`가 false 반환
6. 내부 노드: child 포인터 + 구분 키 저장/조회 일치
7. 내부 노드의 `findChild(key)`가 이진 탐색으로 올바른 자식 반환 (경계값 포함)

**트리 레벨 (`BPlusTree`)**
1. 빈 트리 `search(anyKey)` → null
2. 단일 삽입 → `search` 일치
3. 순차 1~1000 삽입 → 전체 `search` 일치, 최소 1회 이상 리프 split 발생
4. 역순 1000~1 삽입 → 전체 `search` 일치
5. 랜덤 10,000건 삽입 → 전체 `search` 일치
6. 중복 키 put 두 번 → 마지막 값으로 갱신, `keyCount` 불변
7. 존재하지 않는 키 `search` → null
8. **리프 split 경계**: 리프 용량 직전/직후 삽입에서 split 발동, 양쪽 리프
   키 분포가 거의 균등(바이트 기준)
9. **내부 노드 split**: 리프 split이 전파되어 내부 노드도 split → 트리 높이
   증가 확인
10. **루트 split**: 루트 split 후 새 루트 pageId가 메타데이터 페이지에 반영
11. **범위 스캔**: 100건 삽입 후 `scan(30, 60)` → 정확히 31개 반환, 정렬 순서
12. **전체 리프 순회**: 가장 작은 키부터 `nextLeafPageId` 따라가면 전체 키가
    오름차순으로 나오는지
13. **삭제(lazy)**: put → delete → `search` → null, 같은 키 재삽입 가능
14. **pinCount leak**: 임의의 연산 후 모든 페이지의 `pinCount == 0`

**B+Tree 불변식 (Kotest property-based)**
- 랜덤 연산 시퀀스 후 다음 불변식이 모두 유지:
  - 모든 리프의 깊이가 동일
  - 각 내부 노드 키 `k_i`에 대해: 왼쪽 서브트리의 모든 키 ≤ `k_i` ≤ 오른쪽
    서브트리의 모든 키
  - 리프 체인 순회 시 키가 오름차순
  - 루트가 아닌 모든 노드에 최소 1개 이상 엔트리 존재
  - 메타데이터 페이지의 `rootPageId`는 실제 루트와 일치

### KVStore 통합 테스트 (`KVStoreTest`)

1. **기본 CRUD**: put → get → delete → get(null)
2. **영속성**: put → close → reopen → get (rootPageId 복구 포함)
3. **범위 스캔**: 100건 삽입 → `scan(30, 60)` → 정확히 해당 범위만 반환
4. **대량 삽입**: 10,000건 랜덤 삽입 → 전체 조회 일치
5. **Buffer Pool 압박**: `poolSize=4`로 10,000건 처리 → 정상 동작
   (split 중 dirty evict 경로 커버)
6. **B+Tree 분할**: 노드 용량 초과 시 정상 분할, 모든 키 조회 가능
7. **중복 키**: 같은 키로 put 두 번 → 마지막 값으로 갱신
8. **빈 파일 open**: 새 파일 → put → get → close → reopen → get
9. **타입 편의 오버로드**: `String` 키/값 UTF-8 인코딩 경로 동작 확인
10. **AutoCloseable**: try-with-resources로 close 호출 → 이후 연산이 실패
    (IllegalStateException 등 명시적 에러)

### 테스트 전략 메모

- 모든 테스트는 `@TempDir`을 사용하여 파일 격리를 보장한다.
- property-based 테스트의 시드(seed)를 로깅하여 실패 재현 가능하게 한다.
- "pinCount leak" 검증은 테스트 후 `BufferPoolManager`에 진단용 메서드
  (예: `allPinCountsZero()`)를 추가해 공통 assertion으로 수행한다.

## 벤치마크 목표

| 항목 | 메트릭 | 목표 |
|---|---|---|
| 순차 삽입 | ops/sec | 측정 (기준선) |
| 랜덤 읽기 | p50, p99 latency | 측정 (기준선) |
| 범위 스캔 | throughput (rows/sec) | 측정 (기준선) |
| Buffer Pool | hit ratio | > 90% (반복 접근 패턴) |

> Phase 1은 성능 목표보다 **정확성**이 우선이다. 벤치마크는 기준선(baseline)을 잡는 것이 목적.

## 참고 자료

- *Database Internals* Ch.1–7
- CMU 15-445 Project 1 (Buffer Pool Manager)
- CMU 15-445 Project 2 (B+Tree Index)
- SQLite btree.c 소스 코드
