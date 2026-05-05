# Phase 8: Networking & Client Protocol

## 목표

PostgreSQL Wire Protocol (v3.0) Simple Query 서브셋을 구현하여, `psql`이나 JDBC
드라이버로 Gwanbase에 접속해 SQL을 실행할 수 있게 한다. Thread-per-connection
모델로 다중 클라이언트를 동시에 처리하며, 기존 `DatabaseSession` 기반의 트랜잭션
격리를 네트워크 세션에서도 그대로 유지한다.

### MVP 범위

- TCP 서버 (thread-per-connection, `ServerSocket`)
- PostgreSQL Simple Query 프로토콜 (~10종 메시지)
- 인증 스킵 (AuthenticationOk 고정 반환, trust 모드)
- 연결당 `DatabaseSession` 바인딩
- 텍스트 포맷 결과 반환
- 에러 응답 (`ErrorResponse` 메시지)
- Graceful shutdown (Terminate 메시지, 서버 종료 시 세션 정리)

### 비기능 요구사항

- 기존 Phase 1–7 테스트 전체 회귀 없음
- `psql -h localhost -p 5432` 로 접속하여 DDL/DML/트랜잭션 실행 가능
- JDBC(`org.postgresql:postgresql`) 드라이버로 접속하여 기본 CRUD 가능
- 다중 연결(최소 4개 동시)에서 트랜잭션 격리가 정상 동작

## 인터페이스 설계 (public API)

### GwanServer

```kotlin
class GwanServer(
    private val database: Database,
    private val port: Int = 5432,
) : AutoCloseable {
    fun start()              // accept 루프 시작 (별도 스레드)
    fun stop()               // 서버 종료 + 활성 연결 정리
    override fun close() = stop()
}
```

### 사용 예시

```kotlin
val db = Database.open(Path.of("mydb.gwan"))
val server = GwanServer(db, port = 5432)
server.start()
// psql -h localhost -p 5432 -U gwanbase 로 접속 가능
server.close()
db.close()
```

## 내부 설계

### 1. 아키텍처 개요

```
psql / JDBC Client
       │
       ▼ TCP (port 5432)
┌─────────────────────────────┐
│       GwanServer            │  ← ServerSocket.accept() 루프
│  ┌───────────────────────┐  │
│  │  ConnectionHandler    │  │  ← 연결당 1 스레드
│  │  ├─ PgMessageReader   │  │  ← 바이트 → PgMessage 파싱
│  │  ├─ PgMessageWriter   │  │  ← PgMessage → 바이트 직렬화
│  │  └─ DatabaseSession   │  │  ← 기존 세션 재사용
│  └───────────────────────┘  │
└─────────────────────────────┘
              │
              ▼
┌─────────────────────────────┐
│         Database            │  ← 기존 코드 그대로
└─────────────────────────────┘
```

의존 방향: `server → txn → execution → sql → table → index → storage`

### 2. PostgreSQL Wire Protocol 메시지

#### Startup Phase

```
Client                              Server
  │── StartupMessage ──────────────→│  (version=3.0, params: user, database)
  │←── AuthenticationOk ────────────│  (R, 길이=8, 상태=0)
  │←── ParameterStatus ────────────│  (S, server_version 등)
  │←── BackendKeyData ─────────────│  (K, pid + secret — 형식만)
  │←── ReadyForQuery ──────────────│  (Z, 'I')
```

#### Query Phase (Simple Query)

```
Client                              Server
  │── Query ("SELECT ...") ────────→│  ('Q' + null-terminated SQL)
  │                                  │
  │  ── SELECT 결과 ──               │
  │←── RowDescription ─────────────│  ('T', 컬럼 메타데이터)
  │←── DataRow (× N) ─────────────│  ('D', 텍스트 인코딩 값)
  │←── CommandComplete ────────────│  ('C', "SELECT N")
  │←── ReadyForQuery ──────────────│  ('Z', 트랜잭션 상태)
  │                                  │
  │  ── DML/DDL ──                   │
  │←── CommandComplete ────────────│  ('C', "INSERT 0 1" 등)
  │←── ReadyForQuery ──────────────│  ('Z', 트랜잭션 상태)
  │                                  │
  │  ── 에러 ──                      │
  │←── ErrorResponse ──────────────│  ('E', severity + message + code)
  │←── ReadyForQuery ──────────────│  ('Z', 트랜잭션 상태)
```

