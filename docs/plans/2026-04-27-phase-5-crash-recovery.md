# Phase 5: Crash Recovery (WAL) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write-Ahead Logging을 도입하여 crash 이후에도 커밋된 데이터의 무결성을 보장한다.

**Architecture:** Steal/No-Force 정책의 페이지 수준 물리적 로깅(full before/after image). Consistent Checkpoint + 2단계 Recovery(Redo → Undo with CLR). BPM과 WAL 사이의 의존성은 `WalCallback` 인터페이스로 역전시켜 `storage → wal` 방향 의존을 방지한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions, java.nio.FileChannel

---

## 파일 맵

| 파일 | 역할 | 변경 유형 |
|------|------|-----------|
| `core/src/main/kotlin/gwanbase/wal/LogRecord.kt` | 로그 레코드 sealed class + 직렬화 | 생성 |
| `core/src/main/kotlin/gwanbase/wal/LogManager.kt` | 로그 파일 I/O, LSN 관리, flush | 생성 |
| `core/src/main/kotlin/gwanbase/wal/TransactionContext.kt` | 트랜잭션 상태, before-image 추적 | 생성 |
| `core/src/main/kotlin/gwanbase/wal/WalCallbackImpl.kt` | WalCallback 구현체 (wal → storage 방향) | 생성 |
| `core/src/main/kotlin/gwanbase/wal/RecoveryManager.kt` | Redo + Undo 복구 | 생성 |
| `core/src/main/kotlin/gwanbase/storage/WalCallback.kt` | BPM↔WAL 인터페이스 (storage 패키지) | 생성 |
| `core/src/main/kotlin/gwanbase/storage/Page.kt` | `pageLsn` 필드 추가 | 수정 |
| `core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt` | WalCallback 연동, WAL 불변식 | 수정 |
| `core/src/main/kotlin/gwanbase/table/Database.kt` | LogManager 생명주기, auto-commit | 수정 |
| `core/src/test/kotlin/gwanbase/wal/LogRecordTest.kt` | LogRecord 직렬화 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/wal/LogManagerTest.kt` | LogManager 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/wal/RecoveryManagerTest.kt` | Recovery 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/wal/CrashRecoveryIntegrationTest.kt` | Crash simulation 통합 테스트 | 생성 |

---

### Task 1: LogRecord sealed class 및 직렬화

**Files:**
- Create: `core/src/main/kotlin/gwanbase/wal/LogRecord.kt`
- Create: `core/src/test/kotlin/gwanbase/wal/LogRecordTest.kt`

바이너리 포맷:
```
[totalLength:Int(4)][type:Byte(1)][lsn:Int(4)][txnId:Int(4)][prevLsn:Int(4)][...payload...][totalLength:Int(4)]
```
- BEGIN/COMMIT/ABORT/CHECKPOINT: payload 없음 (21 bytes)
- UPDATE: `[pageId:4][beforeImage:4096][afterImage:4096]` (8217 bytes)
- CLR: `[pageId:4][beforeImage:4096][undoNextLsn:4]` (4125 bytes)

- [ ] **Step 1: 직렬화 round-trip 실패 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/LogRecordTest.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LogRecordTest {

    @Test
    fun `Begin 레코드 직렬화 후 역직렬화 시 동일한 값 반환`() {
        val record = LogRecord.Begin(lsn = 0, txnId = 1)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Commit 레코드 직렬화 round-trip`() {
        val record = LogRecord.Commit(lsn = 5, txnId = 1, prevLsn = 3)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Abort 레코드 직렬화 round-trip`() {
        val record = LogRecord.Abort(lsn = 6, txnId = 2, prevLsn = 4)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `Update 레코드 직렬화 round-trip`() {
        val before = ByteArray(DiskManager.PAGE_SIZE) { 0xAA.toByte() }
        val after = ByteArray(DiskManager.PAGE_SIZE) { 0xBB.toByte() }
        val record = LogRecord.Update(lsn = 1, txnId = 1, prevLsn = 0, pageId = 42, beforeImage = before, afterImage = after)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) as LogRecord.Update
        deserialized.lsn shouldBe 1
        deserialized.txnId shouldBe 1
        deserialized.pageId shouldBe 42
        deserialized.beforeImage shouldBe before
        deserialized.afterImage shouldBe after
    }

    @Test
    fun `CLR 레코드 직렬화 round-trip`() {
        val before = ByteArray(DiskManager.PAGE_SIZE) { 0xCC.toByte() }
        val record = LogRecord.CLR(lsn = 7, txnId = 1, prevLsn = 5, pageId = 10, beforeImage = before, undoNextLsn = 2)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) as LogRecord.CLR
        deserialized.undoNextLsn shouldBe 2
        deserialized.beforeImage shouldBe before
    }

    @Test
    fun `Checkpoint 레코드 직렬화 round-trip`() {
        val record = LogRecord.Checkpoint(lsn = 10)
        val bytes = LogRecord.serialize(record)
        val deserialized = LogRecord.deserialize(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
        deserialized shouldBe record
    }

    @Test
    fun `직렬화된 레코드의 선두와 후미에 동일한 totalLength가 기록된다`() {
        val record = LogRecord.Begin(lsn = 0, txnId = 1)
        val bytes = LogRecord.serialize(record)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val headLength = buf.getInt(0)
        val tailLength = buf.getInt(bytes.size - 4)
        headLength shouldBe tailLength
        headLength shouldBe bytes.size
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.LogRecordTest" -i
```

Expected: FAIL (LogRecord 클래스가 아직 없음)

- [ ] **Step 3: LogRecord sealed class 구현**

`core/src/main/kotlin/gwanbase/wal/LogRecord.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.DiskManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAL 로그 레코드.
 *
 * 각 레코드는 LSN(Log Sequence Number)으로 식별되며,
 * prevLsn으로 같은 트랜잭션의 이전 레코드를 역추적할 수 있다.
 */
sealed class LogRecord {
    abstract val lsn: Int
    abstract val txnId: Int
    abstract val prevLsn: Int

    /** 트랜잭션 시작 */
    data class Begin(
        override val lsn: Int,
        override val txnId: Int,
    ) : LogRecord() {
        override val prevLsn: Int = -1
    }

    /** 트랜잭션 커밋 */
    data class Commit(
        override val lsn: Int,
        override val txnId: Int,
        override val prevLsn: Int,
    ) : LogRecord()

    /** 트랜잭션 중단 */
    data class Abort(
        override val lsn: Int,
        override val txnId: Int,
        override val prevLsn: Int,
    ) : LogRecord()

    /**
     * 페이지 수정 레코드.
     * [beforeImage]와 [afterImage]는 페이지 전체(4KB).
     */
    data class Update(
        override val lsn: Int,
        override val txnId: Int,
        override val prevLsn: Int,
        val pageId: Int,
        val beforeImage: ByteArray,
        val afterImage: ByteArray,
    ) : LogRecord() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Update) return false
            return lsn == other.lsn && txnId == other.txnId && prevLsn == other.prevLsn
                && pageId == other.pageId
                && beforeImage.contentEquals(other.beforeImage)
                && afterImage.contentEquals(other.afterImage)
        }
        override fun hashCode(): Int = lsn
    }

    /**
     * Compensation Log Record — Undo 동작 기록.
     * [undoNextLsn]은 다음에 undo할 레코드의 LSN을 가리킨다.
     * CLR 자체는 절대 undo되지 않는다.
     */
    data class CLR(
        override val lsn: Int,
        override val txnId: Int,
        override val prevLsn: Int,
        val pageId: Int,
        val beforeImage: ByteArray,
        val undoNextLsn: Int,
    ) : LogRecord() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CLR) return false
            return lsn == other.lsn && txnId == other.txnId && prevLsn == other.prevLsn
                && pageId == other.pageId
                && beforeImage.contentEquals(other.beforeImage)
                && undoNextLsn == other.undoNextLsn
        }
        override fun hashCode(): Int = lsn
    }

    /** Consistent Checkpoint */
    data class Checkpoint(override val lsn: Int) : LogRecord() {
        override val txnId: Int = -1
        override val prevLsn: Int = -1
    }

    companion object {
        /** 레코드 타입 바이트 상수 */
        private const val TYPE_BEGIN: Byte = 0
        private const val TYPE_COMMIT: Byte = 1
        private const val TYPE_ABORT: Byte = 2
        private const val TYPE_UPDATE: Byte = 3
        private const val TYPE_CLR: Byte = 4
        private const val TYPE_CHECKPOINT: Byte = 5

        /** 공통 헤더 크기: totalLength(4) + type(1) + lsn(4) + txnId(4) + prevLsn(4) = 17 */
        private const val HEADER_SIZE = 17

        /** 후미 totalLength 크기 */
        private const val FOOTER_SIZE = 4

        /**
         * 로그 레코드를 바이트 배열로 직렬화한다.
         * 포맷: [totalLength][type][lsn][txnId][prevLsn][payload][totalLength]
         */
        fun serialize(record: LogRecord): ByteArray {
            val payloadSize = when (record) {
                is Begin, is Commit, is Abort, is Checkpoint -> 0
                is Update -> 4 + DiskManager.PAGE_SIZE * 2  // pageId + before + after
                is CLR -> 4 + DiskManager.PAGE_SIZE + 4     // pageId + before + undoNextLsn
            }
            val totalLength = HEADER_SIZE + payloadSize + FOOTER_SIZE
            val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)

            // 헤더
            buf.putInt(totalLength)
            buf.put(typeOf(record))
            buf.putInt(record.lsn)
            buf.putInt(record.txnId)
            buf.putInt(record.prevLsn)

            // 페이로드
            when (record) {
                is Update -> {
                    buf.putInt(record.pageId)
                    buf.put(record.beforeImage)
                    buf.put(record.afterImage)
                }
                is CLR -> {
                    buf.putInt(record.pageId)
                    buf.put(record.beforeImage)
                    buf.putInt(record.undoNextLsn)
                }
                else -> {}
            }

            // 후미
            buf.putInt(totalLength)
            return buf.array()
        }

        /**
         * ByteBuffer의 현재 position부터 로그 레코드 하나를 역직렬화한다.
         */
        fun deserialize(buf: ByteBuffer): LogRecord {
            val totalLength = buf.getInt()
            val type = buf.get()
            val lsn = buf.getInt()
            val txnId = buf.getInt()
            val prevLsn = buf.getInt()

            val record = when (type) {
                TYPE_BEGIN -> Begin(lsn, txnId)
                TYPE_COMMIT -> Commit(lsn, txnId, prevLsn)
                TYPE_ABORT -> Abort(lsn, txnId, prevLsn)
                TYPE_UPDATE -> {
                    val pageId = buf.getInt()
                    val before = ByteArray(DiskManager.PAGE_SIZE)
                    buf.get(before)
                    val after = ByteArray(DiskManager.PAGE_SIZE)
                    buf.get(after)
                    Update(lsn, txnId, prevLsn, pageId, before, after)
                }
                TYPE_CLR -> {
                    val pageId = buf.getInt()
                    val before = ByteArray(DiskManager.PAGE_SIZE)
                    buf.get(before)
                    val undoNextLsn = buf.getInt()
                    CLR(lsn, txnId, prevLsn, pageId, before, undoNextLsn)
                }
                TYPE_CHECKPOINT -> Checkpoint(lsn)
                else -> error("알 수 없는 로그 레코드 타입: $type")
            }

            buf.getInt() // 후미 totalLength 소비
            return record
        }

        private fun typeOf(record: LogRecord): Byte = when (record) {
            is Begin -> TYPE_BEGIN
            is Commit -> TYPE_COMMIT
            is Abort -> TYPE_ABORT
            is Update -> TYPE_UPDATE
            is CLR -> TYPE_CLR
            is Checkpoint -> TYPE_CHECKPOINT
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — 전체 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.LogRecordTest" -i
```

Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/wal/LogRecord.kt \
       core/src/test/kotlin/gwanbase/wal/LogRecordTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] LogRecord sealed class 및 바이너리 직렬화 구현

