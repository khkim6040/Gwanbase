package gwanbase.wal

import gwanbase.storage.BufferPoolManager
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ARIES 스타일 2-Phase 크래시 리커버리 매니저.
 *
 * Consistent Checkpoint 기반으로 Redo → Undo 두 단계를 수행한다.
 *
 * **Redo 단계** (순방향 스캔):
 * - 모든 로그 레코드를 순서대로 순회하며, 페이지에 아직 반영되지 않은
 *   변경(pageLsn < record.lsn)을 재적용한다.
 * - 활성 트랜잭션 테이블(activeTxns)과 커밋된 트랜잭션 집합을 구축한다.
 *
 * **Undo 단계** (역방향 워크):
 * - 커밋되지 않은 트랜잭션의 변경을 역순으로 롤백한다.
 * - 각 Undo 동작마다 CLR(Compensation Log Record)을 기록하여
 *   반복 크래시에도 멱등성을 보장한다.
 *
 * @param logManager WAL 로그 매니저
 * @param bpm 버퍼 풀 매니저 (walCallback이 null인 상태에서 호출됨)
 */
class RecoveryManager(
    private val logManager: LogManager,
    private val bpm: BufferPoolManager,
) {

    /**
     * 크래시 리커버리를 수행하고, 다음에 사용할 트랜잭션 ID를 반환한다.
     *
     * @return 다음 트랜잭션 ID (리커버리 중 관측된 최대 txnId + 1, 로그가 비어 있으면 0)
     */
    fun recover(): Int {
        logger.info { "리커버리 시작" }

        // 활성 트랜잭션: txnId → 마지막 LSN
        val activeTxns = mutableMapOf<Int, Int>()
        var maxTxnId = -1

        // ─── Redo 단계 ──────────────────────────────────────────────────────
        val startLsn = maxOf(logManager.lastCheckpointLsn() + 1, 0)
        val redoIterator = logManager.forwardIterator(startLsn)

        while (redoIterator.hasNext()) {
            val record = redoIterator.next()

            // 최대 txnId 추적
            if (record.txnId >= 0) {
                maxTxnId = maxOf(maxTxnId, record.txnId)
            }

            when (record) {
                is LogRecord.Begin -> {
                    activeTxns[record.txnId] = record.lsn
                    logger.debug { "Redo: Begin txn=${record.txnId} lsn=${record.lsn}" }
                }

                is LogRecord.Commit -> {
                    activeTxns.remove(record.txnId)
                    logger.debug { "Redo: Commit txn=${record.txnId} lsn=${record.lsn}" }
                }

                is LogRecord.Abort -> {
                    activeTxns.remove(record.txnId)
                    logger.debug { "Redo: Abort txn=${record.txnId} lsn=${record.lsn}" }
                }

                is LogRecord.Update -> {
                    activeTxns[record.txnId] = record.lsn
                    redoUpdate(record.pageId, record.lsn, record.afterImage)
                }

                is LogRecord.CLR -> {
                    activeTxns[record.txnId] = record.lsn
                    redoUpdate(record.pageId, record.lsn, record.beforeImage)
                }

                is LogRecord.Checkpoint -> {
                    logger.debug { "Redo: Checkpoint lsn=${record.lsn} — skip" }
                }
            }
        }

        logger.info { "Redo 단계 완료. 미커밋 트랜잭션 ${activeTxns.size}개" }

        // ─── Undo 단계 ──────────────────────────────────────────────────────
        while (activeTxns.isNotEmpty()) {
            // 가장 높은 lastLsn을 가진 트랜잭션부터 undo
            val (txnId, lastLsn) = activeTxns.maxByOrNull { it.value }!!

            if (lastLsn < 0) {
                // prevLsn이 없으므로 Begin에 도달 — Abort 기록 후 제거
                val abortLsn = logManager.appendAbort(txnId = txnId, prevLsn = lastLsn)
                logManager.flush(abortLsn)
                activeTxns.remove(txnId)
                logger.debug { "Undo: Abort txn=$txnId (Begin 도달)" }
                continue
            }

            val record = logManager.getRecord(lastLsn)

            when (record) {
                is LogRecord.Update -> {
                    // beforeImage를 페이지에 복원
                    val page = bpm.fetchPage(record.pageId)
                        ?: error("Undo: 페이지 ${record.pageId} fetch 실패")
                    page.data.clear()
                    page.data.put(record.beforeImage)
                    page.data.flip()
                    page.isDirty = true
                    bpm.unpinPage(record.pageId, isDirty = true)

                    // CLR 기록
                    val clrLsn = logManager.appendCLR(
                        txnId = txnId,
                        prevLsn = lastLsn,
                        pageId = record.pageId,
                        beforeImage = record.beforeImage,
                        undoNextLsn = record.prevLsn,
                    )
                    logManager.flush(clrLsn)

                    // activeTxns 갱신: 다음에 undo할 레코드의 LSN
                    if (record.prevLsn < 0) {
                        // Begin에 도달 — Abort 기록 후 제거
                        val abortLsn = logManager.appendAbort(txnId = txnId, prevLsn = clrLsn)
                        logManager.flush(abortLsn)
                        activeTxns.remove(txnId)
                        logger.debug { "Undo: Update txn=$txnId lsn=${record.lsn} → Abort" }
                    } else {
                        activeTxns[txnId] = record.prevLsn
                        logger.debug { "Undo: Update txn=$txnId lsn=${record.lsn} → prevLsn=${record.prevLsn}" }
                    }
                }

                is LogRecord.CLR -> {
                    // CLR은 undo하지 않고, undoNextLsn으로 건너뛴다
                    if (record.undoNextLsn < 0) {
                        val abortLsn = logManager.appendAbort(txnId = txnId, prevLsn = lastLsn)
                        logManager.flush(abortLsn)
                        activeTxns.remove(txnId)
                        logger.debug { "Undo: CLR txn=$txnId → Abort (undoNextLsn < 0)" }
                    } else {
                        activeTxns[txnId] = record.undoNextLsn
                        logger.debug { "Undo: CLR txn=$txnId → undoNextLsn=${record.undoNextLsn}" }
                    }
                }

                is LogRecord.Begin -> {
                    // Begin에 도달 — Abort 기록 후 제거
                    val abortLsn = logManager.appendAbort(txnId = txnId, prevLsn = lastLsn)
                    logManager.flush(abortLsn)
                    activeTxns.remove(txnId)
                    logger.debug { "Undo: Begin txn=$txnId → Abort" }
                }

                else -> {
                    // Commit/Abort/Checkpoint는 activeTxns에 있을 수 없음
                    error("Undo 단계에서 예상치 못한 레코드 타입: $record")
                }
            }
        }

        logger.info { "Undo 단계 완료" }

        // 리커버리된 페이지를 디스크에 영속화
        bpm.flushAllPages()

        logger.info { "리커버리 완료. 다음 txnId=${maxTxnId + 1}" }
        return maxTxnId + 1
    }

    /**
     * Redo 단계에서 페이지에 이미지를 적용한다.
     * pageLsn이 record LSN보다 작을 때만 적용한다.
     */
    private fun redoUpdate(pageId: Int, lsn: Int, image: ByteArray) {
        val page = bpm.fetchPage(pageId)
            ?: error("Redo: 페이지 ${pageId}를 fetch할 수 없음")

        if (page.pageLsn < lsn) {
            page.data.clear()
            page.data.put(image)
            page.data.flip()
            page.pageLsn = lsn
            page.isDirty = true
            bpm.unpinPage(pageId, isDirty = true)
            logger.debug { "Redo: pageId=$pageId lsn=$lsn 적용" }
        } else {
            bpm.unpinPage(pageId)
            logger.debug { "Redo: pageId=$pageId lsn=$lsn skip (pageLsn=${page.pageLsn})" }
        }
    }
}
