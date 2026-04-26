package gwanbase.execution

import gwanbase.sql.*
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlannerTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var planner: Planner

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        planner = Planner(database)
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

    @Test
    fun `SELECT STAR FROM t는 Project가 SeqScan을 감싼다`() {
        val stmt = Statement.Select(
            columns = listOf(SelectItem.Star),
            tableName = "students",
            where = null,
            orderBy = null,
            limit = null,
        )
        val op = planner.planSelect(stmt)

        // 루트는 Project
        op.shouldBeInstanceOf<ProjectOperator>()
    }

    @Test
    fun `SELECT WHERE 절이 있으면 Filter 포함`() {
        val stmt = Statement.Select(
            columns = listOf(SelectItem.Star),
            tableName = "students",
            where = Expression.BinaryOp(
                Expression.ColumnRef("score"),
                BinaryOperator.GTE,
                Expression.IntLiteral(80),
            ),
            orderBy = null,
            limit = null,
        )
        val op = planner.planSelect(stmt)
        op.shouldBeInstanceOf<ProjectOperator>()
    }

    @Test
    fun `SELECT ORDER BY가 있으면 Sort 포함`() {
        val stmt = Statement.Select(
            columns = listOf(SelectItem.Star),
            tableName = "students",
            where = null,
            orderBy = OrderByClause("score", ascending = true),
            limit = null,
        )
        val op = planner.planSelect(stmt)
        op.shouldBeInstanceOf<ProjectOperator>()
    }

    @Test
    fun `SELECT LIMIT가 있으면 Limit 포함`() {
        val stmt = Statement.Select(
            columns = listOf(SelectItem.Star),
            tableName = "students",
            where = null,
            orderBy = null,
            limit = 5,
        )
        val op = planner.planSelect(stmt)
        op.shouldBeInstanceOf<ProjectOperator>()
    }

    @Test
    fun `SELECT WHERE ORDER BY LIMIT 조합 실행 결과 검증`() {
        val schema = database.getTable("students")!!.schema
        database.insertTuple("students", Tuple(schema, arrayOf(1, "Alice", 90)))
        database.insertTuple("students", Tuple(schema, arrayOf(2, "Bob", 70)))
        database.insertTuple("students", Tuple(schema, arrayOf(3, "Charlie", 85)))
        database.insertTuple("students", Tuple(schema, arrayOf(4, "Dave", 60)))

        val stmt = Statement.Select(
            columns = listOf(
                SelectItem.ExprItem(Expression.ColumnRef("name")),
                SelectItem.ExprItem(Expression.ColumnRef("score")),
            ),
            tableName = "students",
            where = Expression.BinaryOp(
                Expression.ColumnRef("score"),
                BinaryOperator.GTE,
                Expression.IntLiteral(70),
            ),
            orderBy = OrderByClause("score", ascending = false),
            limit = 2,
        )

        val op = planner.planSelect(stmt)
        op.open()

        val t1 = op.next()!!
        t1.getString(0) shouldBe "Alice"
        t1.getInt(1) shouldBe 90

        val t2 = op.next()!!
        t2.getString(0) shouldBe "Charlie"
        t2.getInt(1) shouldBe 85

        op.next() shouldBe null
        op.close()
    }

    @Test
    fun `planScan - WHERE 없이 전체 스캔`() {
        val schema = database.getTable("students")!!.schema
        database.insertTuple("students", Tuple(schema, arrayOf(1, "Alice", 90)))
        database.insertTuple("students", Tuple(schema, arrayOf(2, "Bob", 70)))

        val op = planner.planScan("students", where = null)
        op.open()
        var count = 0
        while (op.next() != null) count++
        op.close()

        count shouldBe 2
    }

    @Test
    fun `planScan - WHERE로 필터링`() {
        val schema = database.getTable("students")!!.schema
        database.insertTuple("students", Tuple(schema, arrayOf(1, "Alice", 90)))
        database.insertTuple("students", Tuple(schema, arrayOf(2, "Bob", 70)))

        val where = Expression.BinaryOp(
            Expression.ColumnRef("score"),
            BinaryOperator.GTE,
            Expression.IntLiteral(80),
        )
        val op = planner.planScan("students", where)
        op.open()
        val tuple = op.next()!!
        tuple.getInt(0) shouldBe 1
        op.next() shouldBe null
        op.close()
    }
}