#### Termination

```
Client                              Server
  │── Terminate ───────────────────→│  ('X')
  │                (연결 종료)       │
```

#### 메시지 포맷 상세

모든 메시지(StartupMessage 제외)는 공통 프레임을 따른다:
- 1바이트 타입 식별자
- 4바이트 길이 (자신 포함, 타입 바이트 제외)
- 페이로드

StartupMessage는 예외적으로 타입 바이트 없이 4바이트 길이로 시작한다.

| 메시지 | 방향 | 타입 | 주요 필드 |
|--------|------|------|-----------|
| StartupMessage | C→S | (없음) | version(Int32), params(String pairs) |
| Query | C→S | 'Q' (0x51) | sql(String, null-terminated) |
| Terminate | C→S | 'X' (0x58) | (없음) |
| AuthenticationOk | S→C | 'R' (0x52) | status=0(Int32) |
| ParameterStatus | S→C | 'S' (0x53) | name(String), value(String) |
| BackendKeyData | S→C | 'K' (0x4B) | pid(Int32), secret(Int32) |
| ReadyForQuery | S→C | 'Z' (0x5A) | txnStatus(Byte): 'I'/'T'/'E' |
| RowDescription | S→C | 'T' (0x54) | fieldCount(Int16), fields(ColumnDesc[]) |
| DataRow | S→C | 'D' (0x44) | fieldCount(Int16), values(길이+바이트[]) |
| CommandComplete | S→C | 'C' (0x43) | tag(String, null-terminated) |
| ErrorResponse | S→C | 'E' (0x45) | fields(타입코드+String pairs), 0x00 종료 |

#### ErrorResponse 필드 구조

ErrorResponse는 타입코드(1바이트) + null-terminated 문자열 쌍의 반복으로 구성되며,
0x00으로 종료한다. MVP에서 사용하는 필드:

| 타입코드 | 의미 | 값 |
|----------|------|-----|
| 'S' | Severity | `ERROR` / `FATAL` |
| 'V' | Severity (비지역화) | `ERROR` / `FATAL` |
| 'C' | SQLSTATE Code | `42601` (syntax error) 등. 분류 불가 시 `XX000` (internal error) |
| 'M' | Message | 예외 메시지 텍스트 |

#### RowDescription 컬럼 디스크립터

RowDescription의 각 필드는 다음 구조를 따른다:

| 필드 | 타입 | 설명 |
|------|------|------|
| name | String | 컬럼명 (null-terminated) |
| tableOid | Int32 | 테이블 OID (0 고정) |
| columnAttr | Int16 | 컬럼 번호 (0 고정) |
| typeOid | Int32 | 타입 OID |
| typeSize | Int16 | 타입 크기 (바이트) |
| typeMod | Int32 | 타입 수식자 (-1 고정) |
| formatCode | Int16 | 0 = 텍스트 |

#### 타입 OID 매핑

| Gwanbase DataType | PostgreSQL OID | 타입명 | typeSize |
|-------------------|----------------|--------|----------|
| INT | 23 | int4 | 4 |
| BIGINT | 20 | int8 | 8 |
| VARCHAR | 25 | text | -1 |
| BOOLEAN | 16 | bool | 1 |

#### ReadyForQuery 트랜잭션 상태

| 상태 | 의미 | Gwanbase 조건 |
|------|------|---------------|
| 'I' | Idle | 활성 트랜잭션 없음 |
| 'T' | In Transaction | BEGIN 이후, 에러 없음 |
| 'E' | Failed Transaction | BEGIN 이후, 에러 발생 |

#### CommandComplete 태그 포맷

