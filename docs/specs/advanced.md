# Gwanbase 고도화 로드맵

각 Phase MVP 이후 PostgreSQL internals를 참고하여 단계적으로 고도화할 항목을
정리한다. 각 항목은 독립적으로 추가 가능하며, 영역별로 분류했다. 완료된 항목은 ✅로 표시한다.

---

## Query Optimizer 고도화

### 1. 조인 알고리즘 확장

#### Hash Join
- 등가 조인에서 대량 데이터에 효과적. 빌드(build) 단계에서 작은 테이블을
  해시 테이블로 구성, 프로브(probe) 단계에서 큰 테이블을 스캔.
- PostgreSQL: `src/backend/executor/nodeHashjoin.c`
- 학습 포인트: 메모리 제한 시 Grace Hash Join (파티션 후 디스크 스필)

#### Sort-Merge Join
- 양쪽 입력이 조인 키로 정렬되어 있거나, 인덱스가 정렬 순서를 제공할 때 유리.
- 이미 SortOperator가 있으므로 재활용 가능.
- PostgreSQL: `src/backend/executor/nodeMergejoin.c`
- 학습 포인트: 정렬 비용과 merge 비용의 트레이드오프

#### 옵티마이저 통합
- CostEstimator에 Hash Join / Merge Join 비용 모델 추가
- PlanEnumerator가 세 알고리즘 중 최적을 선택

### 2. JOIN 종류 확장

#### LEFT OUTER JOIN
- NULL 패딩 처리 학습. 매칭 없는 outer 행에 inner 측 NULL 채움.
- 옵티마이저 제약: LEFT JOIN은 조인 순서 교환에 제한 (outer를 바꿀 수 없음)

#### RIGHT / FULL OUTER JOIN
- LEFT JOIN 구현 후 자연스럽게 확장 가능

#### CROSS JOIN
- 카테시안 곱. `FROM t1, t2 WHERE ...` 암시적 조인 구문도 지원

#### SEMI / ANTI JOIN
- EXISTS, NOT EXISTS 서브쿼리 최적화에 필요
- PostgreSQL: semi join은 첫 매칭 발견 시 즉시 종료 (성능 이점)

### 3. Cost-Based Optimization (CBO)

#### 히스토그램 기반 선택도 추정
- MVP의 균등 분포 가정(1/distinctCount) 대신, 값 분포 히스토그램 사용
- PostgreSQL: `pg_statistic.stavalues`, `stanumbers` — MCV(Most Common Values) +
  등폭/등깊이 히스토그램
- 학습 포인트: 데이터 스큐(skew)가 쿼리 성능에 미치는 영향

#### 동적 프로그래밍(DP) 기반 계획 열거
- MVP의 greedy 방식 대신, 모든 조인 순서 부분 집합을 DP로 열거
- PostgreSQL: `src/backend/optimizer/path/joinrels.c` — System R 스타일 bottom-up DP
- 테이블 수가 많아지면(12+) GEQO(유전 알고리즘)로 전환
- 학습 포인트: 최적 부분 구조, 계획 공간 폭발

#### 상관 서브쿼리 최적화
- 서브쿼리를 조인으로 변환 (de-correlation)
- PostgreSQL: `pull_up_subqueries()` — 서브쿼리를 가능한 한 조인으로 풀어냄

### 4. 인덱스 고도화

#### 범위 스캔 (Range Scan)
- `WHERE age > 20 AND age < 30` → B+Tree의 `scan(startKey, endKey)` 활용
- MVP에서는 등가 조건만 인덱스 스캔 대상

#### 복합 인덱스 (Composite Index)
- `CREATE INDEX idx ON t (col1, col2)` — 다중 컬럼 키 직렬화
- Prefix 매칭: `WHERE col1 = ? AND col2 = ?` (full), `WHERE col1 = ?` (prefix)
- 학습 포인트: 컬럼 순서가 인덱스 활용도에 미치는 영향

#### Index-Only Scan
- 인덱스만으로 결과를 반환 (heap 접근 불필요)
- PostgreSQL: visibility map 확인 후 heap 접근 스킵
- 학습 포인트: covering index 개념

