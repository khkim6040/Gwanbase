package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FilterOperatorTest {

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
    fun `조건 일치하는 행만 통과`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 70)
        insertStudent(3, "Charlie", 85)

        val predicate = Expression.BinaryOp(
            Expression.ColumnRef("score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(80),
        )
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        val results = collectAll(filter)
        results.size shouldBe 2
        results.map { it.getInt(0) }.toSet() shouldBe setOf(1, 3)
    }

    @Test
    fun `전부 필터링되면 빈 결과`() {
        insertStudent(1, "Alice", 50)
        insertStudent(2, "Bob", 40)

        val predicate = Expression.BinaryOp(
            Expression.ColumnRef("score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(100),
        )
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        collectAll(filter).size shouldBe 0
    }

    @Test
    fun `전부 통과하면 입력과 동일`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)

        val predicate = Expression.BinaryOp(
            Expression.ColumnRef("score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(50),
        )
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        collectAll(filter).size shouldBe 2
    }

    @Test
    fun `IS NULL 필터링`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, null, 80)

        val predicate = Expression.IsNull(Expression.ColumnRef("name"))
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        val results = collectAll(filter)
        results.size shouldBe 1
        results[0].getInt(0) shouldBe 2
    }

    @Test
    fun `IS NOT NULL 필터링`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, null, 80)

        val predicate = Expression.IsNotNull(Expression.ColumnRef("name"))
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        val results = collectAll(filter)
        results.size shouldBe 1
        results[0].getInt(0) shouldBe 1
    }

    @Test
    fun `AND 복합 조건`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 70)
        insertStudent(3, "Charlie", 85)

        val predicate = Expression.BinaryOp(
            Expression.BinaryOp(
                Expression.ColumnRef("score"),
                BinaryOperator.GTE,
                Expression.IntLiteral(80),
            ),
            BinaryOperator.AND,
            Expression.BinaryOp(
                Expression.ColumnRef("name"),
                BinaryOperator.NEQ,
                Expression.StringLiteral("Alice"),
            ),
        )
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        val results = collectAll(filter)
        results.size shouldBe 1
        results[0].getString(1) shouldBe "Charlie"
    }

    @Test
    fun `outputSchema가 child와 동일`() {
        val scan = SeqScanOperator(database, "students")
        val predicate = Expression.BoolLiteral(true)
        val filter = FilterOperator(scan, predicate)

        filter.outputSchema.columnCount shouldBe scan.outputSchema.columnCount
    }

    @Test
    fun `NULL 비교 결과로 필터링 - NULL은 통과하지 못함`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", null)

        // score = 90 → NULL score는 NULL = 90 → NULL → false → 탈락
        val predicate = Expression.BinaryOp(
            Expression.ColumnRef("score"),
            BinaryOperator.EQ,
            Expression.IntLiteral(90),
        )
        val filter = FilterOperator(SeqScanOperator(database, "students"), predicate)

        val results = collectAll(filter)
        results.size shouldBe 1
        results[0].getInt(0) shouldBe 1
    }
}