| ExecuteResult | 태그 |
|---------------|------|
| Created | `CREATE TABLE` |
| Dropped | `DROP TABLE` |
| Inserted | `INSERT 0 1` |
| Selected | `SELECT {rows.size}` |
| Updated | `UPDATE {count}` |
| Deleted | `DELETE {count}` |
| TransactionStarted | `BEGIN` |
| TransactionCommitted | `COMMIT` |
| TransactionRolledBack | `ROLLBACK` |
| IndexCreated | `CREATE INDEX` |
| IndexDropped | `DROP INDEX` |
| Analyzed | `ANALYZE` |
| Explained | (RowDescription + DataRow로 반환) |

EXPLAIN 결과는 단일 TEXT 컬럼 `QUERY PLAN`으로 RowDescription + DataRow를 사용하여
반환한다. PostgreSQL과 동일한 방식이다.

### 3. 컴포넌트 상세

#### PgMessage (sealed class)

```kotlin
sealed class PgMessage {
    // Client → Server
    data class StartupMessage(val version: Int, val params: Map<String, String>) : PgMessage()
    data class Query(val sql: String) : PgMessage()
    data object Terminate : PgMessage()

    // Server → Client
    data object AuthenticationOk : PgMessage()
    data class ParameterStatus(val name: String, val value: String) : PgMessage()
    data class BackendKeyData(val pid: Int, val secretKey: Int) : PgMessage()
    data class ReadyForQuery(val txnStatus: Char) : PgMessage()
    data class RowDescription(val columns: List<ColumnDesc>) : PgMessage()
    data class DataRow(val values: List<String?>) : PgMessage()
    data class CommandComplete(val tag: String) : PgMessage()
    data class ErrorResponse(val severity: String, val message: String, val code: String) : PgMessage()
}

data class ColumnDesc(
    val name: String,
    val tableOid: Int = 0,
    val columnAttr: Short = 0,
    val typeOid: Int,
    val typeSize: Short,
    val typeMod: Int = -1,
    val formatCode: Short = 0,
)
```

#### PgMessageReader

`InputStream`에서 PostgreSQL 바이너리 메시지를 파싱한다.

```kotlin
class PgMessageReader(private val input: InputStream) {
    /** Startup 메시지를 읽는다 (타입 바이트 없이 길이로 시작). */
    fun readStartupMessage(): PgMessage.StartupMessage

    /** 일반 메시지를 읽는다 (1바이트 타입 + 4바이트 길이 + 페이로드). */
    fun readMessage(): PgMessage
}
```

내부적으로 `DataInputStream`을 사용하여 빅엔디안 정수를 읽는다.
null-terminated 문자열은 0x00을 만날 때까지 바이트를 축적한다.

#### PgMessageWriter

`PgMessage`를 PostgreSQL 바이너리 포맷으로 직렬화한다.

```kotlin
class PgMessageWriter(private val output: OutputStream) {
    fun write(msg: PgMessage)
    fun flush()
}
```

내부적으로 `ByteArrayOutputStream`에 페이로드를 먼저 조립한 뒤, 타입 바이트 +
길이 + 페이로드 순서로 출력한다. 이렇게 하면 길이를 미리 계산할 필요 없이
페이로드 크기에서 역산할 수 있다.

#### ConnectionHandler

연결 라이프사이클을 관리한다.

```kotlin
class ConnectionHandler(
    private val socket: Socket,
    private val database: Database,
) : Runnable {
    override fun run() {
        socket.use { sock ->
            val reader = PgMessageReader(sock.getInputStream())
            val writer = PgMessageWriter(sock.getOutputStream())

            handleStartup(reader, writer)
            database.createSession().use { session ->
                queryLoop(session, reader, writer)
            }
        }
    }
}
```

**Startup 처리:**
1. `readStartupMessage()`로 클라이언트 파라미터 수신
2. `AuthenticationOk` 전송 (trust 모드)
3. `ParameterStatus` 전송 (`server_version`, `server_encoding`, `client_encoding`)
4. `BackendKeyData` 전송 (pid=현재 스레드 ID, secret=0)
5. `ReadyForQuery('I')` 전송

