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

#### 범위 스캔 (Range Scan) ✅

`WHERE age > 20 AND age < 30` 같은 부등식 조건을 B+Tree의 `scan(startKey, endKey)`로
처리한다. MVP에서는 등가 조건만 인덱스 스캔 대상이었다.

**PostgreSQL 방식**

- 조건 매칭 (`indxpath.c`, `match_opclause_to_indexcol()`): WHERE 절이
  `indexkey OP const` 꼴이고 OP가 인덱스 opfamily(btree는 `<`, `<=`, `=`, `>=`, `>`)에
  속하면 인덱스 조건(indexqual)이 된다. `const OP indexkey`는 교환자(commutator)로
  뒤집는다. 인덱스로 보내지 못한 조건은 filter(qpqual)로 남겨 힙 튜플에서 평가한다.
- 키 전처리 (`nbtutils.c`, `_bt_preprocess_keys()`): 같은 컬럼의 여러 조건을 정리해
  중복·모순을 제거하고, `_bt_first()`가 가장 좁은 시작 위치로 내려간 뒤 `_bt_checkkeys()`가
  리프를 순회하며 종료 시점을 판정한다. 시작 위치는 컬럼당 하나의 3-way 비교 키
  (insertion scankey)로 잡고, 순회 중 필터링은 원래 조건(search scankey)으로 한다.
- 재검사 (`index-scanning.html`): 인덱스 AM이 lossy라고 알리면 executor가 힙 튜플에서
  인덱스 조건을 다시 평가한다. 정확한 AM은 recheck 없이 결과를 그대로 쓴다.
- 선택도 (`selfuncs.c`, `scalarineqsel()`; `clausesel.c`, `clauselist_selectivity_ext()`):
  부등식 하나는 히스토그램으로 추정하고, 같은 컬럼의 상·하한 쌍은 `hisel + losel - 1`로
  결합한다. 통계가 없으면 단방향 `DEFAULT_INEQ_SEL = 1/3`, 양방향
  `DEFAULT_RANGE_INEQ_SEL = 0.005`를 쓴다.

**Gwanbase 구현**