#### Bitmap Index Scan
- 여러 인덱스 조건을 비트맵으로 결합 후 한꺼번에 heap 접근
- PostgreSQL: `BitmapAnd`, `BitmapOr` 노드
- 학습 포인트: 랜덤 I/O를 순차 I/O로 변환

### 5. 통계 자동화

#### autovacuum 스타일 자동 ANALYZE
- DML 누적 변경량이 임계치를 넘으면 자동으로 ANALYZE 실행
- PostgreSQL: `autovacuum_analyze_threshold` + `autovacuum_analyze_scale_factor`

#### 확장된 통계
- 다중 컬럼 상관관계 통계 (PostgreSQL 10+: `CREATE STATISTICS`)
- 함수 종속성(functional dependency) 통계

### 6. 추가 SQL 기능

#### GROUP BY / 집계 함수
- HashAggregate, SortAggregate 연산자
- COUNT, SUM, AVG, MIN, MAX
- HAVING 절

#### 서브쿼리
- 스칼라 서브쿼리, EXISTS, IN
- 상관/비상관 서브쿼리

#### Window 함수
- ROW_NUMBER, RANK, DENSE_RANK
- OVER (PARTITION BY ... ORDER BY ...)

---

## Networking & Client Protocol 고도화

### 7. Extended Query 프로토콜 (Prepared Statement)

Simple Query는 매번 SQL 텍스트를 파싱하고 계획을 생성한다. Extended Query는
Parse → Bind → Describe → Execute → Sync 단계로 분리하여 계획을 재사용한다.

- 새 메시지: Parse(F), ParseComplete(B), Bind(F), BindComplete(B),
  Describe(F), ParameterDescription(B), Execute(F), Sync(F), Close(F),
  CloseComplete(B), NoData(B)
- 파라미터 바인딩 (`$1`, `$2`) 지원 필요 → Parser/Binder 확장
- PostgreSQL: `src/backend/tcop/postgres.c` — `exec_parse_message()`, `exec_bind_message()`
- 학습 포인트: 계획 캐싱, 파라미터 타입 추론, named/unnamed portal 구분

#### JDBC 호환성 임팩트

JDBC 드라이버는 `preferQueryMode=simple`이 아니면 기본적으로 Extended Query를
사용한다. Prepared Statement, batch insert, `setAutoCommit(false)` 등 고급 기능은
Extended Query 없이 제한적이다. 구현하면 JDBC 호환성이 크게 향상된다.

### 8. 바이너리 포맷 전송

텍스트 포맷은 모든 값을 문자열로 변환하므로 오버헤드가 있다. 바이너리 포맷은
PostgreSQL 내부 표현을 그대로 전송한다.

- RowDescription의 `formatCode`를 1 (binary)로 설정
- DataRow에서 각 값을 바이너리 인코딩 (INT → 4바이트 빅엔디안 등)
- Extended Query의 Bind 메시지에서 포맷 코드를 지정
- 학습 포인트: 타입별 바이너리 인코딩/디코딩, 네트워크 바이트 오더

### 9. COPY 프로토콜

대량 데이터 로딩/추출용 프로토콜. `COPY table FROM STDIN` / `COPY table TO STDOUT`.

- 새 메시지: CopyInResponse(B), CopyOutResponse(B), CopyData(F/B),
  CopyDone(F/B), CopyFail(F)
- CSV/텍스트/바이너리 포맷 선택
- PostgreSQL: `src/backend/commands/copy.c`
- 학습 포인트: 스트리밍 데이터 파이프라인, 벌크 insert 최적화

### 10. 쿼리 취소 (Cancel Request)

장시간 실행되는 쿼리를 클라이언트에서 취소하는 메커니즘.

- BackendKeyData로 전송한 (pid, secret)을 별도 TCP 연결로 재전송
- CancelRequest(길이=16, 코드=80877102, pid, secret) 수신 시 대상 세션에 인터럽트
- PostgreSQL: `src/backend/tcop/postgres.c` — `ProcessInterrupts()`
- 학습 포인트: 스레드 인터럽트, 실행 중인 연산자 취소, 동시성

### 11. 인증 메커니즘

#### MD5 Password
- `AuthenticationMD5Password` 메시지로 4바이트 salt 전송
- 클라이언트가 `md5(md5(password + user) + salt)` 응답
- PostgreSQL: `src/backend/libpq/auth.c` — `sendAuthRequest()`

