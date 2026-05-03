package gwanbase.execution

import gwanbase.sql.BinaryOperator
import gwanbase.sql.Expression
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NestedLoopJoinOperatorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() { database.close() }

    private fun createUsersTable() {
        val schema = Schema(
            listOf(
                Column("id", DataType.INT32, nullable = false),
                Column("name", DataType.VARCHAR, maxLength = 50, nullable = false),
            )
        )
        database.createTable("users", schema)
    }

    private fun createOrdersTable() {
        val schema = Schema(
            listOf(
                Column("oid", DataType.INT32, nullable = false),
                Column("user_id", DataType.INT32, nullable = false),
                Column("amount", DataType.INT32, nullable = false),
            )
        )
        database.createTable("orders", schema)
    }

    private fun insertUser(id: Int, name: String) {
        val schema = database.getTable("users")!!.schema
        database.insertTuple("users", Tuple(schema, arrayOf(id, name)))
    }

    private fun insertOrder(oid: Int, userId: Int, amount: Int) {
        val schema = database.getTable("orders")!!.schema
        database.insertTuple("orders", Tuple(schema, arrayOf(oid, userId, amount)))
    }

    @Test
    fun `INNER JOIN 기본 동작 — 매칭 행만 결합`() {
        createUsersTable()
        createOrdersTable()
        insertUser(1, "Alice")
        insertUser(2, "Bob")
        insertUser(3, "Charlie")
        insertOrder(10, 1, 100)
        insertOrder(11, 2, 200)
        insertOrder(12, 1, 150)

        val outer = SeqScanOperator(database, "users")
        val inner = SeqScanOperator(database, "orders")
        val combinedSchema = Schema(
            outer.outputSchema.columns + inner.outputSchema.columns
        )
        val condition = Expression.BinaryOp(
            Expression.ColumnRef(null, "id"),
            BinaryOperator.EQ,
            Expression.ColumnRef(null, "user_id"),
        )
        val join = NestedLoopJoinOperator(outer, inner, condition, combinedSchema)

        join.open()
        val results = mutableListOf<Tuple>()
        while (true) {
            val tuple = join.next() ?: break
            results.add(tuple)
        }
        join.close()

        results.size shouldBe 3
        // Alice(id=1) - order 10, amount=100
        results[0].getInt(0) shouldBe 1
        results[0].getString(1) shouldBe "Alice"
        results[0].getInt(2) shouldBe 10
        results[0].getInt(4) shouldBe 100
        // Alice(id=1) - order 12, amount=150
        results[1].getInt(0) shouldBe 1
        results[1].getString(1) shouldBe "Alice"
        results[1].getInt(2) shouldBe 12
        results[1].getInt(4) shouldBe 150
        // Bob(id=2) - order 11, amount=200
        results[2].getInt(0) shouldBe 2
        results[2].getString(1) shouldBe "Bob"
        results[2].getInt(2) shouldBe 11
        results[2].getInt(4) shouldBe 200
    }

    @Test
    fun `한쪽 테이블이 비어 있을 때 빈 결과`() {
        createUsersTable()
        createOrdersTable()
        insertUser(1, "Alice")
        // orders 테이블에는 데이터 없음

        val outer = SeqScanOperator(database, "users")
        val inner = SeqScanOperator(database, "orders")
        val combinedSchema = Schema(
            outer.outputSchema.columns + inner.outputSchema.columns
        )
        val condition = Expression.BinaryOp(
            Expression.ColumnRef(null, "id"),
            BinaryOperator.EQ,
            Expression.ColumnRef(null, "user_id"),
        )
        val join = NestedLoopJoinOperator(outer, inner, condition, combinedSchema)

        join.open()
        join.next() shouldBe null
        join.close()
    }

    @Test
    fun `ON 조건에 맞지 않는 행 제외`() {
        createUsersTable()
        createOrdersTable()
        insertUser(1, "Alice")
        insertOrder(10, 99, 100) // user_id=99 — 매칭 없음

        val outer = SeqScanOperator(database, "users")
        val inner = SeqScanOperator(database, "orders")
        val combinedSchema = Schema(
            outer.outputSchema.columns + inner.outputSchema.columns
        )
        val condition = Expression.BinaryOp(
            Expression.ColumnRef(null, "id"),
            BinaryOperator.EQ,
            Expression.ColumnRef(null, "user_id"),
        )
        val join = NestedLoopJoinOperator(outer, inner, condition, combinedSchema)

        join.open()
        join.next() shouldBe null
        join.close()
    }
}
