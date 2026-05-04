package gwanbase.wal

import gwanbase.storage.DiskManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAL 로그 레코드.
 *
 * 각 레코드는 LSN(Log Sequence Number)으로 식별되며,
 * prevLsn으로 같은 트랜잭션의 이전 레코드를 역추적할 수 있다.
 *
 * 바이너리 포맷:
 * ```
 * [totalLength:Int(4)][type:Byte(1)][lsn:Int(4)][txnId:Int(4)][prevLsn:Int(4)][...payload...][totalLength:Int(4)]
 * ```
 * - BEGIN/COMMIT/ABORT/CHECKPOINT: payload 없음 (21 bytes)
 * - UPDATE: `[pageId:4][beforeImage:4096][afterImage:4096]` (8217 bytes)
 * - CLR: `[pageId:4][beforeImage:4096][undoNextLsn:4]` (4125 bytes)
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
        init {
            require(beforeImage.size == DiskManager.PAGE_SIZE) {
                "beforeImage 크기가 PAGE_SIZE(${DiskManager.PAGE_SIZE})여야 한다: ${beforeImage.size}"
            }
            require(afterImage.size == DiskManager.PAGE_SIZE) {
                "afterImage 크기가 PAGE_SIZE(${DiskManager.PAGE_SIZE})여야 한다: ${afterImage.size}"
            }
        }

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
        init {
            require(beforeImage.size == DiskManager.PAGE_SIZE) {
                "beforeImage 크기가 PAGE_SIZE(${DiskManager.PAGE_SIZE})여야 한다: ${beforeImage.size}"
            }
        }

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
        private const val TYPE_BEGIN: Byte = 0
        private const val TYPE_COMMIT: Byte = 1
        private const val TYPE_ABORT: Byte = 2
        private const val TYPE_UPDATE: Byte = 3
        private const val TYPE_CLR: Byte = 4
        private const val TYPE_CHECKPOINT: Byte = 5

        /** 헤더: totalLength(4) + type(1) + lsn(4) + txnId(4) + prevLsn(4) = 17 bytes */
        private const val HEADER_SIZE = 17

        /** 후미: totalLength(4) = 4 bytes */
        private const val FOOTER_SIZE = 4

        /**
         * 로그 레코드를 바이트 배열로 직렬화한다.
         * 선두와 후미 모두 totalLength를 기록하여 역방향 스캔을 지원한다.
         */
        fun serialize(record: LogRecord): ByteArray {
            val payloadSize = when (record) {
                is Begin, is Commit, is Abort, is Checkpoint -> 0
                is Update -> 4 + DiskManager.PAGE_SIZE * 2
                is CLR -> 4 + DiskManager.PAGE_SIZE + 4
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
         * ByteBuffer에서 로그 레코드를 역직렬화한다.
         * 버퍼의 현재 position에서 읽기 시작한다.
         */
        fun deserialize(buf: ByteBuffer): LogRecord {
            buf.getInt() // totalLength (선두)
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

            buf.getInt() // totalLength (후미)
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