#### SCRAM-SHA-256
- PostgreSQL 10+ 기본 인증. SASL 프레임워크 위에 동작.
- `AuthenticationSASL`, `AuthenticationSASLContinue`, `AuthenticationSASLFinal` 메시지
- 학습 포인트: SASL 메커니즘, 채널 바인딩, nonce 교환

### 12. SSL/TLS 지원

StartupMessage 전에 SSLRequest를 수신하면 TLS 핸드셰이크로 전환한다.

- MVP에서 'N' (거부)을 반환하고 있으므로, 'S' (수락) 후 `SSLSocket`으로 업그레이드
- Java의 `SSLContext` + 자체 서명 인증서로 시작
- PostgreSQL: `src/backend/libpq/be-secure-openssl.c`
- 학습 포인트: TLS 핸드셰이크, 인증서 체인, SNI

### 13. NOTIFY / LISTEN

pub/sub 스타일의 비동기 알림. 트리거 기반 실시간 이벤트에 활용.

- `LISTEN channel` → 서버가 해당 채널 구독 등록
- `NOTIFY channel, 'payload'` → 같은 DB의 모든 리스너에게 NotificationResponse 전송
- 새 메시지: NotificationResponse(B) — pid, channel, payload
- PostgreSQL: `src/backend/commands/async.c`
- 학습 포인트: 비동기 메시지 전달, 연결 간 통신

### 14. 연결 풀링

Thread-per-connection 모델의 한계를 보완한다.

#### 내부 연결 풀
- FixedThreadPool + 최대 연결 수 제한
- 대기 큐 + 타임아웃
- 학습 포인트: 스레드 풀 사이징, 큐잉 전략

#### 외부 연결 풀 호환
- PgBouncer 같은 외부 풀러 뒤에서 동작 확인
- Transaction pooling 모드: 트랜잭션 단위로 연결 재사용
- 학습 포인트: 세션 상태 리셋, `DISCARD ALL` 지원

### 15. NIO / Event Loop 모델

Thread-per-connection에서 비동기 I/O 모델로 전환.

- 이미 Netty 의존성이 있으므로 `io.netty` 활용 가능
- `ChannelPipeline`에 PG 메시지 코덱 배치
- EventLoopGroup으로 소수 스레드가 다수 연결 처리
- 학습 포인트: Reactor 패턴, backpressure, non-blocking I/O
- 주의: `BufferPoolManager`가 `@Synchronized`이므로 I/O 스레드에서 블로킹 발생 →
  별도 워커 풀로 분리 필요

### 16. 프로토콜 확장 메시지

#### ParameterDescription
- Prepared Statement의 파라미터 타입을 클라이언트에 알려준다.
- Extended Query 구현 시 함께 필요.

#### NoticeResponse
- 에러가 아닌 경고/정보 메시지 (severity: WARNING, NOTICE, INFO).
- `ErrorResponse`와 동일한 포맷이지만 세션을 중단하지 않는다.

---

## Constraints & Error Semantics 고도화

실제 애플리케이션이 DB에서 마주치는 에러(중복 키, 데드락, 락 타임아웃 등)를
Gwanbase에서 재현하는 것이 목표다. 에러는 PostgreSQL SQLSTATE 코드로 클라이언트에
전달되어 JDBC의 `SQLException.getSQLState()`로 분기할 수 있어야 한다.

### 17. SQLSTATE 매핑 ✅

코어 예외 타입을 `ConnectionHandler.sqlStateOf()`에서 SQLSTATE로 변환한다.
새 에러를 추가할 때는 코어에 예외 타입을 정의하고 이 표에 한 줄을 더한다.

| 예외 | SQLSTATE | 의미 |
|------|----------|------|
| `ParseException` | 42601 | syntax_error |
| `BindException` | 42000 | syntax_error_or_access_rule_violation (세분화 전 임시) |
| `DeadlockException` | 40P01 | deadlock_detected |
| `DataException` | 필드값 (22xxx) | data_exception — 코드를 필드로 보유 |
| `LockTimeoutException` | 55P03 | lock_not_available |
| 그 외 | XX000 | internal_error |
| (트랜잭션 실패 상태) | 25P02 | in_failed_sql_transaction — 기존 구현 |

