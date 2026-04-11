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
- 리프 노드: key-value 쌍 저장, 다음 리프로의 링크
- 내부 노드: key-childPageId 쌍 저장
- 삽입 시 노드 분할 (split), 삭제 시 머지/재분배 (Phase 1에서는 삭제 시 lazy 처리)
- order(차수)는 페이지 크기에서 자동 계산

## 제약 사항 및 트레이드오프

| 결정 | 선택 | 대안 | 이유 |
|---|---|---|---|
| 인덱스 | B+Tree | LSM-Tree | 읽기 성능 우선, 학습 목적 |
| Eviction | LRU | Clock, LRU-K | 구현 단순성, 추후 LRU-K로 교체 가능 |
| 삭제 | Lazy (마킹) | Immediate merge | Phase 1 범위 제한, Phase 2에서 개선 |
| 동시성 | 단일 스레드 | Fine-grained latch | Phase 6에서 추가 |

## 테스트 시나리오

1. **기본 CRUD**: put → get → delete → get(null)
2. **영속성**: put → close → reopen → get
3. **범위 스캔**: 100건 삽입 → scan(30, 60) → 정확히 해당 범위만 반환
4. **대량 삽입**: 10,000건 랜덤 삽입 → 전체 조회 일치
5. **Buffer Pool 압박**: poolSize=4로 10,000건 처리 → 정상 동작
6. **B+Tree 분할**: 노드 용량 초과 시 정상 분할 확인
7. **중복 키**: 같은 키로 put 두 번 → 마지막 값으로 갱신

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
