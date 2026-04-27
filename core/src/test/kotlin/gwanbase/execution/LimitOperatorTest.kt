package gwanbase.execution

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LimitOperatorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        val schema = Schema(
            listOf(
                Column("id", DataType.INT32, nullable = false),
                Column("name", DataType.VARCHAR, maxLength = 50, nullable = true),
            )
        )
        database.createTable("students", schema)
    }

    @AfterEach
    fun tearDown() { database.close() }

    private fun insertStudent(id: Int, name: String?) {
        val schema = database.getTable("students")!!.schema
        database.insertTuple("students", Tuple(schema, arrayOf(id, name)))
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
    fun `LIMIT 적용 시 상위 N개만 반환`() {
        insertStudent(1, "Alice")
        insertStudent(2, "Bob")
        insertStudent(3, "Charlie")

        val limit = LimitOperator(SeqScanOperator(database, "students"), 2)
        collectAll(limit).size shouldBe 2
    }

    @Test
    fun `LIMIT가 전체 행 수보다 크면 전체 반환`() {
        insertStudent(1, "Alice")
        insertStudent(2, "Bob")

        val limit = LimitOperator(SeqScanOperator(database, "students"), 10)
        collectAll(limit).size shouldBe 2
    }

    @Test
    fun `LIMIT 0이면 빈 결과`() {
        insertStudent(1, "Alice")

        val limit = LimitOperator(SeqScanOperator(database, "students"), 0)
        collectAll(limit).size shouldBe 0
    }

    @Test
    fun `빈 입력에 LIMIT 적용`() {
        val limit = LimitOperator(SeqScanOperator(database, "students"), 5)
        collectAll(limit).size shouldBe 0
    }

    @Test
    fun `outputSchema가 child와 동일`() {
        val limit = LimitOperator(SeqScanOperator(database, "students"), 2)
        limit.outputSchema.columnCount shouldBe 2
    }
}