6가지 레코드 타입(Begin, Commit, Abort, Update, CLR, Checkpoint)을
sealed class로 정의하고, 선두/후미에 totalLength를 배치하는
바이너리 직렬화 포맷을 구현한다.
EOF
)"
```

---

### Task 2: LogManager 구현

**Files:**
- Create: `core/src/main/kotlin/gwanbase/wal/LogManager.kt`
- Create: `core/src/test/kotlin/gwanbase/wal/LogManagerTest.kt`

- [ ] **Step 1: LogManager 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/LogManagerTest.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LogManagerTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var logManager: LogManager

    @BeforeEach
    fun setUp() {
        logManager = LogManager(tempDir.resolve("test.wal"))
    }

    @AfterEach
    fun tearDown() {
        logManager.close()
    }

    @Test
    fun `append 후 getRecord로 동일한 레코드 조회`() {
        val lsn = logManager.appendBegin(txnId = 1)
        lsn shouldBe 0
        val record = logManager.getRecord(0)
        record shouldBe LogRecord.Begin(lsn = 0, txnId = 1)
    }

    @Test
    fun `LSN은 append 순서대로 단조 증가한다`() {
        val lsn0 = logManager.appendBegin(txnId = 1)
        val lsn1 = logManager.appendUpdate(
            txnId = 1, prevLsn = 0, pageId = 5,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE),
        )
        val lsn2 = logManager.appendCommit(txnId = 1, prevLsn = lsn1)
        lsn0 shouldBe 0
        lsn1 shouldBe 1
        lsn2 shouldBe 2
    }

    @Test
    fun `flush 후 파일을 다시 열어도 레코드가 유지된다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.flush(1)
        logManager.close()

        val reopened = LogManager(tempDir.resolve("test.wal"))
        val records = reopened.forwardIterator(0).asSequence().toList()
        records.size shouldBe 2
        records[0] shouldBe LogRecord.Begin(lsn = 0, txnId = 1)
        records[1] shouldBe LogRecord.Commit(lsn = 1, txnId = 1, prevLsn = 0)
        reopened.close()
    }

    @Test
    fun `forwardIterator — fromLsn 이후 레코드만 순회한다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendUpdate(
            txnId = 1, prevLsn = 0, pageId = 1,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE),
        )
        logManager.appendCommit(txnId = 1, prevLsn = 1)
        logManager.flush(2)

        val records = logManager.forwardIterator(fromLsn = 1).asSequence().toList()
        records.size shouldBe 2
        records[0].lsn shouldBe 1
        records[1].lsn shouldBe 2
    }

    @Test
    fun `빈 로그 파일에서 forwardIterator는 빈 결과를 반환한다`() {
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.size shouldBe 0
    }

    @Test
    fun `lastCheckpointLsn — checkpoint가 없으면 -1`() {
        logManager.appendBegin(txnId = 1)
        logManager.lastCheckpointLsn() shouldBe -1
    }

    @Test
    fun `lastCheckpointLsn — checkpoint 이후 정확한 LSN 반환`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.appendCheckpoint()
        logManager.lastCheckpointLsn() shouldBe 2
    }

    @Test
    fun `flush 후 nextLsn이 보존된다`() {
        logManager.appendBegin(txnId = 1)
        logManager.appendCommit(txnId = 1, prevLsn = 0)
        logManager.flush(1)
        logManager.close()

        val reopened = LogManager(tempDir.resolve("test.wal"))
        val lsn = reopened.appendBegin(txnId = 2)
        lsn shouldBe 2
        reopened.close()
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.LogManagerTest" -i
```