| 항목 | 구현 | PostgreSQL과의 차이 | 이유 |
|------|------|---------------------|------|
| 조건 매칭 | `PlanEnumerator`가 WHERE의 AND 체인에서 `col OP literal`(교환 포함)을 모아 인덱스 있는 컬럼별로 하한·상한 경계를 만든다. 인덱스마다 경계를 만들어 비용을 계산하고 SeqScan을 포함해 가장 저렴한 계획을 고른다 | opfamily 대신 `BinaryOperator` 5종을 직접 매칭. OR·NOT·함수 조건은 미지원 | 연산자 클래스 체계가 없고 타입도 5개뿐이라 opfamily 추상화는 이름만 남는다. OR는 Bitmap Index Scan 항목에서 다룬다 |
| 리터럴 판정 | `IntLiteral`, `StringLiteral`, `BoolLiteral`, `FloatLiteral`만 경계 값으로 허용. `NullLiteral`·산술식은 필터에만 남긴다 | PostgreSQL은 volatile 함수·인덱스 테이블 변수가 없는 모든 식을 const로 본다 | `Planner.evaluateLiteral()`이 리터럴만 평가할 수 있다. NULL 경계는 비교가 항상 UNKNOWN이므로 실행기가 빈 결과를 낸다 |
| 경계 범위 검사 | INT32 컬럼의 경계 리터럴이 Int 범위를 벗어나면 그 경계를 버리고(범위 확장) 재검사에 맡긴다 (`Planner.toKeyRange`) | PostgreSQL은 바인딩 단계에서 타입을 강제해 이런 리터럴이 옵티마이저에 도달하지 않는다 | Binder가 비교 피연산자 타입을 검사하지 않는 기존 한계. 확장 방향은 재검사 덕분에 항상 정확하다 |
| 키 전처리 | 같은 방향 경계가 둘 이상이면 **첫 번째만** 인덱스로 보내고 나머지는 필터에 맡긴다 | `_bt_preprocess_keys()`는 더 좁은 쪽을 고른다 | 리터럴 값 비교 코드를 줄이기 위한 단순화. 재검사가 정확성을 보장하므로 결과는 같고 스캔 범위만 넓어질 수 있다 |
| 계획 노드 | `PlanNode.IndexScan`의 `lookupValue`를 `lowerBound / upperBound: Bound?` (`Expression` + `inclusive`)로 일반화. 등가는 양쪽 경계가 같은 값이고 포함 | 노드 하나로 등가·범위를 표현. 별도 RangeScan 노드 없음 | PostgreSQL도 `IndexScan` 하나에 indexqual 목록을 싣는다. 노드를 나누면 비용·EXPLAIN·Planner 변환이 두 벌이 되고 복합 인덱스 때 다시 합쳐야 한다 |
| 바이트 경계 | `KeyRange` → `[startKey, endKey?)` 변환을 `KeySerializer`가 담당. `>= v`는 `v`, `> v`는 `successor(v)`, `< v`는 `v` 미만, `<= v`는 `successor(v)` 미만, 하한 없음은 빈 배열, 상한 없음은 `null`. `successor`는 기존 `equalityScanEnd()`. 타입 최대값처럼 successor가 없는 키(전부 0xFF)는 상한 없음(null)으로 처리한다. | insertion scankey 대신 바이트 경계 두 개. 복합 키(`컬럼값 + RID`)라 컬럼값 자체의 successor가 경계가 된다 | B+Tree가 타입을 모르고 unsigned 바이트만 비교하는 구조를 유지한다. 경계 계산을 `KeySerializer` 한 곳에 두면 `scan()`은 그대로 쓸 수 있다 |
| B+Tree | `scan(startKey, endKey: ByteArray?)` — `null`이면 리프 체인 끝까지 | 동일 의미 | 가변 길이 키라 "가장 큰 키"를 만들 수 없어 `null`로 표현한다 |
| 실행기 | `IndexScanOperator`가 `rangeSupplier: () -> KeyRange?`로 경계를 받는다. **재검사는 옵티마이저가 인덱스 조건을 필터에서 제거하지 않는 방식**으로 구현한다. 기존 `remainingFilter` 평가가 곧 recheck이고 `removeCondition()`은 삭제. RID는 lazy 순회한다. | PostgreSQL은 정확한 AM에서 recheck를 생략한다. Gwanbase는 항상 재검사한다 | 새 코드 없이 기존 필터 경로가 recheck 역할을 한다. 키 인코딩 정확성에 결과가 좌우되지 않아 안전하고, 비용은 튜플당 표현식 평가 한 번이다 |
| 선택도 | `CostEstimator.rangeSelectivity(stats, lower, upper)`로 통일: 통계가 있으면 `(min(upper,max) - max(lower,min)) / (max - min)`을 [0,1]로 clamp, 없으면 단방향 1/3, 양방향 0.005 | 히스토그램 없이 min/max 균등 분포 가정. 정수 컬럼만 통계 사용, VARCHAR는 기본값 | 히스토그램은 CBO 항목(3번)에서 도입한다. 기본값은 PostgreSQL 상수를 그대로 써서 이후 비교가 쉽도록 한다 |
| EXPLAIN | 등가면 `key=v`, 범위면 `range=[v1, v2)` 형식. 필터(인덱스 조건 포함)를 `filter=`로 함께 출력한다 | PostgreSQL은 `Index Cond`와 `Filter`를 분리 표시 | 재검사 방식상 필터에 인덱스 조건이 남는 것이 실제 동작이다. 표시만 분리하면 동작과 어긋난다 |

**선행 버그 수정: VARCHAR 인덱스 키 접두사 문제**

`KeySerializer`가 VARCHAR를 길이 정보 없이 UTF-8 바이트 그대로 직렬화하고 뒤에 RID를
붙이므로 `'abc' + RID`와 `'abcd' + RID`의 바이트 순서가 문자열 순서와 일치하지 않는다.
등가 스캔 `[abc, abd)`에 `'abcd'`가 포함되어 `WHERE name = 'abc'`가 `'abcd'` 행을 반환하고
(IndexScan이 선택되는 600행 이상에서 재현), 같은 경로를 쓰는 UNIQUE 검사는 `'abc'`가
있을 때 `'abcd'` 삽입을 23505로 잘못 거부한다. 범위 스캔에서는 `name > 'abc'`가
`'abcd'`를 누락하는 정확성 문제가 된다.

