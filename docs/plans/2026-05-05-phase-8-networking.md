# Phase 8: Networking & Client Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PostgreSQL Wire Protocol (v3.0) Simple Query 서브셋을 구현하여 `psql`/JDBC로 Gwanbase에 접속해 SQL을 실행할 수 있게 한다.

**Architecture:** GwanServer(ServerSocket accept 루프) → ConnectionHandler(연결당 1 스레드) → PgMessageReader/Writer(바이트 파싱/직렬화) → DatabaseSession(기존 SQL 실행) → ResultFormatter(ExecuteResult → PG 메시지 변환). Thread-per-connection 모델.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions, java.net.ServerSocket, PostgreSQL JDBC Driver (테스트용)

---

## 파일 맵

| 파일 | 역할 | 변경 유형 |
|------|------|-----------|
| `core/build.gradle.kts` | PostgreSQL JDBC 테스트 의존성 추가 | 수정 |
| `core/src/main/kotlin/gwanbase/server/PgMessage.kt` | PG 메시지 sealed class + ColumnDesc | 생성 |
| `core/src/main/kotlin/gwanbase/server/PgMessageWriter.kt` | PgMessage → OutputStream 직렬화 | 생성 |
| `core/src/main/kotlin/gwanbase/server/PgMessageReader.kt` | InputStream → PgMessage 파싱 | 생성 |
| `core/src/main/kotlin/gwanbase/server/ResultFormatter.kt` | ExecuteResult → PgMessage 리스트 변환 | 생성 |
| `core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt` | 연결 라이프사이클 (Startup/Query/Terminate) | 생성 |
| `core/src/main/kotlin/gwanbase/server/GwanServer.kt` | TCP 서버, accept 루프, shutdown | 생성 |
| `core/src/test/kotlin/gwanbase/server/PgMessageWriterTest.kt` | Writer 직렬화 바이트 레벨 검증 | 생성 |
| `core/src/test/kotlin/gwanbase/server/PgMessageReaderTest.kt` | Reader 파싱 검증 | 생성 |
| `core/src/test/kotlin/gwanbase/server/ResultFormatterTest.kt` | ExecuteResult → PgMessage 변환 검증 | 생성 |
| `core/src/test/kotlin/gwanbase/server/ConnectionHandlerTest.kt` | 소켓 기반 통합 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/server/GwanServerJdbcTest.kt` | JDBC E2E 테스트 | 생성 |

---

### Task 1: PgMessage sealed class + ColumnDesc

**Files:**
- Create: `core/src/main/kotlin/gwanbase/server/PgMessage.kt`

이 태스크는 테스트가 필요하지 않은 순수 데이터 클래스 정의이다. 이후 태스크의 Reader/Writer 테스트에서 검증한다.

- [ ] **Step 1: PgMessage sealed class 작성**

`core/src/main/kotlin/gwanbase/server/PgMessage.kt`:

```kotlin
package gwanbase.server

/**
 * PostgreSQL Wire Protocol v3.0 메시지.
 *
 * Simple Query 프로토콜에 필요한 메시지만 정의한다.
 */
sealed class PgMessage {
    // ── Client → Server ──

    /** Startup 메시지. 타입 바이트 없이 길이로 시작하는 특수 메시지. */
    data class StartupMessage(val version: Int, val params: Map<String, String>) : PgMessage()

    /** SQL 쿼리 메시지. */
    data class Query(val sql: String) : PgMessage()

    /** 연결 종료 메시지. */
    data object Terminate : PgMessage()

    // ── Server → Client ──

    /** 인증 성공. */
    data object AuthenticationOk : PgMessage()

    /** 서버 파라미터. */
    data class ParameterStatus(val name: String, val value: String) : PgMessage()

    /** 백엔드 키 데이터 (cancel용, MVP에서는 형식만). */
    data class BackendKeyData(val pid: Int, val secretKey: Int) : PgMessage()

    /** 쿼리 처리 완료, 다음 쿼리 대기. */
    data class ReadyForQuery(val txnStatus: Char) : PgMessage()

    /** SELECT 결과의 컬럼 메타데이터. */
    data class RowDescription(val columns: List<ColumnDesc>) : PgMessage()

    /** SELECT 결과의 한 행. null이면 SQL NULL. */
    data class DataRow(val values: List<String?>) : PgMessage()

    /** 명령 실행 완료 태그. */
    data class CommandComplete(val tag: String) : PgMessage()

    /** 에러 응답. */
    data class ErrorResponse(val severity: String, val message: String, val code: String) : PgMessage()
}