- 후속: `BindException`을 undefined_table(42P01), undefined_column(42703),
  duplicate_table(42P07), not_null_violation(23502) 등으로 세분화.
  현재 Binder가 NOT NULL 검사까지 담당하므로 제약 위반과 문법 오류가 한 타입에 섞여 있다.
- PostgreSQL: `src/backend/utils/errcodes.txt`, `ereport(ERROR, errcode(...))`

### 18. 데이터 예외 (Class 22) ✅

| SQLSTATE | 에러 | 재현 | 검사 위치 |
|----------|------|------|-----------|
| 22001 | string_data_right_truncation | `VARCHAR(n)` 길이 초과 INSERT/UPDATE | `SqlExecutor.coerceValue` |
| 22012 | division_by_zero | `SELECT x / 0` (정수·실수 모두) | `ExpressionEvaluator.numericOp` |
| 22003 | numeric_value_out_of_range | INT64 산술 오버플로, INT32 범위 초과 저장 | `numericOp` (`Math.*Exact`), `coerceValue` |

- `DataException(message, sqlState)` 단일 타입에 코드를 실어 보낸다.
  PostgreSQL이 `ereport(ERROR, errcode(...))`로 코드를 값으로 다루는 방식과 같다.
- `VARCHAR(n)`의 n은 PostgreSQL과 동일하게 **문자 수** 기준이다 (바이트 아님).
- 실수 `/ 0`은 IEEE Infinity 대신 에러 — PostgreSQL float8 동작과 일치.

### 19. 락 타임아웃 (55P03) ✅

- `LockManager.acquire(..., timeoutMillis)` — `latch.await(timeout)` 초과 시
  대기열에서 제거하고 `LockTimeoutException` → 55P03. 0이면 무한 대기.
- `DatabaseSession.lockTimeoutMillis` (기본 0) — PostgreSQL `lock_timeout` GUC에 해당.
  세션 객체 프로퍼티로만 설정 가능하며 `SET lock_timeout` SQL은 미지원 (후속).
- 타임아웃 직전에 잠금이 부여되는 경합은 `latch.count == 0` 재확인으로 처리한다.
- 대기자를 제거한 뒤 `grantWaiters()`를 재실행한다 — FIFO 큐 점프 방지 규칙 때문에
  앞 대기자가 사라지면 뒤 대기자가 호환될 수 있다. 데드락 victim 제거도 같은 경로를 쓴다.
- PostgreSQL: `lock_timeout` GUC(기본 0=무한), `SELECT ... FOR UPDATE NOWAIT`
- MySQL: `innodb_lock_wait_timeout` (기본 50초), 에러 1205

### 20. UNIQUE / PRIMARY KEY (23505) ✅

**PostgreSQL 방식**

- UNIQUE / PRIMARY KEY 제약은 **유일 B-Tree 인덱스**로 구현된다. 제약을 선언하면
  `{table}_pkey`, `{table}_{column}_key` 인덱스가 자동 생성되고, 카탈로그에는
  `pg_index.indisunique` / `indisprimary` 플래그와 `pg_constraint` 행이 남는다.
- 검사 시점은 **힙 삽입 후 인덱스 삽입 시점**이다. `heap_insert()` →
  `ExecInsertIndexTuples()` → `_bt_doinsert()` → `_bt_check_unique()`. 위반 시
  `ERRCODE_UNIQUE_VIOLATION`(23505)으로 트랜잭션이 abort되고, 이미 쓰인 힙 튜플은
  dead 버전으로 남아 VACUUM이 정리한다.
- 동시 삽입: `_bt_check_unique()`가 같은 키의 튜플을 찾으면 그 튜플의 삽입 트랜잭션이
  진행 중인지 확인하고, 진행 중이면 그 xid를 반환해 `_bt_doinsert()`가
  `XactLockTableWait()`로 **상대 트랜잭션의 종료를 기다린 뒤 재검사**한다. 상대가
  abort하면 삽입이 성공하고, commit하면 23505다. 원자성과 대기는 모두 인덱스 AM 안에서
  처리된다.
- NULL은 서로 다른 값으로 취급한다 (`NULLS DISTINCT` 기본, PostgreSQL 15부터
  `NULLS NOT DISTINCT` 선택 가능).
