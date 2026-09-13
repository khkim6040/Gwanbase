# Playground 설계: 브라우저에서 Gwanbase에 쿼리 날려보기

## 목표

Gwanbase 엔진을 실제로 띄워 방문자가 브라우저에서 SQL을 실행해 볼 수 있는
웹 페이지를 Fly.io에 배포한다. 학습 프로젝트의 결과물을 링크 하나로 보여주는
것이 목적이며, 다중 방문자가 같은 DB를 공유해 잠금·트랜잭션 동작까지
체험할 수 있어야 한다.

## 접근 방식 선택

| 방식 | 판단 |
|---|---|
| **JDK 내장 `com.sun.net.httpserver.HttpServer` + 손으로 쓴 JSON + HTML 한 장** (채택) | 새 의존성 0개, 엔드포인트 4개에 충분 |
| Ktor | 새 의존성 5~6개, 시작 시간 증가. 규모 대비 과함 |
| 기존 `GwanServer`(PG 프로토콜) 앞에 PostgREST류 프록시 | 프록시가 Gwanbase가 지원하지 않는 시스템 카탈로그 쿼리를 보냄. 불가 |

브라우저에서 엔진을 직접 실행하는 것(Kotlin/JS, WASM)은 `FileChannel`·direct
`ByteBuffer` 의존 때문에 불가하다.

## 모듈 구조

새 Gradle 모듈 `playground/`. `bench/`와 같은 패턴으로 `core`에 의존하며,
`core`의 레이어 규칙(`server`가 최상위)을 침범하지 않는다.

```
playground/
├── build.gradle.kts          kotlin jvm + application 플러그인, implementation(project(":core"))
└── src/
(저장소 루트)
├── Dockerfile                multi-stage: ./gradlew :playground:installDist → eclipse-temurin:17-jre.
│                             빌드 컨텍스트에 core가 필요해 루트에 둔다
├── fly.toml                  internal_port 8080, 볼륨 없음(재시작 시 초기화 = 의도)

    ├── main/kotlin/gwanbase/playground/
    │   ├── Main.kt           환경변수(PORT, GWANBASE_DB) 읽고 Engine + PlaygroundServer 기동
    │   ├── Engine.kt         Database 보유·초기화(reset): 파일 삭제 → open → SampleData 적재
    │   ├── PlaygroundServer.kt   HttpServer, 라우팅 4개, reset용 RW 락
    │   ├── SessionRegistry.kt    쿠키 → DatabaseSession, idle TTL, 세션별 synchronized
    │   ├── Json.kt           ExecuteResult / 스키마 → JSON 문자열 (escape 포함)
    │   └── SampleData.kt     초기 스키마와 샘플 행
    ├── main/resources/index.html   vanilla JS 한 파일
    └── test/kotlin/gwanbase/playground/
```

`core` 변경은 둘이다: (1) `ConnectionHandler.sqlStateOf`를 `internal` → `public`
(다른 모듈에서 SQLSTATE 매핑 재사용), (2) `DatabaseSession.executeSql`이 호출마다
트랜잭션을 호출 스레드에 바인딩 (WalCallback이 ThreadLocal로 트랜잭션을 찾는데,
HTTP 스레드 풀에서는 한 세션이 요청마다 다른 스레드에서 실행되므로 필요하다.
GwanServer는 연결당 스레드 하나라 드러나지 않던 전제였다).

## 엔드포인트

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `GET /` | — | `index.html` |
| `POST /query` | 본문 = SQL 텍스트 그대로 (`text/plain`) | 성공: `{"kind", "columns", "rows", "count", "message", "truncated", "txn"}` / 실패: `{"error", "sqlState", "txn"}` |
| `GET /schema` | — | `{"tables": [{"name", "columns": [{"name","type","nullable"}], "indexes": [{"name","column","unique"}]}]}` |
| `POST /reset` | — | `{"ok": true}` |

- `kind`는 `ExecuteResult`의 서브타입명(`Selected`, `Inserted`, `Updated`, …).
  `columns`/`rows`는 `Selected`에만, `count`는 `Updated`/`Deleted`(영향 행 수)와
  `Selected`/`Explained`(자르기 전 전체 행 수)에, `message`는 그 외(예: `CREATE TABLE users`)에
  채운다. 없는 필드는 생략한다.
- `txn`은 `"I"`(idle) / `"T"`(트랜잭션 중) / `"E"`(트랜잭션 실패). PG 프로토콜의
  `ReadyForQuery` 상태와 같은 의미다.
- `/schema`는 `Catalog.listTables()`와 `getIndexesForTable()`로 만든다.
- `/reset`은 모든 세션을 close → `Database.close()` → DB 파일 삭제 → 재오픈 →
  `SampleData` 적재 순서로 수행한다.
- SQL 실행 실패는 400, 잘못된 경로는 404, 잘못된 메서드는 405, 본문 64KB 초과는 413.
- 요청 본문을 SQL 텍스트 그대로 받으므로 JSON 파서가 필요 없다. 인코더만 손으로 쓴다.

## 세션과 동시성

