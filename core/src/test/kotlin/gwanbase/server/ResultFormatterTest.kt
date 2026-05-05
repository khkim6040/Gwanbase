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
        messages.size shouldBe 2
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
    fun `Explained — QUERY PLAN 컬럼과 줄별 DataRow`() {
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