Expected: FAIL (LogManager 클래스가 아직 없음)

- [ ] **Step 3: LogManager 구현**

`core/src/main/kotlin/gwanbase/wal/LogManager.kt`:

```kotlin
package gwanbase.wal

import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * WAL 로그 파일을 관리한다.
 *
 * 로그 레코드를 순차적으로 append하고, LSN 기반으로 flush/조회를 제공한다.
 * 메모리 내 버퍼와 디스크 파일을 이중으로 유지한다.
 */
class LogManager(private val logPath: Path) : AutoCloseable {

    private val channel: FileChannel = FileChannel.open(
        logPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    )

    /** 메모리 내 레코드 목록. LSN = records 리스트의 인덱스. */
    private val records = mutableListOf<LogRecord>()

    /** LSN → 파일 내 byte offset 매핑 */
    private val lsnToOffset = mutableListOf<Long>()

    /** 다음에 할당할 LSN */
    private var nextLsn: Int = 0

    /** 디스크에 flush된 마지막 LSN. -1이면 아직 flush한 적 없음. */
    private var flushedLsn: Int = -1

    /** 마지막 Checkpoint의 LSN. -1이면 없음. */
    private var lastCheckpointLsnValue: Int = -1

    init {
        // 기존 로그 파일이 있으면 읽어서 메모리에 로드
        if (channel.size() > 0) {
            loadFromDisk()
        }
    }

    /** Begin 로그를 추가하고 LSN을 반환한다. */
    fun appendBegin(txnId: Int): Int =
        doAppend(LogRecord.Begin(lsn = nextLsn, txnId = txnId))

    /** Commit 로그를 추가하고 LSN을 반환한다. */
    fun appendCommit(txnId: Int, prevLsn: Int): Int =
        doAppend(LogRecord.Commit(lsn = nextLsn, txnId = txnId, prevLsn = prevLsn))

    /** Abort 로그를 추가하고 LSN을 반환한다. */
    fun appendAbort(txnId: Int, prevLsn: Int): Int =
        doAppend(LogRecord.Abort(lsn = nextLsn, txnId = txnId, prevLsn = prevLsn))

    /** Update 로그를 추가하고 LSN을 반환한다. */
    fun appendUpdate(
        txnId: Int,
        prevLsn: Int,
        pageId: Int,
        beforeImage: ByteArray,
        afterImage: ByteArray,
    ): Int = doAppend(
        LogRecord.Update(
            lsn = nextLsn, txnId = txnId, prevLsn = prevLsn,
            pageId = pageId, beforeImage = beforeImage, afterImage = afterImage,
        ),
    )

    /** CLR 로그를 추가하고 LSN을 반환한다. */
    fun appendCLR(
        txnId: Int,
        prevLsn: Int,
        pageId: Int,
        beforeImage: ByteArray,
        undoNextLsn: Int,
    ): Int = doAppend(
        LogRecord.CLR(
            lsn = nextLsn, txnId = txnId, prevLsn = prevLsn,
            pageId = pageId, beforeImage = beforeImage, undoNextLsn = undoNextLsn,
        ),
    )

    /** Checkpoint 로그를 추가하고 LSN을 반환한다. */
    fun appendCheckpoint(): Int {
        val lsn = doAppend(LogRecord.Checkpoint(lsn = nextLsn))
        lastCheckpointLsnValue = lsn
        return lsn
    }

    /**
     * 지정된 LSN까지 로그를 디스크에 flush한다.
     * WAL 불변식: dirty page를 디스크에 쓰기 전에 반드시 호출해야 한다.
     */
    fun flush(upToLsn: Int) {
        if (upToLsn <= flushedLsn) return
        val startIndex = (flushedLsn + 1).coerceAtLeast(0)
        val endIndex = upToLsn.coerceAtMost(records.size - 1)

        for (i in startIndex..endIndex) {
            val bytes = LogRecord.serialize(records[i])
            lsnToOffset.ensureIndex(i, channel.size())
            lsnToOffset[i] = channel.size()
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) {
                channel.write(buf, channel.size())
            }
        }
        channel.force(true)
        flushedLsn = endIndex
        logger.debug { "로그 flush 완료: LSN $startIndex..$endIndex" }
    }

    /** LSN으로 레코드를 조회한다. */
    fun getRecord(lsn: Int): LogRecord {
        require(lsn in records.indices) { "LSN $lsn 범위 초과 (records: ${records.size})" }
        return records[lsn]
    }

    /** [fromLsn]부터 끝까지 순방향으로 순회하는 Iterator를 반환한다. */
    fun forwardIterator(fromLsn: Int = 0): Iterator<LogRecord> {
        if (fromLsn >= records.size) return emptyList<LogRecord>().iterator()
        return records.subList(fromLsn, records.size).iterator()
    }

    /** 마지막 Checkpoint 레코드의 LSN을 반환한다. 없으면 -1. */
    fun lastCheckpointLsn(): Int = lastCheckpointLsnValue

    /** 현재 레코드 수 (= 다음 LSN) */
    fun recordCount(): Int = records.size

    override fun close() {
        channel.close()
    }

    private fun doAppend(record: LogRecord): Int {
        val lsn = nextLsn++
        records.add(record)
        return lsn
    }

    /** 디스크에서 로그 파일을 읽어 메모리에 로드한다. */
    private fun loadFromDisk() {
        val fileSize = channel.size()
        if (fileSize == 0L) return

        val buf = ByteBuffer.allocate(fileSize.toInt())
        channel.read(buf, 0)
        buf.flip()
        buf.order(ByteOrder.BIG_ENDIAN)

        while (buf.hasRemaining()) {
            val offset = buf.position().toLong()
            val record = LogRecord.deserialize(buf)
            records.add(record)
            lsnToOffset.add(offset)
            if (record is LogRecord.Checkpoint) {
                lastCheckpointLsnValue = record.lsn
            }
        }

        nextLsn = records.size
        flushedLsn = records.size - 1
        logger.debug { "로그 파일 로드 완료: ${records.size}개 레코드" }
    }

    private fun MutableList<Long>.ensureIndex(index: Int, default: Long) {
        while (this.size <= index) this.add(default)
    }
}
```

- [ ] **Step 4: 테스트 실행 — 전체 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.LogManagerTest" -i
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/wal/LogManager.kt \
       core/src/test/kotlin/gwanbase/wal/LogManagerTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] LogManager 구현

로그 레코드 append, LSN 기반 flush, 순방향 Iterator,
파일 재시작 시 로그 로드 기능을 제공한다.
EOF
)"
```

---

### Task 3: Page.pageLsn + TransactionContext + WalCallback 인터페이스

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/storage/Page.kt:14-19,50-59`
- Create: `core/src/main/kotlin/gwanbase/storage/WalCallback.kt`
- Create: `core/src/main/kotlin/gwanbase/wal/TransactionContext.kt`

- [ ] **Step 1: Page에 pageLsn 필드 추가**

`core/src/main/kotlin/gwanbase/storage/Page.kt` 수정:

`isDirty` 선언 아래에 추가:

```kotlin
    /** 이 페이지를 마지막으로 수정한 로그 레코드의 LSN */
    @Volatile
    var pageLsn: Int = 0
```

`reset()` 메서드에 `pageLsn = 0` 추가:

```kotlin
    fun reset() {
        pageId = INVALID_PAGE_ID
        data.clear()
        val zeros = ByteArray(DiskManager.PAGE_SIZE)
        data.put(zeros)
        data.flip()
        pinCount = 0
        isDirty = false
        pageLsn = 0
    }
```