/**
 * RowDescription의 컬럼 디스크립터.
 *
 * PostgreSQL 프로토콜에서 정의하는 필드를 그대로 포함한다.
 * MVP에서 tableOid, columnAttr, typeMod는 고정값을 사용한다.
 */
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

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/PgMessage.kt
git commit -m "[Phase 8] PgMessage sealed class 및 ColumnDesc 정의"
```

---

### Task 2: PgMessageWriter — 직렬화

**Files:**
- Create: `core/src/test/kotlin/gwanbase/server/PgMessageWriterTest.kt`
- Create: `core/src/main/kotlin/gwanbase/server/PgMessageWriter.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/server/PgMessageWriterTest.kt`:

```kotlin
package gwanbase.server

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PgMessageWriterTest {

    private fun write(msg: PgMessage): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = PgMessageWriter(baos)
        writer.write(msg)
        writer.flush()
        return baos.toByteArray()
    }

    private fun ByteArray.readInt32(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt()

    private fun ByteArray.readInt16(offset: Int): Short =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort()

    @Test
    fun `AuthenticationOk 직렬화 — R 타입, 길이 8, 상태 0`() {
        val bytes = write(PgMessage.AuthenticationOk)
        bytes[0] shouldBe 'R'.code.toByte()
        bytes.readInt32(1) shouldBe 8       // 길이 (자신 포함)
        bytes.readInt32(5) shouldBe 0       // 상태 = 0 (인증 성공)
        bytes.size shouldBe 9
    }

    @Test
    fun `ReadyForQuery 직렬화 — Z 타입, 트랜잭션 상태 바이트`() {
        val bytes = write(PgMessage.ReadyForQuery('I'))
        bytes[0] shouldBe 'Z'.code.toByte()
        bytes.readInt32(1) shouldBe 5       // 길이
        bytes[5] shouldBe 'I'.code.toByte()
        bytes.size shouldBe 6
    }

    @Test
    fun `CommandComplete 직렬화 — null-terminated 태그`() {
        val bytes = write(PgMessage.CommandComplete("SELECT 3"))
        bytes[0] shouldBe 'C'.code.toByte()
        val tag = "SELECT 3"
        val expectedLen = 4 + tag.toByteArray(Charsets.UTF_8).size + 1  // 길이 + 문자열 + null
        bytes.readInt32(1) shouldBe expectedLen
        bytes[5 + tag.length] shouldBe 0    // null terminator
    }

    @Test
    fun `ParameterStatus 직렬화 — name과 value가 null-terminated`() {
        val bytes = write(PgMessage.ParameterStatus("server_version", "0.8.0"))
        bytes[0] shouldBe 'S'.code.toByte()
        val content = String(bytes, 5, bytes.size - 5, Charsets.UTF_8)
        content shouldBe "server_version\u0000" + "0.8.0\u0000"
    }

    @Test
    fun `BackendKeyData 직렬화 — pid와 secretKey`() {
        val bytes = write(PgMessage.BackendKeyData(pid = 42, secretKey = 0))
        bytes[0] shouldBe 'K'.code.toByte()
        bytes.readInt32(1) shouldBe 12      // 길이 = 4 + 4 + 4
        bytes.readInt32(5) shouldBe 42      // pid
        bytes.readInt32(9) shouldBe 0       // secret
        bytes.size shouldBe 13
    }

    @Test
    fun `RowDescription 직렬화 — 컬럼 메타데이터`() {
        val col = ColumnDesc(name = "id", typeOid = 23, typeSize = 4)
        val bytes = write(PgMessage.RowDescription(listOf(col)))
        bytes[0] shouldBe 'T'.code.toByte()
        bytes.readInt16(5) shouldBe 1       // 컬럼 수
        // "id" + null(3) + tableOid(4) + columnAttr(2) + typeOid(4) + typeSize(2) + typeMod(4) + formatCode(2)
        // = 3 + 4 + 2 + 4 + 2 + 4 + 2 = 21
        val expectedLen = 4 + 2 + 21        // 길이필드 + 컬럼수 + 컬럼데이터
        bytes.readInt32(1) shouldBe expectedLen
    }

    @Test
    fun `DataRow 직렬화 — 텍스트 값과 NULL`() {
        val bytes = write(PgMessage.DataRow(listOf("hello", null, "42")))
        bytes[0] shouldBe 'D'.code.toByte()
        bytes.readInt16(5) shouldBe 3       // 컬럼 수

        // 첫 번째 값: 길이 5 + "hello"
        var offset = 7
        bytes.readInt32(offset) shouldBe 5
        offset += 4
        String(bytes, offset, 5, Charsets.UTF_8) shouldBe "hello"
        offset += 5

        // 두 번째 값: NULL = -1
        bytes.readInt32(offset) shouldBe -1
        offset += 4

        // 세 번째 값: 길이 2 + "42"
        bytes.readInt32(offset) shouldBe 2
        offset += 4
        String(bytes, offset, 2, Charsets.UTF_8) shouldBe "42"
    }

    @Test
    fun `ErrorResponse 직렬화 — severity, message, code 필드`() {
        val bytes = write(PgMessage.ErrorResponse("ERROR", "table not found", "42P01"))
        bytes[0] shouldBe 'E'.code.toByte()
        val content = String(bytes, 5, bytes.size - 5, Charsets.UTF_8)
        // S + severity + \0 + V + severity + \0 + C + code + \0 + M + message + \0 + \0
        content.contains("ERROR") shouldBe true
        content.contains("42P01") shouldBe true
        content.contains("table not found") shouldBe true
        bytes.last() shouldBe 0  // 최종 null terminator
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.PgMessageWriterTest"`
Expected: FAIL — `PgMessageWriter` 클래스가 없음

- [ ] **Step 3: PgMessageWriter 구현**

`core/src/main/kotlin/gwanbase/server/PgMessageWriter.kt`:

```kotlin
package gwanbase.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * PgMessage를 PostgreSQL 바이너리 포맷으로 직렬화한다.
 *
 * 내부적으로 ByteArrayOutputStream에 페이로드를 먼저 조립한 뒤,
 * 타입 바이트 + 길이 + 페이로드 순서로 출력한다.
 */
class PgMessageWriter(private val output: OutputStream) {

    /** 메시지를 직렬화하여 출력 스트림에 쓴다. */
    fun write(msg: PgMessage) {
        when (msg) {
            is PgMessage.AuthenticationOk -> writeAuthenticationOk()
            is PgMessage.ParameterStatus -> writeParameterStatus(msg)
            is PgMessage.BackendKeyData -> writeBackendKeyData(msg)
            is PgMessage.ReadyForQuery -> writeReadyForQuery(msg)
            is PgMessage.RowDescription -> writeRowDescription(msg)
            is PgMessage.DataRow -> writeDataRow(msg)
            is PgMessage.CommandComplete -> writeCommandComplete(msg)
            is PgMessage.ErrorResponse -> writeErrorResponse(msg)
            else -> error("서버에서 전송할 수 없는 메시지 타입: ${msg::class.simpleName}")
        }
    }

    /** 버퍼를 플러시한다. */
    fun flush() {
        output.flush()
    }

    // ── 타입 바이트 + 길이 + 페이로드 조립 헬퍼 ──

    private fun writeMessage(type: Char, block: DataOutputStream.() -> Unit) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.block() }
        val payloadBytes = payload.toByteArray()
        output.write(type.code)
        writeInt32(payloadBytes.size + 4)  // 길이는 자신(4) 포함
        output.write(payloadBytes)
    }

    private fun writeInt32(value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun DataOutputStream.writeCString(s: String) {
        write(s.toByteArray(Charsets.UTF_8))
        write(0)
    }

    private fun DataOutputStream.writeInt16(value: Short) {
        writeShort(value.toInt())
    }

    // ── 메시지별 직렬화 ──

    private fun writeAuthenticationOk() {
        writeMessage('R') {
            writeInt(0)  // 상태 = 0 (인증 성공)
        }
    }

    private fun writeParameterStatus(msg: PgMessage.ParameterStatus) {
        writeMessage('S') {
            writeCString(msg.name)
            writeCString(msg.value)
        }
    }

    private fun writeBackendKeyData(msg: PgMessage.BackendKeyData) {
        writeMessage('K') {
            writeInt(msg.pid)
            writeInt(msg.secretKey)
        }
    }

    private fun writeReadyForQuery(msg: PgMessage.ReadyForQuery) {
        writeMessage('Z') {
            write(msg.txnStatus.code)
        }
    }

    private fun writeRowDescription(msg: PgMessage.RowDescription) {
        writeMessage('T') {
            writeInt16(msg.columns.size.toShort())
            for (col in msg.columns) {
                writeCString(col.name)
                writeInt(col.tableOid)
                writeInt16(col.columnAttr)
                writeInt(col.typeOid)
                writeInt16(col.typeSize)
                writeInt(col.typeMod)
                writeInt16(col.formatCode)
            }
        }
    }

    private fun writeDataRow(msg: PgMessage.DataRow) {
        writeMessage('D') {
            writeInt16(msg.values.size.toShort())
            for (value in msg.values) {
                if (value == null) {
                    writeInt(-1)
                } else {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    writeInt(bytes.size)
                    write(bytes)
                }
            }
        }
    }

    private fun writeCommandComplete(msg: PgMessage.CommandComplete) {
        writeMessage('C') {
            writeCString(msg.tag)
        }
    }

    private fun writeErrorResponse(msg: PgMessage.ErrorResponse) {
        writeMessage('E') {
            write('S'.code); writeCString(msg.severity)
            write('V'.code); writeCString(msg.severity)
            write('C'.code); writeCString(msg.code)
            write('M'.code); writeCString(msg.message)
            write(0)  // 필드 목록 종료
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.PgMessageWriterTest"`
Expected: 7 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/PgMessageWriter.kt \
        core/src/test/kotlin/gwanbase/server/PgMessageWriterTest.kt
git commit -m "[Phase 8] PgMessageWriter 직렬화 구현 및 테스트"
```

---

### Task 3: PgMessageReader — 파싱

**Files:**
- Create: `core/src/test/kotlin/gwanbase/server/PgMessageReaderTest.kt`
- Create: `core/src/main/kotlin/gwanbase/server/PgMessageReader.kt`

Writer에서 직렬화한 바이트를 Reader가 정확히 파싱하는지, 그리고 클라이언트 메시지(StartupMessage, Query, Terminate)를 파싱하는지 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/server/PgMessageReaderTest.kt`:

```kotlin
package gwanbase.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PgMessageReaderTest {

    /** StartupMessage 바이트를 수동 조립한다. */
    private fun buildStartupMessage(params: Map<String, String>): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeInt(196608)  // version 3.0 = 0x00030000
        for ((key, value) in params) {
            dos.write(key.toByteArray(Charsets.UTF_8)); dos.write(0)
            dos.write(value.toByteArray(Charsets.UTF_8)); dos.write(0)
        }
        dos.write(0)  // 파라미터 목록 종료
        val payloadBytes = payload.toByteArray()
        val result = ByteArrayOutputStream()
        DataOutputStream(result).writeInt(payloadBytes.size + 4)  // 길이 (자신 포함)
        result.write(payloadBytes)
        return result.toByteArray()
    }

    /** 일반 메시지 바이트를 수동 조립한다. */
    private fun buildMessage(type: Char, block: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.block() }
        val payloadBytes = payload.toByteArray()
        val result = ByteArrayOutputStream()
        result.write(type.code)
        DataOutputStream(result).writeInt(payloadBytes.size + 4)
        result.write(payloadBytes)
        return result.toByteArray()
    }

    @Test
    fun `StartupMessage 파싱 — version 3 dot 0과 파라미터 추출`() {
        val bytes = buildStartupMessage(mapOf("user" to "gwanbase", "database" to "testdb"))
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readStartupMessage()
        msg.shouldBeInstanceOf<PgMessage.StartupMessage>()
        msg.version shouldBe 196608
        msg.params["user"] shouldBe "gwanbase"
        msg.params["database"] shouldBe "testdb"
    }

    @Test
    fun `SSLRequest 감지 — 길이 8, 코드 80877103`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(8)         // 길이
        dos.writeInt(80877103)  // SSL request code
        val bytes = baos.toByteArray()
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val result = reader.readStartupMessage()
        // SSLRequest는 null을 반환하여 호출자가 'N' 응답 후 재시도하도록 한다
        result shouldBe null
    }

    @Test
    fun `Query 메시지 파싱 — null-terminated SQL 추출`() {
        val bytes = buildMessage('Q') {
            write("SELECT * FROM t".toByteArray(Charsets.UTF_8))
            write(0)
        }
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readMessage()
        msg.shouldBeInstanceOf<PgMessage.Query>()
        msg.sql shouldBe "SELECT * FROM t"
    }

    @Test
    fun `Terminate 메시지 파싱 — 페이로드 없음`() {
        val bytes = buildMessage('X') {}
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readMessage()
        msg.shouldBeInstanceOf<PgMessage.Terminate>()
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.PgMessageReaderTest"`
Expected: FAIL — `PgMessageReader` 클래스가 없음

- [ ] **Step 3: PgMessageReader 구현**

`core/src/main/kotlin/gwanbase/server/PgMessageReader.kt`:

```kotlin
package gwanbase.server

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * InputStream에서 PostgreSQL 바이너리 메시지를 파싱한다.
 *
 * [readStartupMessage]는 Startup Phase에서 사용하며 타입 바이트가 없는 특수 형식이다.
 * [readMessage]는 Query Phase에서 사용하며 타입(1) + 길이(4) + 페이로드 형식이다.
 */
class PgMessageReader(input: InputStream) {

    private val din = DataInputStream(input)

    /**
     * Startup 메시지를 읽는다.
     *
     * SSLRequest(길이=8, 코드=80877103)를 감지하면 null을 반환한다.
     * 호출자는 null을 받으면 'N'을 전송하고 다시 readStartupMessage()를 호출한다.
     *
     * @return StartupMessage 또는 SSLRequest일 경우 null
     * @throws EOFException 스트림이 끝난 경우
     */
    fun readStartupMessage(): PgMessage.StartupMessage? {
        val length = din.readInt()
        val payloadSize = length - 4
        val payload = ByteArray(payloadSize)
        din.readFully(payload)

        // SSLRequest 감지
        if (length == 8) {
            val code = ((payload[0].toInt() and 0xFF) shl 24) or
                    ((payload[1].toInt() and 0xFF) shl 16) or
                    ((payload[2].toInt() and 0xFF) shl 8) or
                    (payload[3].toInt() and 0xFF)
            if (code == 80877103) return null
        }

        // version (처음 4바이트)
        val version = ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)

        // 파라미터 파싱 (null-terminated key-value 쌍, 최종 0x00으로 종료)
        val params = mutableMapOf<String, String>()
        var offset = 4
        while (offset < payloadSize) {
            val key = readCString(payload, offset)
            if (key.isEmpty()) break
            offset += key.toByteArray(Charsets.UTF_8).size + 1
            val value = readCString(payload, offset)
            offset += value.toByteArray(Charsets.UTF_8).size + 1
            params[key] = value
        }

        return PgMessage.StartupMessage(version, params)
    }

    /**
     * 일반 메시지를 읽는다 (타입 1바이트 + 길이 4바이트 + 페이로드).
     *
     * @return 파싱된 PgMessage
     * @throws EOFException 스트림이 끝난 경우
     */
    fun readMessage(): PgMessage {
        val type = din.readByte().toInt().toChar()
        val length = din.readInt()
        val payloadSize = length - 4
        val payload = if (payloadSize > 0) {
            ByteArray(payloadSize).also { din.readFully(it) }
        } else {
            ByteArray(0)
        }

        return when (type) {
            'Q' -> {
                val sql = readCString(payload, 0)
                PgMessage.Query(sql)
            }
            'X' -> PgMessage.Terminate
            else -> error("지원하지 않는 메시지 타입: '$type' (0x${type.code.toString(16)})")
        }
    }

    private fun readCString(data: ByteArray, offset: Int): String {
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.PgMessageReaderTest"`
Expected: 4 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/PgMessageReader.kt \
        core/src/test/kotlin/gwanbase/server/PgMessageReaderTest.kt
git commit -m "[Phase 8] PgMessageReader 파싱 구현 및 테스트"
```

---

### Task 4: ResultFormatter — ExecuteResult → PgMessage 변환

**Files:**
- Create: `core/src/test/kotlin/gwanbase/server/ResultFormatterTest.kt`
- Create: `core/src/main/kotlin/gwanbase/server/ResultFormatter.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/server/ResultFormatterTest.kt`:

```kotlin
package gwanbase.server

import gwanbase.sql.ExecuteResult
import gwanbase.table.RID
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ResultFormatterTest {

    @Test
    fun `Selected — RowDescription, DataRow, CommandComplete 생성`() {
        val result = ExecuteResult.Selected(
            columns = listOf("id", "name"),
            rows = listOf(
                listOf(1, "alice"),
                listOf(2, "bob"),
            ),
        )
        val messages = ResultFormatter.format(result)

        messages[0].shouldBeInstanceOf<PgMessage.RowDescription>()
        val rd = messages[0] as PgMessage.RowDescription
        rd.columns.size shouldBe 2
        rd.columns[0].name shouldBe "id"
        rd.columns[1].name shouldBe "name"

        messages[1].shouldBeInstanceOf<PgMessage.DataRow>()
        (messages[1] as PgMessage.DataRow).values shouldBe listOf("1", "alice")

        messages[2].shouldBeInstanceOf<PgMessage.DataRow>()
        (messages[2] as PgMessage.DataRow).values shouldBe listOf("2", "bob")

        messages[3].shouldBeInstanceOf<PgMessage.CommandComplete>()
        (messages[3] as PgMessage.CommandComplete).tag shouldBe "SELECT 2"
    }

    @Test
    fun `Selected — NULL 값은 null로 변환`() {
        val result = ExecuteResult.Selected(
            columns = listOf("id", "name"),
            rows = listOf(listOf(1, null)),
        )
        val messages = ResultFormatter.format(result)
        val dataRow = messages[1] as PgMessage.DataRow
        dataRow.values shouldBe listOf("1", null)
    }

    @Test
    fun `Selected — 빈 결과`() {
        val result = ExecuteResult.Selected(columns = listOf("id"), rows = emptyList())
        val messages = ResultFormatter.format(result)
        messages.size shouldBe 2  // RowDescription + CommandComplete
        messages[0].shouldBeInstanceOf<PgMessage.RowDescription>()
        (messages[1] as PgMessage.CommandComplete).tag shouldBe "SELECT 0"
    }

    @Test
    fun `Inserted — INSERT 0 1 태그`() {
        val result = ExecuteResult.Inserted(RID(1, 0))
        val messages = ResultFormatter.format(result)
        messages.size shouldBe 1
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "INSERT 0 1"
    }

    @Test
    fun `Updated — UPDATE N 태그`() {
        val result = ExecuteResult.Updated(5)
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "UPDATE 5"
    }

    @Test
    fun `Deleted — DELETE N 태그`() {
        val result = ExecuteResult.Deleted(3)
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "DELETE 3"
    }

    @Test
    fun `Created — CREATE TABLE 태그`() {
        val result = ExecuteResult.Created("users")
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "CREATE TABLE"
    }

    @Test
    fun `Dropped — DROP TABLE 태그`() {
        val result = ExecuteResult.Dropped("users")
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "DROP TABLE"
    }

    @Test
    fun `TransactionStarted — BEGIN 태그`() {
        val messages = ResultFormatter.format(ExecuteResult.TransactionStarted)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "BEGIN"
    }

    @Test
    fun `TransactionCommitted — COMMIT 태그`() {
        val messages = ResultFormatter.format(ExecuteResult.TransactionCommitted)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "COMMIT"
    }

    @Test
    fun `TransactionRolledBack — ROLLBACK 태그`() {
        val messages = ResultFormatter.format(ExecuteResult.TransactionRolledBack)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "ROLLBACK"
    }

    @Test
    fun `IndexCreated — CREATE INDEX 태그`() {
        val result = ExecuteResult.IndexCreated("idx_id")
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "CREATE INDEX"
    }

    @Test
    fun `IndexDropped — DROP INDEX 태그`() {
        val result = ExecuteResult.IndexDropped("idx_id")
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "DROP INDEX"
    }

    @Test
    fun `Analyzed — ANALYZE 태그`() {
        val result = ExecuteResult.Analyzed("users", 100)
        val messages = ResultFormatter.format(result)
        (messages[0] as PgMessage.CommandComplete).tag shouldBe "ANALYZE"
    }

    @Test
    fun `Explained — QUERY PLAN 컬럼 + 줄별 DataRow`() {
        val result = ExecuteResult.Explained("SeqScan(t)\n  Filter(id > 1)")
        val messages = ResultFormatter.format(result)

        val rd = messages[0] as PgMessage.RowDescription
        rd.columns.size shouldBe 1
        rd.columns[0].name shouldBe "QUERY PLAN"

        (messages[1] as PgMessage.DataRow).values shouldBe listOf("SeqScan(t)")
        (messages[2] as PgMessage.DataRow).values shouldBe listOf("  Filter(id > 1)")

        (messages[3] as PgMessage.CommandComplete).tag shouldBe "EXPLAIN"
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.ResultFormatterTest"`
Expected: FAIL — `ResultFormatter` 클래스가 없음

- [ ] **Step 3: ResultFormatter 구현**

`core/src/main/kotlin/gwanbase/server/ResultFormatter.kt`:

```kotlin
package gwanbase.server

import gwanbase.sql.ExecuteResult

/**
 * ExecuteResult를 PostgreSQL 응답 메시지 리스트로 변환한다.
 *
 * ReadyForQuery는 포함하지 않는다 — ConnectionHandler가
 * 트랜잭션 상태를 판단하여 직접 추가한다.
 */
object ResultFormatter {

    /** 텍스트 타입 OID (모든 컬럼에 사용). */
    private const val TEXT_OID = 25
    private const val TEXT_SIZE: Short = -1

    /**
     * ExecuteResult를 PgMessage 리스트로 변환한다.
     */
    fun format(result: ExecuteResult): List<PgMessage> {
        return when (result) {
            is ExecuteResult.Selected -> formatSelected(result)
            is ExecuteResult.Inserted -> listOf(PgMessage.CommandComplete("INSERT 0 1"))
            is ExecuteResult.Updated -> listOf(PgMessage.CommandComplete("UPDATE ${result.count}"))
            is ExecuteResult.Deleted -> listOf(PgMessage.CommandComplete("DELETE ${result.count}"))
            is ExecuteResult.Created -> listOf(PgMessage.CommandComplete("CREATE TABLE"))
            is ExecuteResult.Dropped -> listOf(PgMessage.CommandComplete("DROP TABLE"))
            is ExecuteResult.TransactionStarted -> listOf(PgMessage.CommandComplete("BEGIN"))
            is ExecuteResult.TransactionCommitted -> listOf(PgMessage.CommandComplete("COMMIT"))
            is ExecuteResult.TransactionRolledBack -> listOf(PgMessage.CommandComplete("ROLLBACK"))
            is ExecuteResult.IndexCreated -> listOf(PgMessage.CommandComplete("CREATE INDEX"))
            is ExecuteResult.IndexDropped -> listOf(PgMessage.CommandComplete("DROP INDEX"))
            is ExecuteResult.Analyzed -> listOf(PgMessage.CommandComplete("ANALYZE"))
            is ExecuteResult.Explained -> formatExplained(result)
        }
    }

    private fun formatSelected(result: ExecuteResult.Selected): List<PgMessage> {
        val columns = result.columns.map { name ->
            ColumnDesc(name = name, typeOid = TEXT_OID, typeSize = TEXT_SIZE)
        }
        val messages = mutableListOf<PgMessage>()
        messages.add(PgMessage.RowDescription(columns))
        for (row in result.rows) {
            val values = row.map { it?.toString() }
            messages.add(PgMessage.DataRow(values))
        }
        messages.add(PgMessage.CommandComplete("SELECT ${result.rows.size}"))
        return messages
    }

    private fun formatExplained(result: ExecuteResult.Explained): List<PgMessage> {
        val columns = listOf(ColumnDesc(name = "QUERY PLAN", typeOid = TEXT_OID, typeSize = TEXT_SIZE))
        val messages = mutableListOf<PgMessage>()
        messages.add(PgMessage.RowDescription(columns))
        for (line in result.planText.split("\n")) {
            messages.add(PgMessage.DataRow(listOf(line)))
        }
        messages.add(PgMessage.CommandComplete("EXPLAIN"))
        return messages
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.ResultFormatterTest"`
Expected: 14 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/ResultFormatter.kt \
        core/src/test/kotlin/gwanbase/server/ResultFormatterTest.kt
git commit -m "[Phase 8] ResultFormatter 구현 및 테스트"
```

---

### Task 5: ConnectionHandler — 연결 라이프사이클

**Files:**
- Create: `core/src/test/kotlin/gwanbase/server/ConnectionHandlerTest.kt`
- Create: `core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt`

로컬 소켓 쌍을 사용하여 ConnectionHandler의 Startup → Query → Terminate 흐름을 통합 테스트한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/server/ConnectionHandlerTest.kt`:

```kotlin
package gwanbase.server

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path

class ConnectionHandlerTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    /**
     * 로컬 ServerSocket으로 ConnectionHandler를 실행하고,
     * 클라이언트 소켓을 반환한다.
     */
    private fun startHandler(): Pair<Socket, Thread> {
        val serverSocket = ServerSocket(0)  // 랜덤 포트
        val port = serverSocket.localPort
        val thread = Thread {
            val accepted = serverSocket.accept()
            serverSocket.close()
            ConnectionHandler(accepted, db).run()
        }
        thread.isDaemon = true
        thread.start()
        val client = Socket("localhost", port)
        return client to thread
    }

    /** StartupMessage 바이트를 조립하여 전송한다. */
    private fun sendStartup(out: java.io.OutputStream) {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeInt(196608)  // version 3.0
        dos.write("user".toByteArray()); dos.write(0)
        dos.write("gwanbase".toByteArray()); dos.write(0)
        dos.write(0)
        val payloadBytes = payload.toByteArray()
        DataOutputStream(out).writeInt(payloadBytes.size + 4)
        out.write(payloadBytes)
        out.flush()
    }

    /** Query 메시지를 전송한다. */
    private fun sendQuery(out: java.io.OutputStream, sql: String) {
        val sqlBytes = sql.toByteArray(Charsets.UTF_8)
        out.write('Q'.code)
        DataOutputStream(out).writeInt(sqlBytes.size + 4 + 1)  // 길이 + null
        out.write(sqlBytes)
        out.write(0)
        out.flush()
    }

    /** Terminate 메시지를 전송한다. */
    private fun sendTerminate(out: java.io.OutputStream) {
        out.write('X'.code)
        DataOutputStream(out).writeInt(4)
        out.flush()
    }

    /** 서버 응답에서 모든 메시지를 ReadyForQuery까지 읽는다. */
    private fun readUntilReady(reader: PgMessageReader): List<PgMessage> {
        val messages = mutableListOf<PgMessage>()
        while (true) {
            val msg = reader.readMessage()
            messages.add(msg)
            if (msg is PgMessage.ReadyForQuery) break
        }
        return messages
    }

    @Test
    fun `정상 흐름 — Startup부터 Terminate까지`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)

            // Startup 응답: AuthOk, ParameterStatus들, BackendKeyData, ReadyForQuery
            val startupMsgs = readUntilReady(reader)
            startupMsgs.first().shouldBeInstanceOf<PgMessage.AuthenticationOk>()
            startupMsgs.last().shouldBeInstanceOf<PgMessage.ReadyForQuery>()
            (startupMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `SELECT — CREATE TABLE 후 INSERT 후 SELECT`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            // CREATE TABLE
            sendQuery(out, "CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
            val createMsgs = readUntilReady(reader)
            createMsgs.any { it is PgMessage.CommandComplete } shouldBe true

            // INSERT
            sendQuery(out, "INSERT INTO t (id, name) VALUES (1, 'alice')")
            readUntilReady(reader)

            // SELECT
            sendQuery(out, "SELECT * FROM t")
            val selectMsgs = readUntilReady(reader)
            selectMsgs.any { it is PgMessage.RowDescription } shouldBe true
            val dataRows = selectMsgs.filterIsInstance<PgMessage.DataRow>()
            dataRows.size shouldBe 1

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `트랜잭션 — BEGIN, INSERT, COMMIT`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            readUntilReady(reader)

            sendQuery(out, "BEGIN")
            val beginMsgs = readUntilReady(reader)
            (beginMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'T'

            sendQuery(out, "INSERT INTO t (id) VALUES (1)")
            val insertMsgs = readUntilReady(reader)
            (insertMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'T'

            sendQuery(out, "COMMIT")
            val commitMsgs = readUntilReady(reader)
            (commitMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `에러 복구 — 잘못된 SQL 후에도 세션 유지`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            // 잘못된 SQL
            sendQuery(out, "SELECT * FROM nonexistent")
            val errorMsgs = readUntilReady(reader)
            errorMsgs.any { it is PgMessage.ErrorResponse } shouldBe true
            (errorMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            // 이후 정상 SQL
            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            val createMsgs = readUntilReady(reader)
            createMsgs.any { it is PgMessage.CommandComplete } shouldBe true

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `트랜잭션 에러 상태 — ROLLBACK 전까지 명령 거부`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            readUntilReady(reader)

            sendQuery(out, "BEGIN")
            readUntilReady(reader)

            // 에러 유발
            sendQuery(out, "SELECT * FROM nonexistent")
            val errorMsgs = readUntilReady(reader)
            (errorMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'E'

            // ROLLBACK 외 명령 거부
            sendQuery(out, "SELECT * FROM t")
            val rejectedMsgs = readUntilReady(reader)
            rejectedMsgs.any { it is PgMessage.ErrorResponse } shouldBe true
            (rejectedMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'E'

            // ROLLBACK 성공
            sendQuery(out, "ROLLBACK")
            val rollbackMsgs = readUntilReady(reader)
            (rollbackMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.ConnectionHandlerTest"`
Expected: FAIL — `ConnectionHandler` 클래스가 없음

- [ ] **Step 3: ConnectionHandler 구현**

`core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt`:

```kotlin
package gwanbase.server

import gwanbase.table.Database
import mu.KotlinLogging
import java.io.EOFException
import java.net.Socket
import java.net.SocketException

private val logger = KotlinLogging.logger {}

/**
 * 단일 클라이언트 연결의 라이프사이클을 관리한다.
 *
 * Startup → Query Loop → Terminate 순서로 진행하며,
 * 연결당 하나의 DatabaseSession을 바인딩한다.
 */
class ConnectionHandler(
    private val socket: Socket,
    private val database: Database,
) : Runnable {

    private var inTransaction = false
    private var txnFailed = false

    override fun run() {
        try {
            socket.use { sock ->
                val reader = PgMessageReader(sock.getInputStream())
                val writer = PgMessageWriter(sock.getOutputStream())

                if (!handleStartup(reader, writer)) return

                database.createSession().use { session ->
                    queryLoop(session, reader, writer)
                }
            }
        } catch (e: EOFException) {
            logger.debug { "클라이언트 연결 종료 (EOF)" }
        } catch (e: SocketException) {
            logger.debug { "클라이언트 연결 종료: ${e.message}" }
        } catch (e: Exception) {
            logger.error(e) { "ConnectionHandler 오류" }
        }
    }

    /**
     * Startup Phase를 처리한다.
     *
     * SSLRequest를 받으면 'N'으로 거부하고 재시도한다.
     *
     * @return true이면 정상 진행, false이면 연결 종료
     */
    private fun handleStartup(reader: PgMessageReader, writer: PgMessageWriter): Boolean {
        // SSLRequest 처리 루프
        var startup = reader.readStartupMessage()
        while (startup == null) {
            // SSLRequest → 'N' 응답
            socket.getOutputStream().write('N'.code)
            socket.getOutputStream().flush()
            startup = reader.readStartupMessage()
        }

        // 인증 성공 (trust 모드)
        writer.write(PgMessage.AuthenticationOk)

        // 서버 파라미터
        writer.write(PgMessage.ParameterStatus("server_version", "0.8.0"))
        writer.write(PgMessage.ParameterStatus("server_encoding", "UTF8"))
        writer.write(PgMessage.ParameterStatus("client_encoding", "UTF8"))

        // BackendKeyData (형식만)
        writer.write(PgMessage.BackendKeyData(
            pid = Thread.currentThread().id.toInt(),
            secretKey = 0,
        ))

        // Ready
        writer.write(PgMessage.ReadyForQuery('I'))
        writer.flush()
        return true
    }

    private fun queryLoop(
        session: gwanbase.txn.DatabaseSession,
        reader: PgMessageReader,
        writer: PgMessageWriter,
    ) {
        while (true) {
            val msg = reader.readMessage()
            when (msg) {
                is PgMessage.Terminate -> return
                is PgMessage.Query -> handleQuery(msg.sql, session, writer)
                else -> {
                    writer.write(PgMessage.ErrorResponse("ERROR", "지원하지 않는 메시지", "XX000"))
                    writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
                    writer.flush()
                }
            }
        }
    }

    private fun handleQuery(
        sql: String,
        session: gwanbase.txn.DatabaseSession,
        writer: PgMessageWriter,
    ) {
        // 트랜잭션 에러 상태에서 ROLLBACK 외 명령 거부
        if (txnFailed) {
            val trimmed = sql.trim().uppercase()
            if (trimmed != "ROLLBACK") {
                writer.write(PgMessage.ErrorResponse(
                    "ERROR",
                    "current transaction is aborted, commands ignored until end of transaction block",
                    "25P02",
                ))
                writer.write(PgMessage.ReadyForQuery('E'))
                writer.flush()
                return
            }
        }

        try {
            val result = session.executeSql(sql)
            updateTxnState(sql, failed = false)

            val messages = ResultFormatter.format(result)
            for (m in messages) {
                writer.write(m)
            }
            writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
            writer.flush()
        } catch (e: Exception) {
            updateTxnState(sql, failed = true)

            writer.write(PgMessage.ErrorResponse(
                severity = "ERROR",
                message = e.message ?: "내부 오류",
                code = "XX000",
            ))
            writer.write(PgMessage.ReadyForQuery(currentTxnStatus()))
            writer.flush()
        }
    }

    private fun updateTxnState(sql: String, failed: Boolean) {
        val trimmed = sql.trim().uppercase()
        when {
            trimmed == "BEGIN" && !failed -> {
                inTransaction = true
                txnFailed = false
            }
            (trimmed == "COMMIT" || trimmed == "ROLLBACK") && !failed -> {
                inTransaction = false
                txnFailed = false
            }
            failed && inTransaction -> {
                txnFailed = true
            }
        }
    }

    private fun currentTxnStatus(): Char = when {
        txnFailed -> 'E'
        inTransaction -> 'T'
        else -> 'I'
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.server.ConnectionHandlerTest"`
Expected: 5 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt \
        core/src/test/kotlin/gwanbase/server/ConnectionHandlerTest.kt
git commit -m "[Phase 8] ConnectionHandler 연결 라이프사이클 구현 및 통합 테스트"
```

---

### Task 6: GwanServer — TCP 서버

**Files:**
- Create: `core/src/main/kotlin/gwanbase/server/GwanServer.kt`

GwanServer는 ConnectionHandler 통합 테스트에서 이미 간접 검증되므로, 여기서는 구현 + 컴파일 확인만 수행한다. JDBC E2E 테스트(Task 7)에서 전체 동작을 검증한다.

- [ ] **Step 1: GwanServer 구현**

`core/src/main/kotlin/gwanbase/server/GwanServer.kt`:

```kotlin
package gwanbase.server

import gwanbase.table.Database
import mu.KotlinLogging
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.Socket

private val logger = KotlinLogging.logger {}

/**
 * Gwanbase TCP 서버.
 *
 * PostgreSQL Wire Protocol v3.0 Simple Query를 지원한다.
 * Thread-per-connection 모델로 동작하며, 각 연결에 독립 DatabaseSession을 바인딩한다.
 *
 * @param database 대상 데이터베이스
 * @param port 리스닝 포트 (기본 5432)
 */
class GwanServer(
    private val database: Database,
    private val port: Int = 5432,
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    /**
     * 서버를 시작한다.
     *
     * 별도 스레드에서 accept 루프를 실행한다.
     */
    fun start() {
        val ss = ServerSocket(port)
        serverSocket = ss
        logger.info { "GwanServer 시작: port=$port" }

        executor.submit {
            try {
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    connections.add(socket)
                    logger.debug { "클라이언트 연결: ${socket.remoteSocketAddress}" }
                    executor.submit {
                        try {
                            ConnectionHandler(socket, database).run()
                        } finally {
                            connections.remove(socket)
                        }
                    }
                }
            } catch (e: SocketException) {
                if (!ss.isClosed) logger.error(e) { "Accept 루프 오류" }
            }
        }
    }

    /**
     * 서버를 종료한다.
     *
     * ServerSocket을 닫고, 모든 활성 연결을 정리하며,
     * 스레드 풀을 shutdown한다.
     */
    fun stop() {
        logger.info { "GwanServer 종료 중..." }
        serverSocket?.close()
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        logger.info { "GwanServer 종료 완료" }
    }

    override fun close() = stop()
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/server/GwanServer.kt
git commit -m "[Phase 8] GwanServer TCP 서버 구현"
```

---

### Task 7: JDBC E2E 테스트

**Files:**
- Modify: `core/build.gradle.kts` (PostgreSQL JDBC 드라이버 테스트 의존성)
- Create: `core/src/test/kotlin/gwanbase/server/GwanServerJdbcTest.kt`

`org.postgresql:postgresql` JDBC 드라이버로 실제 접속하여 DDL/DML/트랜잭션을 검증한다.

- [ ] **Step 1: build.gradle.kts에 JDBC 드라이버 추가**

`core/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    // ByteBuffer utilities
    implementation("io.netty:netty-buffer:4.1.104.Final")

    // PostgreSQL JDBC driver (E2E 테스트용)
    testImplementation("org.postgresql:postgresql:42.7.1")
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`core/src/test/kotlin/gwanbase/server/GwanServerJdbcTest.kt`:

```kotlin
package gwanbase.server

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class GwanServerJdbcTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database
    private lateinit var server: GwanServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
        // 랜덤 포트로 서버 시작
        port = java.net.ServerSocket(0).use { it.localPort }
        server = GwanServer(db, port)
        server.start()
        Thread.sleep(100)  // 서버 시작 대기
    }

    @AfterEach
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun connect(): java.sql.Connection {
        return DriverManager.getConnection(
            "jdbc:postgresql://localhost:$port/gwanbase?preferQueryMode=simple",
            "gwanbase",
            "",
        )
    }

    @Test
    fun `JDBC 연결 성공`() {
        connect().use { conn ->
            conn.isClosed shouldBe false
        }
    }

    @Test
    fun `DDL과 DML — CREATE TABLE, INSERT, SELECT`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INT NOT NULL, name VARCHAR(100))")
                stmt.execute("INSERT INTO users (id, name) VALUES (1, 'alice')")
                stmt.execute("INSERT INTO users (id, name) VALUES (2, 'bob')")

                val rs = stmt.executeQuery("SELECT * FROM users")
                val rows = mutableListOf<Pair<Int, String>>()
                while (rs.next()) {
                    rows.add(rs.getInt(1) to rs.getString(2))
                }
                rows.size shouldBe 2
                rows[0] shouldBe (1 to "alice")
                rows[1] shouldBe (2 to "bob")
            }
        }
    }

    @Test
    fun `UPDATE와 DELETE`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE t (id INT NOT NULL, val INT NOT NULL)")
                stmt.execute("INSERT INTO t (id, val) VALUES (1, 10)")
                stmt.execute("INSERT INTO t (id, val) VALUES (2, 20)")

                val updateCount = stmt.executeUpdate("UPDATE t SET val = 99 WHERE id = 1")
                updateCount shouldBe 1

                val deleteCount = stmt.executeUpdate("DELETE FROM t WHERE id = 2")
                deleteCount shouldBe 1

                val rs = stmt.executeQuery("SELECT * FROM t")
                rs.next() shouldBe true
                rs.getInt("id") shouldBe 1
                rs.getInt("val") shouldBe 99
                rs.next() shouldBe false
            }
        }
    }

    @Test
    fun `트랜잭션 — commit과 rollback`() {
        connect().use { conn ->
            conn.createStatement().execute("CREATE TABLE t (id INT NOT NULL)")
        }

        // COMMIT
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("BEGIN")
                stmt.execute("INSERT INTO t (id) VALUES (1)")
                stmt.execute("COMMIT")
            }
        }

        // 데이터 확인
        connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM t")
            rs.next() shouldBe true
            rs.getInt(1) shouldBe 1
        }

        // ROLLBACK
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("BEGIN")
                stmt.execute("INSERT INTO t (id) VALUES (2)")
                stmt.execute("ROLLBACK")
            }
        }

        // 롤백된 데이터 미존재 확인
        connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM t")
            rs.next() shouldBe true
            rs.getInt(1) shouldBe 1
            rs.next() shouldBe false
        }
    }

    @Test
    fun `다중 연결 — 독립 트랜잭션`() {
        connect().use { conn ->
            conn.createStatement().execute("CREATE TABLE t (id INT NOT NULL)")
        }

        val conn1 = connect()
        val conn2 = connect()
        try {
            conn1.createStatement().execute("INSERT INTO t (id) VALUES (1)")
            conn2.createStatement().execute("INSERT INTO t (id) VALUES (2)")

            val rs = connect().use { conn ->
                val r = conn.createStatement().executeQuery("SELECT * FROM t")
                val ids = mutableListOf<Int>()
                while (r.next()) ids.add(r.getInt(1))
                ids
            }
            rs.sorted() shouldBe listOf(1, 2)
        } finally {
            conn1.close()
            conn2.close()
        }
    }

    @Test
    fun `대량 INSERT — 100건 삽입 후 SELECT 검증`() {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
                for (i in 1..100) {
                    stmt.execute("INSERT INTO t (id, name) VALUES ($i, 'name_$i')")
                }
                val rs = stmt.executeQuery("SELECT * FROM t")
                var count = 0
                while (rs.next()) count++
                count shouldBe 100
            }
        }
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :core:test --tests "gwanbase.server.GwanServerJdbcTest"`
Expected: 6 tests PASS

JDBC 드라이버와 Gwanbase 서버 간 프로토콜 호환 문제가 발생할 수 있다.
이 경우 PgMessageReader/Writer/ConnectionHandler를 수정하여 해결한다.
일반적인 조정 포인트:
- JDBC가 보내는 추가 쿼리 (`SET extra_float_digits = 3` 등) → 에러 시 무시하거나 빈 결과 반환
- `executeUpdate()` 반환값 → CommandComplete 태그의 숫자 파싱

- [ ] **Step 4: 기존 테스트 회귀 확인**

Run: `./gradlew :core:test`
Expected: 전체 PASS (기존 Phase 1–7 테스트 회귀 없음)

- [ ] **Step 5: 커밋**

```bash
git add core/build.gradle.kts \
        core/src/test/kotlin/gwanbase/server/GwanServerJdbcTest.kt
git commit -m "[Phase 8] JDBC E2E 테스트 및 PostgreSQL 드라이버 의존성 추가"
```

---

### Task 8: CLAUDE.md 업데이트 + 스펙 문서 참조 정리

**Files:**
- Modify: `CLAUDE.md`

로드맵, 컴포넌트 테이블, 설계 가이드를 Phase 8 완료 상태로 업데이트한다.

- [ ] **Step 1: CLAUDE.md 로드맵 업데이트**

로드맵 섹션에서 Phase 8 상태를 변경한다:

```
Phase 8  Networking & Client Protocol    ⬜ 다음 작업
```
→
```
Phase 8  Networking & Client Protocol    ✅ 완료 (tag v0.8-networking)
```

- [ ] **Step 2: Phase 8 컴포넌트 테이블 추가**

Phase 7 컴포넌트 테이블 아래에 추가:

```markdown
### Phase 8 컴포넌트 (완료)

| 컴포넌트 | 상태 | 파일 |
|---|---|---|
| PgMessage | ✅ | `core/src/main/kotlin/gwanbase/server/PgMessage.kt` |
| PgMessageReader | ✅ | `core/src/main/kotlin/gwanbase/server/PgMessageReader.kt` |
| PgMessageWriter | ✅ | `core/src/main/kotlin/gwanbase/server/PgMessageWriter.kt` |
| ResultFormatter | ✅ | `core/src/main/kotlin/gwanbase/server/ResultFormatter.kt` |
| ConnectionHandler | ✅ | `core/src/main/kotlin/gwanbase/server/ConnectionHandler.kt` |
| GwanServer | ✅ | `core/src/main/kotlin/gwanbase/server/GwanServer.kt` |
```

- [ ] **Step 3: Phase 8 설계 가이드 추가**

Phase 7 설계 가이드 아래에 추가:

```markdown
### Phase 8 (완료)

상세 설계·트레이드오프·테스트 시나리오는 `docs/specs/phase-8-networking.md`
참조. 완료된 주요 결정 요약:

- PostgreSQL Wire Protocol v3.0 Simple Query 서브셋 구현
- Thread-per-connection 모델 (PostgreSQL과 동일)
- trust 인증 (AuthenticationOk 고정, 이후 MD5/SCRAM 추가 예정)
- 텍스트 포맷 결과 반환, 바이너리 포맷은 고도화에서 추가
- 고도화 로드맵은 `docs/specs/advanced.md` 참조
```

- [ ] **Step 4: 커밋**

```bash
git add CLAUDE.md
git commit -m "[Phase 8] CLAUDE.md 로드맵, 컴포넌트, 설계 가이드 업데이트"
```

- [ ] **Step 5: 태그 생성**

```bash
git tag v0.8-networking
```
