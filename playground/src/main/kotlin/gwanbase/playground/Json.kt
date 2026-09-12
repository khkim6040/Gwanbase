package gwanbase.playground

import gwanbase.sql.ExecuteResult
import gwanbase.table.Catalog

/**
 * 의존성 없이 응답 JSON을 만드는 최소 인코더.
 *
 * 요청 본문은 SQL 텍스트를 그대로 받으므로 디코더는 필요 없다.
 * `Map`은 객체, `Iterable`은 배열, 문자열·숫자·불리언·null은 리터럴, 그 외는 `toString()` 문자열이다.
 */
object Json {

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Float -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    /**
     * 실행 결과를 응답 맵으로 바꾼다. `txn`은 PG 프로토콜 ReadyForQuery 상태(I/T/E)와 같은 의미다.
     * `Selected`/`Explained`는 [maxRows]에서 자르고 `truncated`를 표시한다.
     */
    fun result(result: ExecuteResult, txn: Char, maxRows: Int): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>("kind" to result::class.simpleName)
        when (result) {
            is ExecuteResult.Selected -> base.putRows(result.columns, result.rows, maxRows)
            is ExecuteResult.Explained -> base.putRows(listOf("QUERY PLAN"), result.planText.split("\n").map { listOf(it) }, maxRows)
            is ExecuteResult.Updated -> base["count"] = result.count
            is ExecuteResult.Deleted -> base["count"] = result.count
            is ExecuteResult.Inserted -> base["message"] = "INSERT 1"
            is ExecuteResult.Created -> base["message"] = "CREATE TABLE ${result.tableName}"
            is ExecuteResult.Dropped -> base["message"] = "DROP TABLE ${result.tableName}"
            is ExecuteResult.IndexCreated -> base["message"] = "CREATE INDEX ${result.indexName}"
            is ExecuteResult.IndexDropped -> base["message"] = "DROP INDEX ${result.indexName}"
            is ExecuteResult.Analyzed -> base["message"] = "ANALYZE ${result.tableName} (${result.rowCount} rows)"
            ExecuteResult.TransactionStarted -> base["message"] = "BEGIN"
            ExecuteResult.TransactionCommitted -> base["message"] = "COMMIT"
            ExecuteResult.TransactionRolledBack -> base["message"] = "ROLLBACK"
        }
        base["txn"] = txn.toString()
        return base
    }

    private fun MutableMap<String, Any?>.putRows(columns: List<String>, rows: List<List<Any?>>, maxRows: Int) {
        this["columns"] = columns
        this["rows"] = rows.take(maxRows)
        this["count"] = rows.size
        this["truncated"] = rows.size > maxRows
    }

    /** 사이드바용 스키마: 테이블 → 컬럼(name/type/nullable), 인덱스(name/column/unique). */
    fun schema(catalog: Catalog): Map<String, Any?> = mapOf(
        "tables" to catalog.listTables().map { table ->
            mapOf(
                "name" to table.name,
                "columns" to table.schema.columns.map {
                    mapOf("name" to it.name, "type" to it.type.name, "nullable" to it.nullable)
                },
                "indexes" to catalog.getIndexesForTable(table.name).map {
                    mapOf("name" to it.name, "column" to it.columnName, "unique" to it.unique)
                },
            )
        },
    )

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