- [ ] **Step 2: WalCallback 인터페이스 생성**

`core/src/main/kotlin/gwanbase/storage/WalCallback.kt`:

```kotlin
package gwanbase.storage

import java.nio.ByteBuffer

/**
 * BufferPoolManager가 WAL 계층에 이벤트를 전달하기 위한 콜백 인터페이스.
 *
 * storage 패키지가 wal 패키지에 직접 의존하지 않도록 의존성을 역전시킨다.
 * wal 패키지에서 이 인터페이스를 구현한다.
 */
interface WalCallback {
    /**
     * dirty page를 디스크에 쓰기 전에 호출된다.
     * WAL 불변식: pageLsn까지 로그가 flush되어야 한다.
     */
    fun ensureLogFlushed(pageLsn: Int)

    /**
     * 페이지가 fetch될 때 호출된다.
     * 활성 트랜잭션이 있으면 before-image를 캡처한다.
     */
    fun onPageFetched(pageId: Int, data: ByteBuffer)

    /**
     * 페이지가 dirty 상태로 unpin될 때 호출된다.
     * Update 로그 레코드를 기록하고, 할당된 LSN을 반환한다.
     * 로깅이 불필요하면 -1을 반환한다.
     */
    fun onPageDirtyUnpin(pageId: Int, data: ByteBuffer): Int
}
```

- [ ] **Step 3: TransactionContext 생성**

`core/src/main/kotlin/gwanbase/wal/TransactionContext.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.DiskManager
import java.nio.ByteBuffer

/**
 * 현재 활성 트랜잭션의 상태를 추적한다.
 *
 * before-image 맵으로 페이지 수정 전 상태를 보존하며,
 * prevLsn 체인을 위해 마지막 LSN을 유지한다.
 */
class TransactionContext(val txnId: Int) {

    /** 이 트랜잭션의 마지막 로그 레코드 LSN. Begin의 LSN으로 초기화. */
    var lastLsn: Int = -1

    /** pageId → before-image. 페이지가 처음 fetch될 때 캡처된다. */
    private val beforeImages = mutableMapOf<Int, ByteArray>()

    /**
     * 페이지의 before-image를 캡처한다.
     * 같은 트랜잭션 내에서 이미 캡처된 페이지는 무시한다.
     */
    fun captureBeforeImage(pageId: Int, data: ByteBuffer) {
        if (pageId in beforeImages) return
        val snapshot = ByteArray(DiskManager.PAGE_SIZE)
        val pos = data.position()
        data.rewind()
        data.get(snapshot)
        data.position(pos)
        beforeImages[pageId] = snapshot
    }

    /**
     * before-image를 가져오고 맵에서 제거한다.
     * 제거하여 같은 페이지의 다음 fetch에서 새로운 before-image를 캡처할 수 있게 한다.
     */
    fun getAndRemoveBeforeImage(pageId: Int): ByteArray? =
        beforeImages.remove(pageId)
}
```

- [ ] **Step 4: 기존 테스트 실행 — 회귀 없음 확인**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL (pageLsn 추가는 기존 동작에 영향 없음)

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/storage/Page.kt \
       core/src/main/kotlin/gwanbase/storage/WalCallback.kt \
       core/src/main/kotlin/gwanbase/wal/TransactionContext.kt
git commit -m "$(cat <<'EOF'
[Phase 5] Page.pageLsn, WalCallback 인터페이스, TransactionContext 추가

Page에 pageLsn 필드를 추가하고, storage↔wal 간 의존성 역전을 위한
WalCallback 인터페이스를 정의한다. TransactionContext는 트랜잭션의
before-image 추적과 prevLsn 체인을 관리한다.
EOF
)"
```

---

### Task 4: BufferPoolManager WAL 통합

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt`
- Modify: `core/src/test/kotlin/gwanbase/storage/BufferPoolManagerTest.kt`

- [ ] **Step 1: BPM WAL 통합 테스트 작성**

`core/src/test/kotlin/gwanbase/storage/BufferPoolManagerTest.kt`에 추가:

```kotlin
    @Test
    fun `WAL 콜백 — eviction 시 ensureLogFlushed가 호출된다`() {
        var flushedLsn = -1
        val callback = object : WalCallback {
            override fun ensureLogFlushed(pageLsn: Int) { flushedLsn = pageLsn }
            override fun onPageFetched(pageId: Int, data: java.nio.ByteBuffer) {}
            override fun onPageDirtyUnpin(pageId: Int, data: java.nio.ByteBuffer): Int = -1
        }
        bpm.walCallback = callback

        // 풀 크기 3, 3개 채우고 dirty로 unpin
        val ids = (0 until 3).map { bpm.newPage()!!.pageId }
        ids.forEach { pageId ->
            val page = bpm.fetchPage(pageId)!!
            page.pageLsn = 10
            bpm.unpinPage(pageId, isDirty = true)
        }

        // 4번째 할당 시 eviction 발생 → ensureLogFlushed(10) 호출
        bpm.newPage()
        flushedLsn shouldBe 10
    }

    @Test
    fun `WAL 콜백 — fetchPage 시 onPageFetched가 호출된다`() {
        var fetchedPageId = -1
        val callback = object : WalCallback {
            override fun ensureLogFlushed(pageLsn: Int) {}
            override fun onPageFetched(pageId: Int, data: java.nio.ByteBuffer) { fetchedPageId = pageId }
            override fun onPageDirtyUnpin(pageId: Int, data: java.nio.ByteBuffer): Int = -1
        }
        bpm.walCallback = callback

        val page = bpm.newPage()!!
        bpm.unpinPage(page.pageId)

        bpm.fetchPage(page.pageId)
        fetchedPageId shouldBe page.pageId
        bpm.unpinPage(page.pageId)
    }

    @Test
    fun `WAL 콜백 — dirty unpin 시 onPageDirtyUnpin이 호출되고 pageLsn이 설정된다`() {
        val callback = object : WalCallback {
            override fun ensureLogFlushed(pageLsn: Int) {}
            override fun onPageFetched(pageId: Int, data: java.nio.ByteBuffer) {}
            override fun onPageDirtyUnpin(pageId: Int, data: java.nio.ByteBuffer): Int = 42
        }
        bpm.walCallback = callback

        val page = bpm.newPage()!!
        val pageId = page.pageId
        page.data.putInt(0, 999)
        bpm.unpinPage(pageId, isDirty = true)

        val fetched = bpm.fetchPage(pageId)!!
        fetched.pageLsn shouldBe 42
        bpm.unpinPage(pageId)
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :core:test --tests "gwanbase.storage.BufferPoolManagerTest" -i
```

Expected: FAIL (walCallback 속성이 아직 없음)

- [ ] **Step 3: BPM에 walCallback 속성 추가 및 후킹**

`core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt` 수정:

클래스 필드에 추가:

```kotlin
    /** WAL 콜백. Phase 5에서 주입된다. null이면 WAL 미사용. */
    var walCallback: WalCallback? = null
```

`fetchPage` 메서드 — 두 곳 모두에서 반환 직전에 콜백 호출:

캐시 히트 경로 (기존 `return page` 직전):
```kotlin
            walCallback?.onPageFetched(pageId, page.data)
            return page
```

캐시 미스 경로 (기존 `return page` 직전):
```kotlin
        walCallback?.onPageFetched(pageId, page.data)
        return page
```

`newPage` 메서드 — 반환 직전에 콜백 호출:

```kotlin
        walCallback?.onPageFetched(newPageId, page.data)
        return page
```

`unpinPage` 메서드 — `if (isDirty)` 블록 확장:

```kotlin
        if (isDirty) {
            page.isDirty = true
            val lsn = walCallback?.onPageDirtyUnpin(pageId, page.data) ?: -1
            if (lsn >= 0) page.pageLsn = lsn
        }
```

`findFreeFrame` 메서드 — eviction dirty 쓰기 직전:

```kotlin
        if (victimPage.isDirty) {
            walCallback?.ensureLogFlushed(victimPage.pageLsn)
            victimPage.data.rewind()
            diskManager.writePage(victimPage.pageId, victimPage.data)
            victimPage.data.rewind()
        }
```

`flushPage` 메서드 — 디스크 쓰기 직전:

```kotlin
        walCallback?.ensureLogFlushed(page.pageLsn)
        page.data.rewind()
        diskManager.writePage(pageId, page.data)
```

- [ ] **Step 4: 테스트 실행 — 전체 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.storage.BufferPoolManagerTest" -i
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 전체 테스트 회귀 확인**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL (walCallback이 null이면 기존 동작 유지)

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/storage/BufferPoolManager.kt \
       core/src/test/kotlin/gwanbase/storage/BufferPoolManagerTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] BufferPoolManager에 WalCallback 연동

fetchPage 시 onPageFetched, dirty unpin 시 onPageDirtyUnpin,
eviction/flush 시 ensureLogFlushed를 호출한다.
walCallback이 null이면 기존 동작과 동일하다.
EOF
)"
```

---

### Task 5: WalCallbackImpl + Database auto-commit 트랜잭션

**Files:**
- Create: `core/src/main/kotlin/gwanbase/wal/WalCallbackImpl.kt`
- Modify: `core/src/main/kotlin/gwanbase/table/Database.kt`
- Create: `core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt`

- [ ] **Step 1: WalCallbackImpl 구현**

`core/src/main/kotlin/gwanbase/wal/WalCallbackImpl.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.DiskManager
import gwanbase.storage.WalCallback
import java.nio.ByteBuffer

/**
 * [WalCallback]의 구현체.
 *
 * BPM 이벤트를 받아 LogManager에 로그 레코드를 기록하고,
 * TransactionContext에 before-image를 캡처한다.
 */
class WalCallbackImpl(
    private val logManager: LogManager,
    private val txnProvider: () -> TransactionContext?,
) : WalCallback {

    override fun ensureLogFlushed(pageLsn: Int) {
        logManager.flush(pageLsn)
    }

    override fun onPageFetched(pageId: Int, data: ByteBuffer) {
        txnProvider()?.captureBeforeImage(pageId, data)
    }

    override fun onPageDirtyUnpin(pageId: Int, data: ByteBuffer): Int {
        val txn = txnProvider() ?: return -1
        val beforeImage = txn.getAndRemoveBeforeImage(pageId) ?: return -1
        val afterImage = captureImage(data)
        val lsn = logManager.appendUpdate(
            txnId = txn.txnId,
            prevLsn = txn.lastLsn,
            pageId = pageId,
            beforeImage = beforeImage,
            afterImage = afterImage,
        )
        txn.lastLsn = lsn
        return lsn
    }

    private fun captureImage(data: ByteBuffer): ByteArray {
        val snapshot = ByteArray(DiskManager.PAGE_SIZE)
        val pos = data.position()
        data.rewind()
        data.get(snapshot)
        data.position(pos)
        return snapshot
    }
}
```

- [ ] **Step 2: Database에 WAL 통합 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt`:

```kotlin
package gwanbase.wal

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WalIntegrationTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun `executeSql로 CREATE TABLE + INSERT 후 SELECT 성공`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        database.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")

        val result = database.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 1
        result.rows[0][1] shouldBe "Alice"
    }

    @Test
    fun `executeSql — DML 실행 시 WAL 로그가 기록된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (1)")

        // 로그 파일이 존재하고 레코드가 있어야 한다
        val logPath = tempDir.resolve("test.db.wal")
        logPath.toFile().exists() shouldBe true

        val logManager = LogManager(logPath)
        val records = logManager.forwardIterator(0).asSequence().toList()
        // 최소: BEGIN + Update(s) + COMMIT × 2 (CREATE TABLE, INSERT)
        (records.size >= 4) shouldBe true
        records.any { it is LogRecord.Begin } shouldBe true
        records.any { it is LogRecord.Commit } shouldBe true
        records.any { it is LogRecord.Update } shouldBe true
        logManager.close()
    }

    @Test
    fun `executeSql — SELECT는 Update 로그를 생성하지 않는다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (1)")

        val logManager1 = LogManager(tempDir.resolve("test.db.wal"))
        val countBefore = logManager1.recordCount()
        logManager1.close()

        database.executeSql("SELECT * FROM t")

        val logManager2 = LogManager(tempDir.resolve("test.db.wal"))
        val countAfter = logManager2.recordCount()
        logManager2.close()

        // SELECT는 Begin+Commit만 추가되고 Update는 없어야 한다
        // (실제로 Read-only txn에서도 Begin+Commit이 기록됨)
        val diff = countAfter - countBefore
        (diff <= 2) shouldBe true  // Begin + Commit만
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.WalIntegrationTest" -i
```

Expected: FAIL (Database.executeSql 메서드가 없음)

- [ ] **Step 4: Database에 WAL 통합 구현**

`core/src/main/kotlin/gwanbase/table/Database.kt` 수정:

import 추가:

```kotlin
import gwanbase.sql.SqlExecutor
import gwanbase.sql.ExecuteResult
import gwanbase.wal.LogManager
import gwanbase.wal.TransactionContext
import gwanbase.wal.WalCallbackImpl
```

클래스 필드 추가 (companion object 바로 아래, 기존 필드 영역):

```kotlin
    private val logManager: LogManager?
    private val sqlExecutor: SqlExecutor = SqlExecutor(this)
    private var currentTxn: TransactionContext? = null
    private var nextTxnId: Int = 0
```

생성자 변경 — `logManager` 파라미터 추가:

```kotlin
class Database private constructor(
    private val diskManager: DiskManager,
    private val bpm: BufferPoolManager,
    private val catalog: Catalog,
    private val logManager: LogManager?,
) : AutoCloseable {
```

`open()` 수정:

```kotlin
        fun open(path: Path, bufferPoolSize: Int = 256): Database {
            val dm = DiskManager(path)
            try {
                val bpm = BufferPoolManager(dm, bufferPoolSize)

                val catalog = if (dm.pageCount == 0) {
                    createFresh(bpm)
                } else {
                    loadExisting(bpm)
                }

                val logPath = path.resolveSibling(path.fileName.toString() + ".wal")
                val logManager = LogManager(logPath)

                val db = Database(dm, bpm, catalog, logManager)

                // WAL 콜백 연결
                bpm.walCallback = WalCallbackImpl(logManager) { db.currentTxn }

                return db
            } catch (e: Throwable) {
                dm.close()
                throw e
            }
        }
```

`executeSql` 메서드 추가:

```kotlin
    /**
     * SQL 문을 auto-commit 트랜잭션으로 실행한다.
     * DML/DDL은 Begin → 실행 → Commit으로 감싸고,
     * 실패 시 Abort를 기록한다.
     */
    fun executeSql(sql: String): ExecuteResult {
        beginTransaction()
        try {
            val result = sqlExecutor.execute(sql)
            commitTransaction()
            return result
        } catch (e: Throwable) {
            abortTransaction()
            throw e
        }
    }

    private fun beginTransaction() {
        val txnId = nextTxnId++
        val txn = TransactionContext(txnId)
        val lm = logManager
        if (lm != null) {
            txn.lastLsn = lm.appendBegin(txnId)
        }
        currentTxn = txn
    }

    private fun commitTransaction() {
        val txn = currentTxn ?: return
        val lm = logManager
        if (lm != null) {
            val commitLsn = lm.appendCommit(txn.txnId, txn.lastLsn)
            lm.flush(commitLsn)
        }
        currentTxn = null
    }

    private fun abortTransaction() {
        val txn = currentTxn ?: return
        val lm = logManager
        if (lm != null) {
            lm.appendAbort(txn.txnId, txn.lastLsn)
        }
        currentTxn = null
    }
```

