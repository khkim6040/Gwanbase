package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProjectOperatorTest {

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
    fun `SELECT STAR는 전체 컬럼 그대로 반환`() {
        insertStudent(1, "Alice", 90)

        val projections = listOf(SelectItem.Star)
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        val results = collectAll(project)
        results.size shouldBe 1
        results[0].schema.columnCount shouldBe 3
        results[0].getInt(0) shouldBe 1
        results[0].getString(1) shouldBe "Alice"
        results[0].getInt(2) shouldBe 90
    }

    @Test
    fun `특정 컬럼만 선택`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)

        val projections = listOf(
            SelectItem.ExprItem(Expression.ColumnRef(null,"name")),
            SelectItem.ExprItem(Expression.ColumnRef(null,"score")),
        )
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        project.outputSchema.columnCount shouldBe 2
        project.outputSchema.column(0).name shouldBe "name"
        project.outputSchema.column(1).name shouldBe "score"

        val results = collectAll(project)
        results.size shouldBe 2
        results.forEach { tuple ->
            tuple.schema.columnCount shouldBe 2
        }
    }

    @Test
    fun `컬럼 순서 변경`() {
        insertStudent(1, "Alice", 90)

        val projections = listOf(
            SelectItem.ExprItem(Expression.ColumnRef(null,"score")),
            SelectItem.ExprItem(Expression.ColumnRef(null,"id")),
        )
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        val results = collectAll(project)
        results[0].getInt(0) shouldBe 90
        results[0].getInt(1) shouldBe 1
    }

    @Test
    fun `표현식 프로젝션`() {
        insertStudent(1, "Alice", 90)

        // score + 10
        val projections = listOf(
            SelectItem.ExprItem(Expression.ColumnRef(null,"name")),
            SelectItem.ExprItem(
                Expression.BinaryOp(
                    Expression.ColumnRef(null,"score"),
                    BinaryOperator.ADD,
                    Expression.IntLiteral(10),
                )
            ),
        )
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        val results = collectAll(project)
        results[0].getString(0) shouldBe "Alice"
        // INT32(score=90) + Long(10) → Long(100) → ProjectOperator에서 Long으로 저장
        results[0].getLong(1) shouldBe 100L
    }

    @Test
    fun `빈 입력이면 빈 결과`() {
        val projections = listOf(SelectItem.ExprItem(Expression.ColumnRef(null,"name")))
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        collectAll(project).size shouldBe 0
    }

    @Test
    fun `NULL 값 프로젝션`() {
        insertStudent(1, null, null)

        val projections = listOf(
            SelectItem.ExprItem(Expression.ColumnRef(null,"name")),
            SelectItem.ExprItem(Expression.ColumnRef(null,"score")),
        )
        val project = ProjectOperator(SeqScanOperator(database, "students"), projections)

        val results = collectAll(project)
        results[0].isNull(0) shouldBe true
        results[0].isNull(1) shouldBe true
    }
}
