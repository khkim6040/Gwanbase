package gwanbase.server

import gwanbase.sql.ExecuteResult

/**
 * ExecuteResult를 PostgreSQL 응답 메시지 리스트로 변환한다.
 *
 * ReadyForQuery는 포함하지 않는다 — ConnectionHandler가
 * 트랜잭션 상태를 판단하여 직접 추가한다.
 */
object ResultFormatter {

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