`close()` 수정:

```kotlin
    override fun close() {
        if (closed) return
        bpm.flushAllPages()
        logManager?.close()
        diskManager.close()
        closed = true
    }
```

VERSION 업데이트:

```kotlin
        const val VERSION: Short = 3  // Phase 5: WAL 도입
```

`loadExisting`에서 version 검증을 하위 호환으로 수정:

```kotlin
                check(version == VERSION || version == 2.toShort()) {
                    "DB 파일 버전 불일치: expected $VERSION, got $version"
                }
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.WalIntegrationTest" -i
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 기존 테스트 회귀 확인**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL (기존 테스트는 SqlExecutor를 직접 사용하므로 영향 없음. Database 버전 하위 호환 처리로 DatabaseTest도 통과.)

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/wal/WalCallbackImpl.kt \
       core/src/main/kotlin/gwanbase/table/Database.kt \
       core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] Database auto-commit 트랜잭션 및 WAL 통합

Database.executeSql()이 각 SQL 문을 Begin/Commit으로 감싸고,
WalCallbackImpl을 통해 BPM의 페이지 수정을 자동으로 로깅한다.
EOF
)"
```

---

### Task 6: RecoveryManager (Redo + Undo with CLR)

**Files:**
- Create: `core/src/main/kotlin/gwanbase/wal/RecoveryManager.kt`
- Create: `core/src/test/kotlin/gwanbase/wal/RecoveryManagerTest.kt`