**Query Loop:**
1. `readMessage()` 호출
2. `Query` → `session.executeSql(sql)` → `ResultFormatter`로 변환 → 응답 전송
3. `Terminate` → 루프 종료
4. 예외 발생 → `ErrorResponse` 전송, 트랜잭션 상태에 따라 `ReadyForQuery` 전송

**트랜잭션 에러 상태 관리:**

`ConnectionHandler`는 `txnFailed: Boolean` 플래그로 트랜잭션 에러 상태를 추적한다.
BEGIN 이후 에러가 발생하면 `txnFailed = true`로 설정하고, 이후 ROLLBACK 외의
명령은 `ErrorResponse("current transaction is aborted")` + `ReadyForQuery('E')`로
거부한다. ROLLBACK 성공 시 `txnFailed = false`로 복원한다.

#### ResultFormatter

`ExecuteResult`를 `PgMessage` 리스트로 변환한다.
`ReadyForQuery`는 포함하지 않는다 — `ConnectionHandler`가 트랜잭션 상태를 판단하여
직접 추가한다.

```kotlin
object ResultFormatter {
    fun format(result: ExecuteResult): List<PgMessage>
}
```

`ExecuteResult.Selected`의 경우:
- 컬럼명과 Gwanbase `DataType` → `ColumnDesc` (타입 OID 매핑)
- 각 행의 값 → 텍스트 문자열 변환 (null은 `null`로 유지 → DataRow에서 -1 길이)
- `RowDescription` + `DataRow` × N + `CommandComplete("SELECT N")`

`ExecuteResult.Explained`의 경우:
- 단일 TEXT 컬럼 `QUERY PLAN`의 `RowDescription`
- `planText`를 줄 단위로 분리, 각 줄이 하나의 `DataRow`
- `CommandComplete("EXPLAIN")`

#### GwanServer

```kotlin
class GwanServer(
    private val database: Database,
    private val port: Int = 5432,
) : AutoCloseable {
    private lateinit var serverSocket: ServerSocket
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        serverSocket = ServerSocket(port)
        executor.submit {
            while (!serverSocket.isClosed) {
                val socket = serverSocket.accept()
                connections.add(socket)
                executor.submit(ConnectionHandler(socket, database))
            }
        }
    }

    fun stop() {
        serverSocket.close()
        connections.forEach { it.close() }
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    override fun close() = stop()
}
```

`connections` 집합으로 활성 소켓을 추적하여 shutdown 시 모든 연결을 정리한다.

### 4. 패키지 구조

```
core/src/main/kotlin/gwanbase/server/
├── GwanServer.kt          ← TCP 서버, accept 루프, shutdown
├── ConnectionHandler.kt   ← 연결 라이프사이클, Startup/Query/Terminate
├── PgMessage.kt           ← 메시지 sealed class + ColumnDesc
├── PgMessageReader.kt     ← InputStream → PgMessage 파싱
├── PgMessageWriter.kt     ← PgMessage → OutputStream 직렬화
└── ResultFormatter.kt     ← ExecuteResult → PgMessage 리스트 변환
```

## 제약 사항 및 트레이드오프

| 결정 | 이유 | 대안 |
|------|------|------|
| trust 인증 (AuthOk 고정) | MVP 단순화. 학습 초점은 프로토콜 자체 | MD5/SCRAM 인증 추가 |
| Simple Query만 | Prepared Statement 없이도 psql/JDBC 기본 동작 충분 | Extended Query는 고도화에서 |
| 텍스트 포맷만 | 바이너리 포맷은 타입별 인코더 필요 | 성능 필요 시 바이너리 추가 |
| Thread-per-connection | PostgreSQL과 동일 모델, DatabaseSession과 1:1 자연스러움 | NIO/Netty event loop |
| CachedThreadPool | 연결 수 제한 없이 단순 시작 | FixedThreadPool + maxConnections 설정 |
| 타입 OID 하드코딩 | 지원 타입이 4개뿐 | Catalog에 OID 매핑 테이블 |
| Cancel 미지원 | BackendKeyData 형식만 전송, cancel signal 무시 | 이후 cancel 구현 |
| SSL 미지원 | MVP에서 보안 불필요 | StartupMessage에서 SSLRequest 감지 → 거부 응답 |

