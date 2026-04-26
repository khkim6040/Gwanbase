package gwanbase.execution

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SortOperatorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        val schema = Schema(
            listOf(
                Column("id", DataType.INT32, nullable = false),
                Column("name", DataType.VARCHAR, maxLength = 50, nullable = true),
                Column("score", DataType.INT32, nullable = true),
            )
        )
        database.createTable("students", schema)
    }

    @AfterEach
    fun tearDown() { database.close() }

    private fun insertStudent(id: Int, name: String?, score: Int?) {
        val schema = database.getTable("students")!!.schema
        database.insertTuple("students", Tuple(schema, arrayOf(id, name, score)))
    }

    private fun collectAll(op: Operator): List<Tuple> {
        op.open()
        val result = mutableListOf<Tuple>()
        var tuple = op.next()
        while (tuple != null) {
            result.add(tuple)
            tuple = op.next()
        }
        op.close()
        return result
    }

    @Test
    fun `오름차순 정렬`() {
        insertStudent(1, "Charlie", 70)
        insertStudent(2, "Alice", 90)
        insertStudent(3, "Bob", 80)

        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = true)
        val results = collectAll(sort)

        results.map { it.getInt(2) } shouldBe listOf(70, 80, 90)
    }

    @Test
    fun `내림차순 정렬`() {
        insertStudent(1, "Charlie", 70)
        insertStudent(2, "Alice", 90)
        insertStudent(3, "Bob", 80)

        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = false)
        val results = collectAll(sort)

        results.map { it.getInt(2) } shouldBe listOf(90, 80, 70)
    }

    @Test
    fun `문자열 오름차순 정렬`() {
        insertStudent(1, "Charlie", 70)
        insertStudent(2, "Alice", 90)
        insertStudent(3, "Bob", 80)

        val sort = SortOperator(SeqScanOperator(database, "students"), "name", ascending = true)
        val results = collectAll(sort)

        results.map { it.getString(1) } shouldBe listOf("Alice", "Bob", "Charlie")
    }

    @Test
    fun `NULL 값 정렬 시 nulls last`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", null)
        insertStudent(3, "Charlie", 70)

        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = true)
        val results = collectAll(sort)

        results.map { it.getInt(2) } shouldBe listOf(70, 90, null)
    }

    @Test
    fun `빈 입력이면 빈 결과`() {
        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = true)
        collectAll(sort).size shouldBe 0
    }

    @Test
    fun `이미 정렬된 입력`() {
        insertStudent(1, "Alice", 70)
        insertStudent(2, "Bob", 80)
        insertStudent(3, "Charlie", 90)

        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = true)
        val results = collectAll(sort)

        results.map { it.getInt(2) } shouldBe listOf(70, 80, 90)
    }

    @Test
    fun `outputSchema가 child와 동일`() {
        val sort = SortOperator(SeqScanOperator(database, "students"), "score", ascending = true)
        sort.outputSchema.columnCount shouldBe 3
    }
}
