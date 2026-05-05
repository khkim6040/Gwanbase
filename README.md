# Gwanbase

Kotlin으로 관계형 데이터베이스를 밑바닥부터 만들어보는 프로젝트.
디스크 I/O부터 SQL 파서, 트랜잭션, 쿼리 옵티마이저, PostgreSQL 와이어 프로토콜까지 직접 구현했다.

## 현재 상태

8개 Phase를 모두 완료했다. `psql`이나 JDBC 드라이버로 접속해서 SQL을 실행할 수 있다.

| Phase | 내용 | 태그 |
|---|---|---|
| 0 | 프로젝트 세팅 | — |
| 1 | B+Tree 기반 Key-Value Store | `v0.1-kvstore` |
| 2 | 테이블 스토리지 (HeapFile, Catalog) | `v0.2-table` |
| 3 | SQL 파서 (Lexer, Parser, Binder) | `v0.3-sql` |
| 4 | Volcano 모델 쿼리 실행 엔진 | `v0.4-execution` |
| 5 | WAL 기반 크래시 복구 | `v0.5-wal` |
| 6 | Strict 2PL 동시성 제어 | `v0.6-txn` |
| 7 | 쿼리 옵티마이저 (통계 + 조인 순서) | `v0.7-optimizer` |
| 8 | PostgreSQL Wire Protocol | `v0.8-networking` |

## 구조

```
core/src/main/kotlin/gwanbase/
├── storage/     # 디스크 I/O, 버퍼 풀, SlottedPage
├── index/       # B+Tree
├── kv/          # KVStore
├── table/       # Schema, Tuple, HeapFile, Catalog
├── sql/         # Lexer, Parser, Binder, SqlExecutor
├── execution/   # Volcano 연산자, Planner
├── wal/         # WAL, Recovery
├── txn/         # LockManager, DatabaseSession
├── optimizer/   # PlanNode, CostEstimator, PlanEnumerator
└── server/      # GwanServer, PostgreSQL Wire Protocol
```

모듈 의존 방향: `server → txn → execution → sql → table → index → storage`

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 테스트
./gradlew :core:test

# 벤치마크
./gradlew bench:jmh
```

## 기술 스택

- Kotlin 1.9 / JVM 17
- Gradle Kotlin DSL (멀티모듈: core, bench)
- JUnit 5 + Kotest assertions + property-based testing
- JMH 벤치마크
- Netty Buffer (ByteBuffer 유틸리티)

## 설계 문서

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — 전체 아키텍처
- [docs/specs/](docs/specs/) — Phase별 스펙, 설계 결정, 트레이드오프

## 참고 자료

- *Database Internals* — Alex Petrov
- *Architecture of a Database System* — Hellerstein et al.
- CMU 15-445 — Andy Pavlo
- *Crafting Interpreters* — Robert Nystrom
