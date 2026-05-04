package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import java.nio.ByteOrder

/**
 * 테이블 메타데이터.
 */
data class TableInfo(
    val tableId: Int,
    val name: String,
    val schema: Schema,
    val heapFileFirstPageId: Int,
)

/**
 * 컬럼 통계 정보.
 *
 * 쿼리 옵티마이저가 카디널리티 추정에 사용한다.
 */
data class ColumnStats(
    val distinctCount: Long,
    val minValue: Any?,
    val maxValue: Any?,
    val nullCount: Long,
)

/**
 * 인덱스 메타데이터.
 */
data class IndexInfo(
    val indexId: Int,
    val name: String,
    val tableName: String,
    val columnName: String,
    val rootPageId: Int,
)

/**
 * 테이블/인덱스 메타데이터를 전용 페이지에 영속 저장한다.
 *
 * Catalog 페이지 직렬화 포맷:
 * ```
 * [nextTableId: Int]
 * [tableCount: Int]
 * 반복 {
 *   [tableId: Int]
 *   [nameLength: Short][name: UTF-8 bytes]
 *   [heapFileFirstPageId: Int]
 *   [columnCount: Short]
 *   반복 {
 *     [colNameLength: Short][colName: UTF-8 bytes]
 *     [dataType: Byte]
 *     [maxLength: Short]
 *     [nullable: Byte]
 *   }
 * }
 * ```
 */
