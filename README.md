# my-db

**Kotlin으로 만드는 데이터베이스 엔진** — DB Internals 학습 및 스펙 관리용 프로젝트

## 목표

디스크 기반 관계형 데이터베이스를 밑바닥부터 구현하며 내부 동작 원리를 학습한다.
각 Phase는 독립적으로 동작하는 데이터베이스이며, 이전 Phase 위에 기능을 확장한다.

## Roadmap

| Phase | 기능 | 상태 |
|---|---|---|
| 0 | Project Scaffolding | ✅ |
| 1 | Persistent Key-Value Store | 🔄 In Progress |
| 2 | Table Storage Engine | ⬜ |
| 3 | SQL Frontend (Parser) | ⬜ |
| 4 | Query Execution Engine | ⬜ |
| 5 | Crash Recovery (WAL) | ⬜ |
| 6 | Concurrency Control | ⬜ |
| 7 | Query Optimizer | ⬜ |
| 8 | Networking & Client Protocol | ⬜ |

> Phase 4 완료 시 SQL 문자열을 넣으면 결과가 나오는 완전한 DB가 동작한다.

## Tech Stack

- **Language**: Kotlin 1.9+ (JVM 21)
- **Build**: Gradle Kotlin DSL
- **Test**: JUnit 5 + Kotest assertions
- **Benchmark**: JMH

## Quick Start

```bash
# 빌드
./gradlew build

# 테스트
./gradlew test

# 벤치마크
./gradlew bench:jmh
```

## Architecture

자세한 설계 문서는 [ARCHITECTURE.md](docs/ARCHITECTURE.md) 참고.
각 Phase의 스펙 문서는 [docs/specs/](docs/specs/) 디렉토리에 있다.

## 참고 자료

- *Database Internals* (Alex Petrov)
- *Architecture of a Database System* (Hellerstein et al.)
- CMU 15-445: Database Systems (Andy Pavlo)
- *Crafting Interpreters* (Robert Nystrom)