- 쿠키 `gb_session`(UUID)으로 방문자를 식별하고 `DatabaseSession` 하나를 대응시킨다.
  쿠키가 없거나 만료된 세션이면 새로 발급한다.
- `DatabaseSession`은 스레드 안전하지 않으므로 같은 세션의 동시 요청은 세션
  객체로 `synchronized`한다. 서로 다른 세션은 `GwanServer`와 마찬가지로 병렬로
  실행된다.
- 모든 세션에 `lockTimeoutMillis = 5000`을 준다. 다른 방문자의 미커밋 행에
  걸리면 무한 대기 대신 55P03을 받는다.
- 알려진 한계: core의 HeapFile/BPlusTree/Catalog에는 아직 래치가 없어 두 방문자의
  동시 DDL/DML이 페이지를 손상시킬 수 있다. GwanServer에 psql 두 개를 붙여도
  같다. 플레이그라운드 계층에서 전역 락으로 막지 않는 이유는, 한 문장이 행 잠금을
  5초 기다리는 동안 모든 방문자가 멈추고 잠금 대기 데모 자체가 불가능해지기
  때문이다. 손상 시 복구 경로는 "초기화"다. B+Tree 래치가 도입되면 이 한계는
  사라진다.
- idle 10분이 지난 세션은 백그라운드 스레드가 close한다. close는 미커밋
  트랜잭션을 abort하고 잠금을 풀므로, 브라우저를 닫고 떠난 방문자가 잠금을
  영원히 쥐는 일을 막는다. 이것이 공유 DB 모델에서 가장 중요한 안전장치다.
- `/reset`은 `ReentrantReadWriteLock`의 write 락, 나머지 요청은 read 락을 잡는다.
- 트랜잭션 상태(I/T/E)와 실패한 블록의 25P02 거부는 `DatabaseSession.txnStatus`가
  담당한다(`advanced.md` 22번). 세션 래퍼는 직렬화와 `lastUsedAt` 갱신만 한다.

## 안전 상한

- `Selected` 결과는 500행에서 자르고 `truncated: true`를 붙인다.
- 요청 본문은 64KB까지.
- 문장 실행 타임아웃은 두지 않는다. `executeSql`이 동기라 스레드를 중단할 수
  없어 흉내만 나고, 락 대기는 이미 5초로 제한된다. 필요해지면 그때 추가한다.

## 화면 (`index.html`)

프레임워크 없이 vanilla JS 한 파일.

- **좌측 사이드바**: `/schema` 결과(테이블 → 컬럼·인덱스), "초기화" 버튼(확인 후 `/reset`).
- **우측 상단**: SQL textarea, 실행 버튼(Ctrl/Cmd+Enter), 예제 버튼 9개 —
  SELECT, JOIN, EXPLAIN, INSERT, UPDATE, BEGIN, ROLLBACK, UNIQUE 위반, FK 위반.
  클릭하면 textarea에 채워진다.
- **우측 하단**: 실행 이력. 입력 SQL과 결과(테이블 또는 빨간 에러 + SQLSTATE)를
  위→아래로 누적하고, 각 항목에 `txn` 배지를 붙인다.
- DDL/DML/`/reset` 후 사이드바를 다시 불러온다.

## 샘플 데이터 (`SampleData`)

- `CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), age INT)` 5행
- `CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id), amount INT)` 8행
- `CREATE INDEX idx_orders_user ON orders (user_id)`
- 위 문법은 모두 현재 `Parser`가 지원한다 (`SqlExecutorTest` 참조).

## 테스트

`playground/src/test`에서 랜덤 포트로 서버를 띄우고 `java.net.http.HttpClient`로
호출한다. JSON 파서 의존성을 추가하지 않고 응답 문자열 포함 여부로 검증한다.

1. `SELECT`가 `columns`와 `rows`를 담아 반환한다.
2. 존재하지 않는 테이블 조회는 `sqlState` 42000(`BindException` 매핑)을 반환한다.
3. 쿠키 A에서 `BEGIN` + `INSERT` 후 쿠키 B에서 같은 행 `UPDATE` → 55P03.
4. `/reset` 후 `/schema`가 샘플 스키마로 돌아온다.
5. 500행을 넘는 `SELECT`는 `truncated: true`다.
6. 같은 쿠키로 `BEGIN` 후 응답 `txn`이 `"T"`, 에러 후 `"E"`, `ROLLBACK` 후 `"I"`.

`Json.kt`의 escape는 단위 테스트로 따로 검증한다.

## 배포

- `fly launch --no-deploy --copy-config --yes`로 앱을 만들고, fly가 재작성한 `fly.toml`을 커밋한다.
- CI 자동 배포: `fly launch`가 생성한 `.github/workflows/fly-deploy.yml`(`superfly/flyctl-actions`, `FLY_API_TOKEN` 시크릿)을 함께 포함한다. main에 push될 때마다 원격 빌더로 배포된다.
- README에 플레이그라운드 URL을 한 줄 추가한다.

## 범위 밖

인증, 방문자별 영속 DB, 요청 속도 제한, 문장 타임아웃, EXPLAIN 시각화,
다크모드. 방문자가 생기면 그때 판단한다.