class Catalog(
    private val bpm: BufferPoolManager,
    val catalogPageId: Int,
) {
    private val tables = mutableListOf<TableInfo>()
    private var nextTableId: Int = 1
    private val indexes = mutableListOf<IndexInfo>()
    private var nextIndexId: Int = 1
    private val rowCounts = mutableMapOf<String, Long>()
    private val columnStatsMap = mutableMapOf<String, MutableMap<String, ColumnStats>>()

    companion object {
        /** 새 Catalog를 생성한다. 전용 페이지를 할당하고 빈 상태로 초기화한다. */
        fun createNew(bpm: BufferPoolManager): Catalog {
            val page = bpm.newPage() ?: error("Catalog 페이지 할당 실패")
            val pageId = page.pageId
            bpm.unpinPage(pageId, isDirty = false)
            val catalog = Catalog(bpm, pageId)
            catalog.flush()
            return catalog
        }

        /** 기존 Catalog를 로드한다. */
        fun load(bpm: BufferPoolManager, catalogPageId: Int): Catalog {
            val catalog = Catalog(bpm, catalogPageId)
            catalog.loadFromPage()
            return catalog
        }
    }

    /** 테이블을 생성한다. */
    fun createTable(name: String, schema: Schema): TableInfo {
        require(tables.none { it.name == name }) { "테이블 '$name'이 이미 존재한다" }

        val heapFile = HeapFile.createNew(bpm)
        val info = TableInfo(nextTableId++, name, schema, heapFile.firstPageId)
        tables.add(info)
        flush()
        return info
    }

    /** 이름으로 테이블 조회 */
    fun getTable(name: String): TableInfo? = tables.find { it.name == name }

    /** tableId로 테이블 조회 */
    fun getTable(tableId: Int): TableInfo? = tables.find { it.tableId == tableId }

    /** 모든 테이블 목록 */
    fun listTables(): List<TableInfo> = tables.toList()

    /** 테이블을 삭제한다. */
    fun dropTable(name: String): Boolean {
        val removed = tables.removeAll { it.name == name }
        if (removed) flush()
        return removed
    }

    // --- 인덱스 관리 ---

    /** 인덱스를 생성한다. */
    fun createIndex(name: String, tableName: String, columnName: String, rootPageId: Int): IndexInfo {
        require(indexes.none { it.name == name }) { "인덱스 '$name'이 이미 존재한다" }
        val info = IndexInfo(nextIndexId++, name, tableName, columnName, rootPageId)
        indexes.add(info)
        flush()
        return info
    }

    /** 이름으로 인덱스를 조회한다. */
    fun getIndex(name: String): IndexInfo? = indexes.find { it.name == name }

    /** 특정 테이블에 속한 인덱스 목록을 반환한다. */
    fun getIndexesForTable(tableName: String): List<IndexInfo> =
        indexes.filter { it.tableName == tableName }

    /** 인덱스를 삭제한다. */
    fun dropIndex(name: String): Boolean {
        val removed = indexes.removeAll { it.name == name }
        if (removed) flush()
        return removed
    }

    // --- 통계 관리 ---

    /** 테이블의 행 수를 반환한다. */
    fun getRowCount(tableName: String): Long = rowCounts.getOrDefault(tableName, 0L)

    /** 테이블의 행 수를 1 증가시킨다. */
    fun incrementRowCount(tableName: String) {
        rowCounts[tableName] = getRowCount(tableName) + 1
    }

    /** 테이블의 행 수를 1 감소시킨다. 0 미만으로 내려가지 않는다. */
    fun decrementRowCount(tableName: String) {
        val current = getRowCount(tableName)
        if (current > 0) {
            rowCounts[tableName] = current - 1
        }
    }

    /** 컬럼 통계를 갱신한다. */
    fun updateColumnStats(tableName: String, columnName: String, stats: ColumnStats) {
        columnStatsMap.getOrPut(tableName) { mutableMapOf() }[columnName] = stats
        flush()
    }

    /** 특정 컬럼의 통계를 조회한다. */
    fun getColumnStats(tableName: String, columnName: String): ColumnStats? =
        columnStatsMap[tableName]?.get(columnName)

    /** 테이블의 모든 컬럼 통계를 조회한다. */
    fun getAllColumnStats(tableName: String): Map<String, ColumnStats> =
        columnStatsMap[tableName]?.toMap() ?: emptyMap()

    // --- 직렬화/역직렬화 ---

    /** 현재 테이블·인덱스 목록의 직렬화 예상 크기를 바이트 단위로 반환한다. */
    private fun estimateSerializedSize(): Int {
        var size = 4 + 4 // nextTableId + tableCount
        for (info in tables) {
            val nameBytes = info.name.toByteArray(Charsets.UTF_8).size
            size += 4 + 2 + nameBytes + 4 + 2 // tableId, nameLen, name, heapFileFirstPageId, colCount
            for (col in info.schema.columns) {
                val colNameBytes = col.name.toByteArray(Charsets.UTF_8).size
                size += 2 + colNameBytes + 1 + 2 + 1 // colNameLen, colName, dataType, maxLength, nullable
            }
        }
        // 인덱스 섹션
        size += 4 + 4 // nextIndexId + indexCount
        for (idx in indexes) {
            val nameBytes = idx.name.toByteArray(Charsets.UTF_8).size
            val tableNameBytes = idx.tableName.toByteArray(Charsets.UTF_8).size
            val colNameBytes = idx.columnName.toByteArray(Charsets.UTF_8).size
            size += 4 + 2 + nameBytes + 2 + tableNameBytes + 2 + colNameBytes + 4
            // indexId, nameLen, name, tableNameLen, tableName, colNameLen, colName, rootPageId
        }
        // 통계 섹션
        size += 4 // statsTableCount
        for ((tableName, colStats) in columnStatsMap) {
            val tblNameBytes = tableName.toByteArray(Charsets.UTF_8).size
            size += 2 + tblNameBytes // tableNameLen, tableName
            size += 8 // rowCount
            size += 2 // columnStatsCount
            for ((colName, _) in colStats) {
                val colNameBytes = colName.toByteArray(Charsets.UTF_8).size
                size += 2 + colNameBytes // colNameLen, colName
                size += 8 + 1 + 8 + 8 + 8 // distinctCount, hasMinMax, minValue, maxValue, nullCount
            }
        }
        return size
    }

    private fun flush() {
        val estimatedSize = estimateSerializedSize()
        check(estimatedSize <= DiskManager.PAGE_SIZE) {
            "Catalog 직렬화 크기(${estimatedSize})가 페이지 크기(${DiskManager.PAGE_SIZE})를 초과한다"
        }
        val page = bpm.fetchPage(catalogPageId) ?: error("Catalog 페이지 조회 실패")
        try {
            val buf = page.data
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.position(0)

            buf.putInt(nextTableId)
            buf.putInt(tables.size)

            for (info in tables) {
                buf.putInt(info.tableId)

                val nameBytes = info.name.toByteArray(Charsets.UTF_8)
                buf.putShort(nameBytes.size.toShort())
                buf.put(nameBytes)

                buf.putInt(info.heapFileFirstPageId)
                buf.putShort(info.schema.columnCount.toShort())

                for (col in info.schema.columns) {
                    val colNameBytes = col.name.toByteArray(Charsets.UTF_8)
                    buf.putShort(colNameBytes.size.toShort())
                    buf.put(colNameBytes)
                    buf.put(col.type.ordinal.toByte())
                    buf.putShort(col.maxLength.toShort())
                    buf.put(if (col.nullable) 1.toByte() else 0.toByte())
                }
            }

            // 인덱스 섹션
            buf.putInt(nextIndexId)
            buf.putInt(indexes.size)
            for (idx in indexes) {
                buf.putInt(idx.indexId)

                val nameBytes = idx.name.toByteArray(Charsets.UTF_8)
                buf.putShort(nameBytes.size.toShort())
                buf.put(nameBytes)

                val tableNameBytes = idx.tableName.toByteArray(Charsets.UTF_8)
                buf.putShort(tableNameBytes.size.toShort())
                buf.put(tableNameBytes)

                val colNameBytes = idx.columnName.toByteArray(Charsets.UTF_8)
                buf.putShort(colNameBytes.size.toShort())
                buf.put(colNameBytes)

                buf.putInt(idx.rootPageId)
            }

            // 통계 섹션
            buf.putInt(columnStatsMap.size)
            for ((tableName, colStats) in columnStatsMap) {
                val tblNameBytes = tableName.toByteArray(Charsets.UTF_8)
                buf.putShort(tblNameBytes.size.toShort())
                buf.put(tblNameBytes)

                buf.putLong(rowCounts.getOrDefault(tableName, 0L))

                buf.putShort(colStats.size.toShort())
                for ((colName, stats) in colStats) {
                    val colNameBytes = colName.toByteArray(Charsets.UTF_8)
                    buf.putShort(colNameBytes.size.toShort())
                    buf.put(colNameBytes)

                    buf.putLong(stats.distinctCount)
                    val hasMinMax = stats.minValue is Number && stats.maxValue is Number
                    buf.put(if (hasMinMax) 1.toByte() else 0.toByte())
                    buf.putLong(if (hasMinMax) (stats.minValue as Number).toLong() else 0L)
                    buf.putLong(if (hasMinMax) (stats.maxValue as Number).toLong() else 0L)
                    buf.putLong(stats.nullCount)
                }
            }
        } finally {
            bpm.unpinPage(catalogPageId, isDirty = true)
        }
    }

    private fun loadFromPage() {
        val page = bpm.fetchPage(catalogPageId) ?: error("Catalog 페이지 조회 실패")
        try {
            val buf = page.data
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.position(0)

            nextTableId = buf.getInt()
            val tableCount = buf.getInt()

            tables.clear()
            repeat(tableCount) {
                val tableId = buf.getInt()

                val nameLen = buf.getShort().toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)

                val heapFileFirstPageId = buf.getInt()
                val columnCount = buf.getShort().toInt() and 0xFFFF

                val columns = (0 until columnCount).map {
                    val colNameLen = buf.getShort().toInt() and 0xFFFF
                    val colNameBytes = ByteArray(colNameLen)
                    buf.get(colNameBytes)
                    val colName = String(colNameBytes, Charsets.UTF_8)
                    val dataType = DataType.entries[buf.get().toInt() and 0xFF]
                    val maxLength = buf.getShort().toInt() and 0xFFFF
                    val nullable = buf.get() != 0.toByte()
                    Column(colName, dataType, maxLength, nullable)
                }

                tables.add(TableInfo(tableId, name, Schema(columns), heapFileFirstPageId))
            }

            // 인덱스 섹션 (하위 호환: 이전 포맷에는 없을 수 있음)
            indexes.clear()
            if (buf.remaining() >= 8) {
                nextIndexId = buf.getInt()
                val indexCount = buf.getInt()
                repeat(indexCount) {
                    val indexId = buf.getInt()

                    val nameLen = buf.getShort().toInt() and 0xFFFF
                    val nameBytes = ByteArray(nameLen)
                    buf.get(nameBytes)
                    val idxName = String(nameBytes, Charsets.UTF_8)

                    val tableNameLen = buf.getShort().toInt() and 0xFFFF
                    val tableNameBytes = ByteArray(tableNameLen)
                    buf.get(tableNameBytes)
                    val tblName = String(tableNameBytes, Charsets.UTF_8)

                    val colNameLen = buf.getShort().toInt() and 0xFFFF
                    val colNameBytes = ByteArray(colNameLen)
                    buf.get(colNameBytes)
                    val colName = String(colNameBytes, Charsets.UTF_8)

                    val rootPageId = buf.getInt()

                    indexes.add(IndexInfo(indexId, idxName, tblName, colName, rootPageId))
                }
            }

            // 통계 섹션 (하위 호환: 이전 포맷에는 없을 수 있음)
            rowCounts.clear()
            columnStatsMap.clear()
            if (buf.remaining() >= 4) {
                val statsTableCount = buf.getInt()
                repeat(statsTableCount) {
                    val tblNameLen = buf.getShort().toInt() and 0xFFFF
                    val tblNameBytes = ByteArray(tblNameLen)
                    buf.get(tblNameBytes)
                    val tblName = String(tblNameBytes, Charsets.UTF_8)

                    val rowCount = buf.getLong()
                    rowCounts[tblName] = rowCount

                    val colStatsCount = buf.getShort().toInt() and 0xFFFF
                    val colStats = mutableMapOf<String, ColumnStats>()
                    repeat(colStatsCount) {
                        val colNameLen = buf.getShort().toInt() and 0xFFFF
                        val colNameBytes = ByteArray(colNameLen)
                        buf.get(colNameBytes)
                        val colName = String(colNameBytes, Charsets.UTF_8)

                        val distinctCount = buf.getLong()
                        val hasMinMax = buf.get() != 0.toByte()
                        val minRaw = buf.getLong()
                        val maxRaw = buf.getLong()
                        val nullCount = buf.getLong()

                        val minValue: Any? = if (hasMinMax) minRaw else null
                        val maxValue: Any? = if (hasMinMax) maxRaw else null
                        colStats[colName] = ColumnStats(distinctCount, minValue, maxValue, nullCount)
                    }
                    columnStatsMap[tblName] = colStats
                }
            }
        } finally {
            bpm.unpinPage(catalogPageId, isDirty = false)
        }
    }
}