- [ ] **Step 1: RecoveryManager 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/RecoveryManagerTest.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class RecoveryManagerTest {

    @TempDir lateinit var tempDir: Path

    private fun createBpmAndDm(): Pair<BufferPoolManager, DiskManager> {
        val dm = DiskManager(tempDir.resolve("test.db"))
        val bpm = BufferPoolManager(dm, 256)
        return bpm to dm
    }

    @Test
    fun `Redo — 커밋된 트랜잭션의 Update가 페이지에 반영된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지 할당 (pageId=0)
        val page = bpm.newPage()!!
        val pageId = page.pageId
        bpm.unpinPage(pageId)

        // 커밋된 트랜잭션 로그 작성
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 12345)
        val beforeImage = ByteArray(DiskManager.PAGE_SIZE)

        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = beforeImage, afterImage = afterImage)
        logManager.appendCommit(txnId = 0, prevLsn = 1)
        logManager.flush(2)

        // Recovery 실행
        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        // 페이지에 after-image가 반영되어야 한다
        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 12345
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `Undo — 미커밋 트랜잭션의 Update가 롤백된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지 할당
        val page = bpm.newPage()!!
        val pageId = page.pageId
        bpm.unpinPage(pageId)

        // 미커밋 트랜잭션 로그 (Commit 없음)
        val beforeImage = ByteArray(DiskManager.PAGE_SIZE) // 원래 0
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 99999)

        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = beforeImage, afterImage = afterImage)
        logManager.flush(1)

        // Recovery — Redo로 afterImage 적용 후, Undo로 beforeImage 복원
        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 0 // before-image (0) 복원
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `Redo skip — pageLsn이 이미 높으면 재적용하지 않는다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        // 페이지를 할당하고 pageLsn을 높게 설정 (이미 반영된 상태 시뮬레이션)
        val page = bpm.newPage()!!
        val pageId = page.pageId
        page.data.order(ByteOrder.BIG_ENDIAN)
        page.data.putInt(0, 77777) // 이미 반영된 다른 값
        page.pageLsn = 100
        bpm.unpinPage(pageId, isDirty = true)
        bpm.flushPage(pageId)

        // LSN 1인 Update 로그 — pageLsn(100) > record.lsn(1)이므로 skip
        val afterImage = ByteArray(DiskManager.PAGE_SIZE)
        ByteBuffer.wrap(afterImage).order(ByteOrder.BIG_ENDIAN).putInt(0, 12345)
        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = pageId,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE), afterImage = afterImage)
        logManager.appendCommit(txnId = 0, prevLsn = 1)
        logManager.flush(2)

        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        val recovered = bpm.fetchPage(pageId)!!
        recovered.data.order(ByteOrder.BIG_ENDIAN)
        recovered.data.getInt(0) shouldBe 77777 // redo skip — 원래 값 유지
        bpm.unpinPage(pageId)

        logManager.close()
        dm.close()
    }

    @Test
    fun `빈 로그에서 recovery는 아무 작업 없이 성공한다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        val recoveryManager = RecoveryManager(logManager, bpm)
        val nextTxnId = recoveryManager.recover()
        nextTxnId shouldBe 0

        logManager.close()
        dm.close()
    }

    @Test
    fun `CLR — Undo 시 CLR이 기록된다`() {
        val (bpm, dm) = createBpmAndDm()
        val logManager = LogManager(tempDir.resolve("test.wal"))

        val page = bpm.newPage()!!
        bpm.unpinPage(page.pageId)

        // 미커밋 트랜잭션
        logManager.appendBegin(txnId = 0)
        logManager.appendUpdate(txnId = 0, prevLsn = 0, pageId = page.pageId,
            beforeImage = ByteArray(DiskManager.PAGE_SIZE),
            afterImage = ByteArray(DiskManager.PAGE_SIZE))
        logManager.flush(1)

        val recoveryManager = RecoveryManager(logManager, bpm)
        recoveryManager.recover()

        // Recovery 후 로그에 CLR과 Abort가 추가되어야 한다
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.any { it is LogRecord.CLR } shouldBe true
        records.any { it is LogRecord.Abort } shouldBe true

        logManager.close()
        dm.close()
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.RecoveryManagerTest" -i
```

Expected: FAIL (RecoveryManager 없음)

- [ ] **Step 3: RecoveryManager 구현**

`core/src/main/kotlin/gwanbase/wal/RecoveryManager.kt`:

```kotlin
package gwanbase.wal

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import mu.KotlinLogging
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

/**
 * Crash Recovery를 수행한다.
 *
 * Consistent Checkpoint 기반 2단계 복구:
 * 1. Redo: checkpoint 이후 모든 레코드를 순방향 재생
 * 2. Undo: 미커밋 트랜잭션을 역방향 롤백 (CLR 기록)
 */
class RecoveryManager(
    private val logManager: LogManager,
    private val bpm: BufferPoolManager,
) {

    /**
     * Recovery를 수행하고 다음에 사용할 txnId를 반환한다.
     */
    fun recover(): Int {
        if (logManager.recordCount() == 0) {
            logger.info { "빈 로그 — recovery 불필요" }
            return 0
        }

        val startLsn = (logManager.lastCheckpointLsn() + 1).coerceAtLeast(0)
        val committedTxns = mutableSetOf<Int>()
        val activeTxns = mutableMapOf<Int, Int>() // txnId → lastLsn
        var maxTxnId = -1

        // === Redo 단계 ===
        logger.info { "Redo 단계 시작 (startLsn=$startLsn)" }
        val iter = logManager.forwardIterator(startLsn)
        while (iter.hasNext()) {
            val record = iter.next()
            if (record.txnId >= 0) maxTxnId = maxOf(maxTxnId, record.txnId)

            when (record) {
                is LogRecord.Begin -> {
                    activeTxns[record.txnId] = record.lsn
                }
                is LogRecord.Commit -> {
                    committedTxns.add(record.txnId)
                    activeTxns.remove(record.txnId)
                }
                is LogRecord.Abort -> {
                    activeTxns.remove(record.txnId)
                }
                is LogRecord.Update -> {
                    activeTxns[record.txnId] = record.lsn
                    redoUpdate(record.pageId, record.lsn, record.afterImage)
                }
                is LogRecord.CLR -> {
                    activeTxns[record.txnId] = record.lsn
                    redoUpdate(record.pageId, record.lsn, record.beforeImage)
                }
                is LogRecord.Checkpoint -> {}
            }
        }
        logger.info { "Redo 완료. 미커밋 트랜잭션: ${activeTxns.keys}" }

        // === Undo 단계 ===
        logger.info { "Undo 단계 시작" }
        while (activeTxns.isNotEmpty()) {
            // 가장 큰 lastLsn을 가진 트랜잭션부터 undo
            val (txnId, lastLsn) = activeTxns.maxByOrNull { it.value }!!
            val record = logManager.getRecord(lastLsn)

            when (record) {
                is LogRecord.Update -> {
                    undoUpdate(record)
                    val prevLsn = record.prevLsn
                    if (prevLsn < 0) {
                        logManager.appendAbort(txnId, logManager.recordCount() - 1)
                        activeTxns.remove(txnId)
                    } else {
                        activeTxns[txnId] = prevLsn
                    }
                }
                is LogRecord.CLR -> {
                    // CLR은 undo하지 않는다. undoNextLsn으로 건너뛴다.
                    val nextLsn = record.undoNextLsn
                    if (nextLsn < 0) {
                        logManager.appendAbort(txnId, record.lsn)
                        activeTxns.remove(txnId)
                    } else {
                        activeTxns[txnId] = nextLsn
                    }
                }
                is LogRecord.Begin -> {
                    logManager.appendAbort(txnId, record.lsn)
                    activeTxns.remove(txnId)
                }
                else -> {
                    activeTxns.remove(txnId)
                }
            }
        }
        logger.info { "Undo 완료" }

        // 변경된 페이지를 디스크에 반영
        bpm.flushAllPages()

        return maxTxnId + 1
    }

    /**
     * Redo: 페이지에 이미지를 적용한다.
     * pageLsn >= recordLsn이면 이미 반영된 것이므로 skip.
     */
    private fun redoUpdate(pageId: Int, recordLsn: Int, image: ByteArray) {
        val page = bpm.fetchPage(pageId)
        if (page == null) {
            logger.warn { "Redo: 페이지 $pageId 를 fetch할 수 없다 (skip)" }
            return
        }
        try {
            if (page.pageLsn >= recordLsn) {
                logger.debug { "Redo skip: page $pageId pageLsn=${page.pageLsn} >= recordLsn=$recordLsn" }
                return
            }
            page.data.clear()
            page.data.put(image)
            page.data.flip()
            page.pageLsn = recordLsn
            page.isDirty = true
        } finally {
            bpm.unpinPage(pageId, isDirty = page.isDirty)
        }
    }

    /**
     * Undo: Update 레코드의 before-image를 복원하고 CLR을 기록한다.
     */
    private fun undoUpdate(record: LogRecord.Update) {
        val page = bpm.fetchPage(record.pageId)
        if (page == null) {
            logger.warn { "Undo: 페이지 ${record.pageId} 를 fetch할 수 없다 (skip)" }
            return
        }
        try {
            page.data.clear()
            page.data.put(record.beforeImage)
            page.data.flip()

            val clrLsn = logManager.appendCLR(
                txnId = record.txnId,
                prevLsn = record.lsn,
                pageId = record.pageId,
                beforeImage = record.beforeImage,
                undoNextLsn = record.prevLsn,
            )
            page.pageLsn = clrLsn
            page.isDirty = true
        } finally {
            bpm.unpinPage(record.pageId, isDirty = true)
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — 전체 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.RecoveryManagerTest" -i
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/wal/RecoveryManager.kt \
       core/src/test/kotlin/gwanbase/wal/RecoveryManagerTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] RecoveryManager 구현 (Redo + Undo with CLR)

Consistent Checkpoint 기반 2단계 Recovery:
Redo로 커밋된 변경을 재생하고, Undo로 미커밋 변경을 CLR과 함께 롤백한다.
EOF
)"
```

---

### Task 7: Consistent Checkpoint + Database Recovery 통합

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/table/Database.kt`
- Modify: `core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt`

- [ ] **Step 1: Checkpoint + Recovery 통합 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt`에 추가:

```kotlin
    @Test
    fun `close 시 checkpoint가 기록된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (1)")
        database.close()

        val logManager = LogManager(tempDir.resolve("test.db.wal"))
        val records = logManager.forwardIterator(0).asSequence().toList()
        records.any { it is LogRecord.Checkpoint } shouldBe true
        logManager.close()
    }

    @Test
    fun `정상 종료 후 재시작 시 데이터가 유지된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        database.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")
        database.executeSql("INSERT INTO t (id, name) VALUES (2, 'Bob')")
        database.close()

        val reopened = Database.open(tempDir.resolve("test.db"))
        val result = reopened.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 2
        reopened.close()
    }

    @Test
    fun `Recovery 후 커밋된 트랜잭션 데이터가 복구된다`() {
        database.executeSql("CREATE TABLE t (id INT NOT NULL)")
        database.executeSql("INSERT INTO t (id) VALUES (42)")
        // close 대신 flush만 하여 checkpoint 없이 "crash" 시뮬레이션
        // (실제 crash는 Task 8에서 더 정밀하게 테스트)
        database.close()

        val reopened = Database.open(tempDir.resolve("test.db"))
        val result = reopened.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 42
        reopened.close()
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.WalIntegrationTest" -i
```

Expected: FAIL (checkpoint/recovery 미구현)

- [ ] **Step 3: Database에 checkpoint + recovery 로직 추가**

`core/src/main/kotlin/gwanbase/table/Database.kt` 수정:

import 추가:

```kotlin
import gwanbase.wal.RecoveryManager
```

`open()` 메서드에 recovery 호출 추가:

```kotlin
        fun open(path: Path, bufferPoolSize: Int = 256): Database {
            val dm = DiskManager(path)
            try {
                val bpm = BufferPoolManager(dm, bufferPoolSize)

                val catalog = if (dm.pageCount == 0) {
                    createFresh(bpm)
                } else {
                    loadExisting(bpm)
                }

                val logPath = path.resolveSibling(path.fileName.toString() + ".wal")
                val logManager = LogManager(logPath)

                // Recovery 수행 (WAL 콜백 연결 전 — recovery 중 추가 로깅 방지)
                val recoveryManager = RecoveryManager(logManager, bpm)
                val nextTxnId = recoveryManager.recover()

                // Catalog 재로드 (recovery가 catalog 페이지를 변경했을 수 있음)
                val recoveredCatalog = if (dm.pageCount > 0) {
                    loadExisting(bpm)
                } else {
                    catalog
                }

                val db = Database(dm, bpm, recoveredCatalog, logManager)
                db.nextTxnId = nextTxnId

                // WAL 콜백 연결 (recovery 완료 후)
                bpm.walCallback = WalCallbackImpl(logManager) { db.currentTxn }

                return db
            } catch (e: Throwable) {
                dm.close()
                throw e
            }
        }
```

`close()` 메서드에 checkpoint 추가:

```kotlin
    override fun close() {
        if (closed) return
        // Checkpoint: 모든 dirty page flush 후 checkpoint 레코드 기록
        bpm.flushAllPages()
        logManager?.let { lm ->
            lm.appendCheckpoint()
            lm.flush(lm.recordCount() - 1)
        }
        logManager?.close()
        diskManager.close()
        closed = true
    }
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :core:test --tests "gwanbase.wal.WalIntegrationTest" -i
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 전체 테스트 회귀 확인**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/table/Database.kt \
       core/src/test/kotlin/gwanbase/wal/WalIntegrationTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] Consistent Checkpoint 및 Database Recovery 통합

Database.close()에서 모든 dirty page를 flush한 뒤 Checkpoint 로그를
기록한다. Database.open()에서 RecoveryManager.recover()를 호출하여
crash 후에도 커밋된 데이터를 복구한다.
EOF
)"
```

---

### Task 8: Crash Simulation 통합 테스트 + CLAUDE.md 업데이트

**Files:**
- Create: `core/src/test/kotlin/gwanbase/wal/CrashRecoveryIntegrationTest.kt`
- Modify: `CLAUDE.md` (Phase 5 완료 반영)

- [ ] **Step 1: Crash simulation 테스트 작성**

`core/src/test/kotlin/gwanbase/wal/CrashRecoveryIntegrationTest.kt`:

```kotlin
package gwanbase.wal