- `CREATE UNIQUE INDEX`로 기존 데이터를 빌드할 때는 정렬 후 인접 중복을 검사한다
  (`nbtsort.c`).

**Gwanbase 구현**

| 항목 | 구현 | PostgreSQL과의 차이 |
|------|------|---------------------|
| 제약의 실체 | `IndexInfo.unique` + B+Tree (`Catalog.kt`) | `indisprimary`, `pg_constraint`는 생략. FK 작업 시 필요해지면 추가 |
| 파서 | `ColumnDef.unique / primaryKey`, `CreateIndex.unique`. 컬럼 제약은 순서 무관 반복 | 테이블 수준 제약(`PRIMARY KEY (a, b)`)은 복합 인덱스 이후로 미룸 |
| 인덱스 명명 | `{table}_pkey`, `{table}_{column}_key` (`SqlExecutor.executeCreateTable`) | 동일 |
| 검사 시점 | **힙 변경 전** `Database.checkUniqueConstraints()` | **의도적 차이.** MVCC/VACUUM이 없어 실패한 힙 튜플을 dead 버전으로 남길 수 없다. 검사를 선행하면 힙·다른 인덱스에 반쯤 쓰인 상태가 남지 않아 undo도 불필요 |
| 검사 방법 | `tree.scan(columnKey, equalityScanEnd)`로 접두사 범위 조회, UPDATE는 자기 RID 제외 | 기존 복합 키(`columnKey + rid`) 구조를 그대로 사용 |
| 동시 삽입 | `DatabaseSession.waitForConflictingRow()` — 충돌 RID에 **S 잠금 획득으로 상대 트랜잭션 종료를 대기** 후 1회 재시도 | xid 대기 대신 행 잠금 대기. Strict 2PL에서는 잠금이 트랜잭션 종료까지 유지되므로 등가. 데드락은 기존 감지기가 40P01로 처리 |
| 검사–삽입 원자성 | 보장하지 않음 (`ponytail:` 주석) | B+Tree 자체가 아직 동시 쓰기에 안전하지 않은 기존 한계. B+Tree 래치 도입 시 함께 해결 |
| NULL | 검사 제외 (`NULLS DISTINCT`) | 동일 |
| 에러 | `UniqueViolationException(indexName, conflictingRid)` → 23505, 메시지 `duplicate key value violates unique constraint "..."` | 동일. 예외가 `table` 패키지에 있는 이유는 모듈 의존 방향(`sql → table`) 때문 |
| `CREATE UNIQUE INDEX` 빌드 | 스캔하며 트리 조회로 검사, 위반 시 Catalog 미등록 | 정렬 기반 인접 검사 대신 단순화. 결과 동일 |

- 부수 수정: `Catalog.dropTable()`이 해당 테이블의 인덱스를 함께 제거한다. 이전에는
  고아 인덱스가 남아 `DROP TABLE` 후 같은 이름의 PK 테이블 재생성이 실패했다.
- Catalog 직렬화 포맷에 인덱스별 `unique` 1바이트가 추가되어 이전 파일과 호환되지 않는다.

**참고 자료**