수정: VARCHAR 키 뒤에 종단 바이트 `0x00`을 붙인다. UTF-8에는 NUL 문자 외에 `0x00`이
없으므로 `'abc\0' < 'abcd\0'`이고 `successor('abc\0') = 'abc\1' < 'abcd\0'`이 보장된다.
NUL을 포함한 문자열은 `require`로 거부한다(PostgreSQL도 text에 NUL을 허용하지 않는다).
온디스크 인덱스 포맷이 바뀌므로 기존 DB 파일은 인덱스를 재생성해야 한다.

**범위 밖 (이후 항목)**

- `BETWEEN` 문법 (파서 desugar만 필요)
- 인덱스 순서를 ORDER BY에 활용 (Sort 제거)
- Index Nested Loop Join의 동적 범위 키
- VARCHAR 컬럼 min/max 통계

**참고 자료**

- [PostgreSQL 문서: Index Types](https://www.postgresql.org/docs/current/indexes-types.html) — B-tree가 처리하는 연산자 `<`, `<=`, `=`, `>=`, `>`, `BETWEEN`
- [PostgreSQL 문서: Index Scanning](https://www.postgresql.org/docs/current/index-scanning.html) — lossy 인덱스와 recheck
- [`src/backend/optimizer/path/indxpath.c`](https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/indxpath.c) — `match_clause_to_indexcol()`, `match_opclause_to_indexcol()`, indexqual vs qpqual
- [`src/backend/access/nbtree/README`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/README) — search scankey / insertion scankey, `_bt_first()`, `_bt_checkkeys()`
- [`src/backend/access/nbtree/nbtutils.c`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtutils.c) — `_bt_preprocess_keys()`
- [`src/backend/access/nbtree/nbtsearch.c`](https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtsearch.c) — `_bt_first()`
- [`src/backend/utils/adt/selfuncs.c`](https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/selfuncs.c) — `scalarineqsel()`, `ineq_histogram_selectivity()`
- [`src/include/utils/selfuncs.h`](https://github.com/postgres/postgres/blob/master/src/include/utils/selfuncs.h) — `DEFAULT_INEQ_SEL`, `DEFAULT_RANGE_INEQ_SEL`
- [`src/backend/optimizer/path/clausesel.c`](https://github.com/postgres/postgres/blob/master/src/backend/optimizer/path/clausesel.c) — `clauselist_selectivity_ext()`, `addRangeClause()`, `hisel + losel - 1`

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
| 검사–삽입 원자성 | 보장하지 않음 (`Database.findRidByColumnKey` 주석 참조) | B+Tree 자체가 아직 동시 쓰기에 안전하지 않은 기존 한계. B+Tree 래치 도입 시 함께 해결 |
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

### 21. FOREIGN KEY / CHECK (23503, 23514) ✅

**PostgreSQL 방식**

- 두 제약 모두 `pg_constraint` 행으로 남는다 (`contype = 'f'` / `'c'`). CHECK 표현식은
  `conbin`에 노드 트리로 저장되고 `pg_get_constraintdef()`가 텍스트로 되돌린다.
- CHECK는 실행기가 힙 삽입 **전에** `ExecConstraints()` → `ExecRelCheck()`로 평가한다.
  결과가 NULL이면 통과한다 (SQL 표준). 위반 시 `ERRCODE_CHECK_VIOLATION`(23514).
- FK는 **RI 트리거**로 구현된다. 자식 INSERT/UPDATE 후 AFTER 트리거
  `RI_FKey_check_ins/upd`가 `SELECT 1 FROM ONLY parent WHERE pk = $1 FOR KEY SHARE`를
  SPI로 실행해 부모 존재를 확인하고, `FOR KEY SHARE` 잠금으로 트랜잭션이 끝날 때까지
  부모 키 삭제·변경을 막는다. 부모 DELETE/UPDATE 후에는 `RI_FKey_noaction_del/upd`
  (`ri_restrict`)가 자식 테이블을 `FOR KEY SHARE`로 조회해 참조 행이 남아 있으면
  `ERRCODE_FOREIGN_KEY_VIOLATION`(23503)을 낸다. `NO ACTION`은 문 끝(또는 DEFERRED면
  커밋 시점)에, `RESTRICT`는 즉시 검사한다는 점만 다르다.
- 참조 컬럼에는 유일 인덱스가 있어야 한다 (`transformFkeyCheckAttrs()`). 참조 컬럼을
  생략하면 부모 PRIMARY KEY를 참조한다. FK 값이 NULL이면 검사하지 않는다
  (`MATCH SIMPLE` 기본).
- 제약 이름은 `ChooseConstraintName()`이 `{table}_{columns}_fkey`, `{table}_{columns}_check`로
  짓는다. CHECK의 `columns`는 표현식이 실제 참조하는 컬럼이다.
- 참조되는 테이블·인덱스는 의존성 때문에 `DROP`이 거부되고(2BP01), `CASCADE`로
  제약을 함께 지울 수 있다.

**Gwanbase 구현**

| 항목 | 구현 | PostgreSQL과의 차이 |
|------|------|---------------------|
| 제약의 실체 | `Catalog.ForeignKeyInfo`, `Catalog.CheckInfo` — pg_constraint 역할. CHECK 표현식은 `Expression.toSql()` 텍스트로 저장하고 실행 시 `Parser.parseStandaloneExpression()`으로 재파싱 | 노드 트리 대신 텍스트 저장. `table` 패키지가 `sql`의 AST에 의존할 수 없고, 텍스트 왕복이 훨씬 단순하다 |
| 파서 | 컬럼 제약 `REFERENCES t [(c)]`, `CHECK (expr)` (`ColumnDef.references / check`) | 테이블 수준 제약(`FOREIGN KEY (a) REFERENCES ...`, 테이블 `CHECK`), `ON DELETE CASCADE/SET NULL`, 자기 참조 테이블은 미지원 |
| 이름 | `{table}_{column}_fkey`, `{table}_{column}_check` (`SqlExecutor.executeCreateTable`) | CHECK 이름에 표현식이 참조하는 컬럼 대신 **선언된 컬럼**을 쓴다 |
| 바인딩 | `Binder.bindCreateTable`: 부모 테이블·컬럼 존재, 유일 인덱스 존재, 타입 일치, CHECK 컬럼 참조 검증. PK 참조 생략은 `{table}_pkey` 인덱스로 해석 | `indisprimary` 플래그가 없어 이름 규약으로 PK를 찾는다. 타입은 암묵 캐스트 없이 정확히 같아야 한다 |
| CHECK 검사 | `SqlExecutor.rowConstraintChecker` — INSERT/UPDATE 튜플 완성 후 **힙 변경 전** `ExpressionEvaluator.evaluate()`. `false`만 위반, NULL 통과 | 시점·NULL 의미 동일 |
| FK 자식 검사 | 같은 함수에서 부모 유일 인덱스로 RID 조회(`Database.findRidByUniqueIndex`) → 부모 RID에 **S 잠금** → 잠금 후 부모 행 재확인 | 트리거 대신 실행기가 직접 호출. `FOR KEY SHARE` 대신 행 S 잠금 — Strict 2PL에서 트랜잭션 종료까지 유지되므로 부모 삭제를 막는 효과는 같다. MVCC가 없어 다른 트랜잭션이 미커밋 삭제한 부모는 즉시 위반으로 본다 |
| FK 부모 검사 | `SqlExecutor.checkNoReferencingRows` — DELETE/키 변경 UPDATE 시 부모 행 **X 잠금 획득 후** `Database.existsRowWithValue()`로 자식 존재 확인 (자식 컬럼 인덱스가 있으면 트리 조회, 없으면 순차 스캔). 자기 참조 테이블은 자기 RID 제외 | `RESTRICT`만 지원(즉시 검사). 자식 삽입이 부모 S 잠금을 잡으므로 X 잠금을 얻은 뒤에는 미커밋 자식 삽입이 없다. 다른 트랜잭션이 미커밋 삭제한 자식은 보이지 않아 그 트랜잭션이 abort하면 고아 행이 남을 수 있다 (MVCC 부재의 기존 한계) |
| DROP 거부 | `Binder.bindDropTable / bindDropIndex`: 다른 테이블의 FK가 참조하면 `BindException` | 2BP01 대신 42000. `CASCADE` 미지원 |
| 에러 | `ConstraintViolationException(constraintName, sqlState, message)` → 23503 / 23514. 메시지는 PostgreSQL 형식 (`new row for relation "t" violates check constraint "..."`, `insert or update on table "..." violates foreign key constraint "..."`, `update or delete on table "..." violates foreign key constraint "..." on table "..."`) | `DataException`처럼 코드를 필드로 가진다. UNIQUE는 충돌 RID를 실어야 해서 별도 클래스 |

- Catalog 직렬화 포맷에 제약 섹션이 추가되었다. 이전 파일은 읽을 수 있지만(섹션 없음 허용)
  새 파일은 이전 코드로 읽을 수 없다.

**참고 자료**

- [PostgreSQL 문서: Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html) — CHECK NULL 처리, FK 참조 동작(NO ACTION/RESTRICT/CASCADE), MATCH SIMPLE
- [PostgreSQL 문서: CREATE TABLE](https://www.postgresql.org/docs/current/sql-createtable.html) — `REFERENCES reftable [ ( refcolumn ) ]` 문법
- [PostgreSQL 문서: pg_constraint](https://www.postgresql.org/docs/current/catalog-pg-constraint.html) — `contype`, `conbin`
- [PostgreSQL 문서: Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html) — 23503 `foreign_key_violation`, 23514 `check_violation`, 2BP01 `dependent_objects_still_exist`
- [`src/backend/executor/execMain.c`](https://github.com/postgres/postgres/blob/master/src/backend/executor/execMain.c) — `ExecConstraints()`, `ExecRelCheck()`
- [`src/backend/utils/adt/ri_triggers.c`](https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/ri_triggers.c) — `RI_FKey_check()`, `ri_restrict()`, `ri_PerformCheck()`, `ri_ReportViolation()`
- [`src/backend/commands/tablecmds.c`](https://github.com/postgres/postgres/blob/master/src/backend/commands/tablecmds.c) — `transformFkeyCheckAttrs()` (참조 컬럼의 유일 인덱스 요구), `ATAddForeignKeyConstraint()`
- [`src/backend/commands/indexcmds.c`](https://github.com/postgres/postgres/blob/master/src/backend/commands/indexcmds.c) — `ChooseConstraintName()`

### 22. 트랜잭션 실패 상태 (25P02) ✅

명시적 트랜잭션 안에서 오류가 난 뒤의 세션 동작. 이전에는 `ConnectionHandler`와
플레이그라운드가 각자 I/T/E 상태기계를 갖고 있었고, `DatabaseSession`은 실행 단계 오류에
즉시 abort해 이후 `ROLLBACK`이 `IllegalStateException`을 던졌다(플레이그라운드는 `catch`로 우회).

**PostgreSQL 방식**

- 오류가 나면 `AbortCurrentTransaction()` → `AbortTransaction()`이 **그 자리에서** undo·잠금
  해제·리소스 정리를 끝내고, 블록 상태만 `TBLOCK_ABORT`로 남긴다.
- 이후 문장은 `exec_simple_query()`에서 `IsAbortedTransactionBlockState() &&
  !IsTransactionExitStmt()` → 25P02 `current transaction is aborted, commands ignored
  until end of transaction block`.
- `ROLLBACK`은 `CleanupTransaction()`만 수행한다. `COMMIT`은 `EndTransactionBlock()`이
  false를 돌려주고 `standard_ProcessUtility()`가 command tag를 `ROLLBACK`으로 바꾼다 —
  오류가 아니다.
- ReadyForQuery의 트랜잭션 상태 바이트(I/T/E)와 libpq `PQtransactionStatus()`가 같은 3값을 노출한다.

**Gwanbase 구현**

| 항목 | 구현 | PostgreSQL과의 차이 | 이유 |
|------|------|---------------------|------|
| 실패 진입 | `DatabaseSession.executeSql`이 파싱·바인딩·실행 어느 단계 오류든 명시적 트랜잭션이면 `abortInternal()`(undo + 잠금 해제) 후 `txnFailed = true`. auto-commit은 abort만 하고 플래그를 세우지 않는다 | 동일. 잠금은 오류 시점에 즉시 풀린다 | 잠금 보유를 ROLLBACK까지 미루면 실패한 세션이 다른 세션을 막는다. PG가 즉시 푸는 이유와 같다 |
| 실패 상태의 문장 | `ROLLBACK`/`COMMIT` → 플래그만 지우고 `TransactionRolledBack` 반환. 그 외(파싱 실패 포함) → `TransactionAbortedException`(25P02) | 동일 | abort는 이미 끝났으므로 상태 정리만 남는다 |
| 상태 노출 | `DatabaseSession.txnStatus: Char` (I/T/E) | libpq `PQtransactionStatus`와 같은 3값 | 서버·플레이그라운드가 각자 상태기계를 갖지 않고 세션 하나만 보게 한다 |
| 중첩 BEGIN | 기존대로 `IllegalStateException`, 실패 상태로 가지 않음 | PG는 WARNING만 내고 무시한다 | 트랜잭션 제어문 오류는 블록을 망가뜨리지 않는다. 경고 채널이 없어 예외로 남긴다 |
| 클라이언트 | `ConnectionHandler`·`PlaygroundSession`은 `session.txnStatus`만 읽고, 25P02 판별·ROLLBACK 문자열 비교·`IllegalStateException` 우회를 모두 삭제 | — | 상태의 진실은 세션 한 곳 |

**참고 자료**

- [PostgreSQL 문서: Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html) — 25P02 `in_failed_sql_transaction`
- [PostgreSQL 문서: Message Flow — ReadyForQuery](https://www.postgresql.org/docs/current/protocol-flow.html) — 트랜잭션 상태 바이트 I/T/E
- [`src/backend/access/transam/xact.c`](https://github.com/postgres/postgres/blob/master/src/backend/access/transam/xact.c) — `AbortTransaction()`, `AbortCurrentTransaction()`, `EndTransactionBlock()`의 `TBLOCK_ABORT` 분기, `IsAbortedTransactionBlockState()`
- [`src/backend/tcop/postgres.c`](https://github.com/postgres/postgres/blob/master/src/backend/tcop/postgres.c) — `exec_simple_query()`의 25P02 검사, `IsTransactionExitStmt()`
- [`src/backend/tcop/utility.c`](https://github.com/postgres/postgres/blob/master/src/backend/tcop/utility.c) — `standard_ProcessUtility()` `TRANS_STMT_COMMIT`: 실패 시 `CMDTAG_ROLLBACK`

### 23. Serialization Failure (40001)

- 2PL에서는 발생하지 않는다. write-write 충돌은 대기 또는 데드락으로 해소된다.
- MVCC + Snapshot Isolation 도입 후에야 재현 가능. 별도 Phase급 작업.
- PostgreSQL: `heap_update()`의 `HeapTupleUpdated` 처리, SSI(`predicate.c`)

---

## 우선순위 가이드

### Query Optimizer

| 순위 | 항목 | 이유 |
|------|------|------|
| 1 | 범위 스캔 ✅ | 등가 조건만으로는 인덱스 활용이 제한적 |
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
| 5 | FK / CHECK ✅ | UNIQUE 위에 구축 |
| 6 | 트랜잭션 실패 상태 ✅ | 오류 후 ROLLBACK 계약 — 서버·플레이그라운드 상태기계 중복 제거 |
| 7 | Serialization Failure | MVCC 선행 필요 |

## 참고 자료

- PostgreSQL 소스: `src/backend/optimizer/`, `src/backend/executor/`, `src/backend/tcop/`
- [PostgreSQL Error Codes (Appendix A)](https://www.postgresql.org/docs/current/errcodes-appendix.html)
- *The Internals of PostgreSQL* (Hironobu Suzuki) — 무료 온라인, 옵티마이저/프로토콜 장
- CMU 15-721 (Andy Pavlo) — 고급 주제: 비용 모델, 적응형 실행
- *Access Path Selection in a Relational Database Management System* (Selinger et al., 1979) — System R 옵티마이저 원논문
- [PostgreSQL Frontend/Backend Protocol](https://www.postgresql.org/docs/current/protocol.html)
- [PostgreSQL Message Formats](https://www.postgresql.org/docs/current/protocol-message-formats.html)