### SSLRequest 처리

PostgreSQL 클라이언트는 StartupMessage 전에 SSLRequest(길이=8, 코드=80877103)를
보낼 수 있다. 이를 감지하면 단일 바이트 'N' (SSL 거부)을 전송하고 일반
StartupMessage를 기다린다.

## 테스트 시나리오

### PgMessageReader 테스트

- `StartupMessage 파싱 — version 3.0과 user/database 파라미터 추출`
- `Query 메시지 파싱 — null-terminated SQL 문자열 추출`
- `Terminate 메시지 파싱 — 페이로드 없는 메시지 처리`
- `SSLRequest 감지 — 길이 8, 코드 80877103 식별`

### PgMessageWriter 테스트

- `AuthenticationOk 직렬화 — R 타입, 길이 8, 상태 0`
- `RowDescription 직렬화 — 컬럼 메타데이터 바이트 레벨 검증`
- `DataRow 직렬화 — 텍스트 값과 NULL(-1 길이) 인코딩`
- `ErrorResponse 직렬화 — severity/message/code 필드 + 0x00 종료`
- `CommandComplete 직렬화 — null-terminated 태그 문자열`

### ResultFormatter 테스트

- `Selected → RowDescription + DataRow + CommandComplete 생성`
- `Inserted → CommandComplete "INSERT 0 1"`
- `Updated → CommandComplete "UPDATE N"`
- `Deleted → CommandComplete "DELETE N"`
- `Created → CommandComplete "CREATE TABLE"`
- `Explained → QUERY PLAN 컬럼 + 줄별 DataRow`
- `NULL 값 포함 SELECT — DataRow에서 해당 컬럼 길이 -1`

### ConnectionHandler 통합 테스트

- `정상 흐름 — Startup → Query → Terminate 전체 라이프사이클`
- `SELECT — CREATE TABLE + INSERT 후 SELECT 결과 검증`
- `트랜잭션 — BEGIN → INSERT → COMMIT 후 데이터 영속 확인`
- `에러 복구 — 잘못된 SQL 후에도 다음 쿼리 정상 실행`
- `트랜잭션 에러 상태 — BEGIN → 에러 → ROLLBACK 외 명령 거부 → ROLLBACK 후 정상`
- `SSLRequest → 'N' 응답 후 정상 Startup 진행`
- `클라이언트 갑작스런 연결 종료 — 세션 정리, 트랜잭션 롤백`

### E2E 테스트 (JDBC)

- `JDBC 연결 — DriverManager.getConnection 성공`
- `DDL/DML — CREATE TABLE, INSERT, SELECT, UPDATE, DELETE`
- `트랜잭션 — setAutoCommit(false), commit(), rollback()`
- `다중 연결 — 동시 2개 연결에서 독립 트랜잭션 격리 확인`
- `대량 INSERT — 100건 삽입 후 SELECT COUNT 검증`

## 벤치마크 목표

Phase 8 MVP에서는 정확성 우선. 성능 기준선 측정만 수행한다:

- `pgbench -i -s 1` (초기화: CREATE TABLE + INSERT)이 정상 동작
- `pgbench -c 1 -t 100` (단일 연결 100 트랜잭션) 완주
- `pgbench -c 4 -t 100` (4 연결 동시) 완주 + 데드락 없음
- 이후 고도화에서 TPS 수치 개선 목표 설정

## 참고 자료

- [PostgreSQL Frontend/Backend Protocol](https://www.postgresql.org/docs/current/protocol.html)
- [Message Flow](https://www.postgresql.org/docs/current/protocol-flow.html)
- [Message Formats](https://www.postgresql.org/docs/current/protocol-message-formats.html)
- CMU 15-445 Networking Layer 강의
- toydb, bustub 네트워크 레이어 참고