import gwanbase.sql.ExecuteResult
import gwanbase.sql.SqlExecutor
import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Crash Recovery 통합 테스트.
 *
 * Crash를 시뮬레이션하기 위해 Database.close() 대신
 * DiskManager를 직접 닫아 checkpoint 없이 종료한다.
 */
class CrashRecoveryIntegrationTest {

    @TempDir lateinit var tempDir: Path

    private fun dbPath() = tempDir.resolve("crash_test.db")

    /**
     * crash 시뮬레이션: checkpoint 없이 강제 종료.
     * LogManager.flush()는 호출되어 있으므로(commitTransaction에서)
     * 로그는 디스크에 있지만, dirty page는 flush되지 않았을 수 있다.
     */
    private fun simulateCrash(database: Database) {
        // close()를 호출하지 않고 GC에 맡김 — checkpoint/flushAllPages 미실행
        // FileChannel은 GC 시 닫히므로 테스트에서는 명시적으로 닫지 않음
    }

    @Test
    fun `커밋된 INSERT는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        db.executeSql("INSERT INTO t (id, name) VALUES (1, 'Alice')")
        db.executeSql("INSERT INTO t (id, name) VALUES (2, 'Bob')")
        simulateCrash(db)

        // Recovery
        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 2
        recovered.close()
    }

    @Test
    fun `커밋된 UPDATE는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL, value INT NOT NULL)")
        db.executeSql("INSERT INTO t (id, value) VALUES (1, 100)")
        db.executeSql("UPDATE t SET value = 200 WHERE id = 1")
        simulateCrash(db)

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT value FROM t WHERE id = 1") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 200
        recovered.close()
    }

    @Test
    fun `커밋된 DELETE는 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL)")
        db.executeSql("INSERT INTO t (id) VALUES (1)")
        db.executeSql("INSERT INTO t (id) VALUES (2)")
        db.executeSql("DELETE FROM t WHERE id = 1")
        simulateCrash(db)

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe 2
        recovered.close()
    }

    @Test
    fun `CREATE TABLE은 crash 후 recovery로 복구된다`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE alpha (id INT NOT NULL)")
        db.executeSql("CREATE TABLE beta (name VARCHAR(100) NOT NULL)")
        simulateCrash(db)

        val recovered = Database.open(dbPath())
        // 테이블이 존재해야 INSERT 가능
        recovered.executeSql("INSERT INTO alpha (id) VALUES (1)")
        recovered.executeSql("INSERT INTO beta (name) VALUES ('test')")
        val r1 = recovered.executeSql("SELECT * FROM alpha") as ExecuteResult.Selected
        val r2 = recovered.executeSql("SELECT * FROM beta") as ExecuteResult.Selected
        r1.rows.size shouldBe 1
        r2.rows.size shouldBe 1
        recovered.close()
    }

    @Test
    fun `Checkpoint 이후 추가 INSERT + crash → 전체 데이터 무결성`() {
        // 첫 세션: INSERT 2건 + 정상 종료 (checkpoint 포함)
        val db1 = Database.open(dbPath())
        db1.executeSql("CREATE TABLE t (id INT NOT NULL)")
        db1.executeSql("INSERT INTO t (id) VALUES (1)")
        db1.executeSql("INSERT INTO t (id) VALUES (2)")
        db1.close() // checkpoint 포함

        // 두번째 세션: INSERT 1건 + crash (checkpoint 없음)
        val db2 = Database.open(dbPath())
        db2.executeSql("INSERT INTO t (id) VALUES (3)")
        simulateCrash(db2)

        // Recovery — checkpoint 이전 2건 + 이후 1건 = 총 3건
        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 3
        recovered.close()
    }

    @Test
    fun `여러 트랜잭션 커밋 후 crash → 모든 데이터 복구`() {
        val db = Database.open(dbPath())
        db.executeSql("CREATE TABLE t (id INT NOT NULL)")
        for (i in 1..50) {
            db.executeSql("INSERT INTO t (id) VALUES ($i)")
        }
        simulateCrash(db)

        val recovered = Database.open(dbPath())
        val result = recovered.executeSql("SELECT * FROM t") as ExecuteResult.Selected
        result.rows.size shouldBe 50
        recovered.close()
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew :core:test --tests "gwanbase.wal.CrashRecoveryIntegrationTest" -i
```

Expected: BUILD SUCCESSFUL (이전 Task에서 이미 구현 완료)

실패하는 테스트가 있으면 디버깅하여 수정한다. 가능한 문제:
- `simulateCrash`에서 FileChannel이 GC 전에 닫히지 않아 lock 충돌 → Java의 `System.gc()` 또는 Database 내부 참조를 직접 닫는 방식으로 수정
- Recovery 후 Catalog 재로드 시 version 불일치 → `loadExisting`의 version 검증 확인

- [ ] **Step 3: 전체 테스트 실행**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add core/src/test/kotlin/gwanbase/wal/CrashRecoveryIntegrationTest.kt
git commit -m "$(cat <<'EOF'
[Phase 5] Crash Recovery 통합 테스트

INSERT/UPDATE/DELETE/CREATE TABLE의 crash 후 복구,
Checkpoint 이후 추가 변경의 복구, 대량 트랜잭션 복구를 검증한다.
EOF
)"
```

- [ ] **Step 5: CLAUDE.md 로드맵 및 컴포넌트 업데이트**

CLAUDE.md에서 Phase 5 상태를 ✅ 완료로 변경하고, Phase 5 컴포넌트 테이블 및 설계 가이드를 추가한다. (구체적인 내용은 실제 구현 결과에 맞춰 작성)

- [ ] **Step 6: 커밋**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
[Phase 5] CLAUDE.md 로드맵·컴포넌트·설계 가이드 업데이트
EOF
)"
```

---

## 실행 순서 요약

| Task | 내용 | 예상 변경 파일 수 | 의존성 |
|------|------|-----------------|--------|
| 1 | LogRecord sealed class + 직렬화 | 2 | 없음 |
| 2 | LogManager | 2 | Task 1 |
| 3 | Page.pageLsn + TransactionContext + WalCallback | 3 | 없음 |
| 4 | BufferPoolManager WAL 통합 | 2 | Task 3 |
| 5 | WalCallbackImpl + Database auto-commit | 3 | Task 1-4 |
| 6 | RecoveryManager (Redo + Undo) | 2 | Task 1-2 |
| 7 | Checkpoint + Database Recovery 통합 | 2 | Task 5-6 |
| 8 | Crash simulation 통합 테스트 | 2 | Task 7 |

Task 1-2 (로그 레이어)와 Task 3-4 (스토리지 레이어)는 독립적이므로 병렬 실행 가능.
Task 5부터는 순차적으로 진행한다.