- [PostgreSQL 문서: Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html) — UNIQUE/PK가 유일 인덱스로 구현됨, NULLS DISTINCT
- [PostgreSQL 문서: Unique Indexes](https://www.postgresql.org/docs/current/indexes-unique.html)
- [PostgreSQL 문서: pg_index](https://www.postgresql.org/docs/current/catalog-pg-index.html) — `indisunique`, `indisprimary`
- [PostgreSQL 문서: Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html) — 23505 `unique_violation`
- [`src/backend/executor/execIndexing.c`](https://github.com/postgres/postgres/blob/master/src/backend/executor/execIndexing.c) — 파일 상단 주석이 힙 삽입 → 인덱스 삽입 순서와 동시 삽입 처리를 설명
- [`src/backend/access/nbtree/nbtinsert.c`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtinsert.c) — `_bt_check_unique()`, `_bt_doinsert()`의 `XactLockTableWait()`
- [`src/backend/access/nbtree/nbtsort.c`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtsort.c) — 인덱스 빌드 시 유일성 검사
- [`src/backend/access/nbtree/README`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/README) — B-Tree 동시성 설계 (Lehman & Yao)

### 21. FOREIGN KEY / CHECK (23503, 23514)

- UNIQUE 위에 얹힌다. FK는 부모 테이블의 UNIQUE 인덱스를 참조한다.
- INSERT 자식 → 부모 존재 확인, DELETE 부모 → 자식 존재 확인(RESTRICT만 MVP).
- CHECK는 `ExpressionEvaluator`로 INSERT/UPDATE 시 평가.
- PostgreSQL: FK는 트리거(`RI_FKey_check_ins`)로 구현된다.

### 22. Serialization Failure (40001)

- 2PL에서는 발생하지 않는다. write-write 충돌은 대기 또는 데드락으로 해소된다.
- MVCC + Snapshot Isolation 도입 후에야 재현 가능. 별도 Phase급 작업.
- PostgreSQL: `heap_update()`의 `HeapTupleUpdated` 처리, SSI(`predicate.c`)

---

## 우선순위 가이드

### Query Optimizer

| 순위 | 항목 | 이유 |
|------|------|------|
| 1 | 범위 스캔 | 등가 조건만으로는 인덱스 활용이 제한적 |
| 2 | Hash Join | 대량 등가 조인 성능 대폭 향상 |
| 3 | GROUP BY / 집계 | 실용적 쿼리 지원에 필수 |
| 4 | CBO (히스토그램) | 데이터 분포 반영으로 계획 품질 향상 |
| 5 | 복합 인덱스 | 실무 인덱스 설계의 핵심 |
| 6 | LEFT OUTER JOIN | 실무 쿼리에서 빈번하게 사용 |
| 7 | 서브쿼리 | SQL 표현력 확장 |
| 8 | DP 계획 열거 | 조인 수 증가 시 최적 계획 보장 |
| 9 | Index-Only Scan | 읽기 최적화 |
| 10 | Window 함수 | 분석 쿼리 지원 |

### Networking

| 순위 | 항목 | 이유 |
|------|------|------|
| 1 | Extended Query | JDBC 완전 호환, 벤치마크 도구 호환성 대폭 향상 |
| 2 | 바이너리 포맷 | 성능 벤치마크에서 텍스트 인코딩 오버헤드 제거 |
| 3 | COPY | 대량 데이터 로딩 벤치마크 필수 (pgbench -i) |
| 4 | 쿼리 취소 | 장시간 쿼리 제어, 운영 안정성 |
| 5 | 인증 | 보안 요구 시 |
| 6 | SSL/TLS | 보안 요구 시 |
| 7 | 연결 풀링 | 고부하 환경 |
| 8 | NIO | 대규모 동시 접속 |
| 9 | NOTIFY/LISTEN | 부가 기능 |

### Constraints & Error Semantics

| 순위 | 항목 | 이유 |
|------|------|------|
| 1 | SQLSTATE 매핑 ✅ | 이후 모든 에러의 전달 경로 |
| 2 | 데이터 예외 ✅ | 검사 한 곳씩, 낮은 비용 |
| 3 | 락 타임아웃 ✅ | 무한 대기 제거, 운영 안정성 |
| 4 | UNIQUE / PK ✅ | 중복 키 에러 — 실무에서 가장 빈번한 재시도 대상 |
| 5 | FK / CHECK | UNIQUE 위에 구축 |
| 6 | Serialization Failure | MVCC 선행 필요 |

## 참고 자료

- PostgreSQL 소스: `src/backend/optimizer/`, `src/backend/executor/`, `src/backend/tcop/`
- [PostgreSQL Error Codes (Appendix A)](https://www.postgresql.org/docs/current/errcodes-appendix.html)
- *The Internals of PostgreSQL* (Hironobu Suzuki) — 무료 온라인, 옵티마이저/프로토콜 장
- CMU 15-721 (Andy Pavlo) — 고급 주제: 비용 모델, 적응형 실행
- *Access Path Selection in a Relational Database Management System* (Selinger et al., 1979) — System R 옵티마이저 원논문
- [PostgreSQL Frontend/Backend Protocol](https://www.postgresql.org/docs/current/protocol.html)
- [PostgreSQL Message Formats](https://www.postgresql.org/docs/current/protocol-message-formats.html)
