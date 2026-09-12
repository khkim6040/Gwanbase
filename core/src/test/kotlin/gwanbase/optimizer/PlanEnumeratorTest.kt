package gwanbase.optimizer

import gwanbase.sql.BinaryOperator
import gwanbase.sql.ExecuteResult
import gwanbase.sql.Expression
import gwanbase.table.*
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlanEnumeratorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var enumerator: PlanEnumerator

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        database.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50), age INT)")
        enumerator = PlanEnumerator(database.getCatalog())
    }

    @AfterEach
    fun tearDown() { database.close() }

    private fun col(name: String) = Expression.ColumnRef(null, name)
    private fun lit(v: Long) = Expression.IntLiteral(v)
    private fun bin(l: Expression, op: BinaryOperator, r: Expression) = Expression.BinaryOp(l, op, r)

    /** 1000행 + id 인덱스 + ANALYZE. */
    private fun prepareIndexedUsers() {
        for (i in 1..1000) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")
    }

    @Test
    fun `인덱스 있는 단방향 범위 조건에서 IndexScan 선택`() {
        prepareIndexedUsers()
        // id > 995 → 통계상 5행 → 비용 8 < seq 10
        val filter = bin(col("id"), BinaryOperator.GT, lit(995))
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(995), false)
        scan.upperBound shouldBe null
    }

    @Test
    fun `인덱스 있는 양방향 범위 조건을 하나의 구간으로 병합`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GTE, lit(100)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.LT, lit(104)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(100), true)
        scan.upperBound shouldBe Bound(lit(104), false)
    }

    @Test
    fun `IndexScan은 인덱스 조건을 포함한 전체 필터를 유지한다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(995)),
            BinaryOperator.AND,
            bin(col("age"), BinaryOperator.EQ, lit(30)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        plan.shouldBeInstanceOf<PlanNode.IndexScan>().filter shouldBe filter
    }

    @Test
    fun `리터럴이 왼쪽에 있으면 연산자를 뒤집어 매칭한다`() {
        prepareIndexedUsers()
        // 5 > id  ≡  id < 5
        val filter = bin(lit(5), BinaryOperator.GT, col("id"))
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe null
        scan.upperBound shouldBe Bound(lit(5), false)
    }

    @Test
    fun `같은 방향 경계가 둘이면 첫 번째만 인덱스로 보낸다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(995)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.GT, lit(990)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.lowerBound shouldBe Bound(lit(995), false)
        scan.filter shouldBe filter
    }

    @Test
    fun `등가 조건은 앞선 범위 경계를 덮어쓴다`() {
        prepareIndexedUsers()
        val filter = bin(
            bin(col("id"), BinaryOperator.GT, lit(3)),
            BinaryOperator.AND,
            bin(col("id"), BinaryOperator.EQ, lit(42)),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        val scan = plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        scan.isEquality shouldBe true
        scan.lowerBound shouldBe Bound(lit(42), true)
    }

    @Test
    fun `부등호가 아닌 조건(NEQ)은 인덱스를 쓰지 않는다`() {
        prepareIndexedUsers()
        val filter = bin(col("id"), BinaryOperator.NEQ, lit(42))
        enumerator.bestAccessPath("users", filter).shouldBeInstanceOf<PlanNode.SeqScan>()
    }

    @Test
    fun `넓은 범위 조건은 비용 비교로 SeqScan을 선택한다`() {
        prepareIndexedUsers()
        // id > 10 → 990행 → 인덱스 비용 993 > seq 10
        val filter = bin(col("id"), BinaryOperator.GT, lit(10))
        enumerator.bestAccessPath("users", filter).shouldBeInstanceOf<PlanNode.SeqScan>()
    }

    @Test
    fun `인덱스 있는 등가 조건에서 IndexScan 선택`() {
        // 1000 rows 삽입 (seqScanCost > indexScanCost가 되려면 충분한 행 수 필요)
        for (i in 1..1000) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")

        val filter = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef(null, "id"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.IntLiteral(42),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        plan.indexName shouldBe "idx_users_id"
        plan.indexColumnName shouldBe "id"
        plan.lowerBound shouldBe Bound(gwanbase.sql.Expression.IntLiteral(42), true)
        plan.upperBound shouldBe plan.lowerBound
        plan.filter shouldBe filter
    }

    @Test
    fun `인덱스 없는 조건에서 SeqScan 선택`() {
        for (i in 1..10) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i})")
        }
        database.executeSql("ANALYZE users")

        val filter = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef(null, "age"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.IntLiteral(25),
        )
        val plan = enumerator.bestAccessPath("users", filter)
        plan.shouldBeInstanceOf<PlanNode.SeqScan>()
    }

    @Test
    fun `필터 없으면 SeqScan`() {
        for (i in 1..5) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i})")
        }
        database.executeSql("ANALYZE users")

        val plan = enumerator.bestAccessPath("users", null)
        plan.shouldBeInstanceOf<PlanNode.SeqScan>()
        plan.filter shouldBe null
    }

    @Test
    fun `3테이블 조인 — 모든 조건이 계획에 포함된다`() {
        database.executeSql("CREATE TABLE orders (oid INT NOT NULL, uid INT NOT NULL)")
        database.executeSql("CREATE TABLE items (iid INT NOT NULL, oid INT NOT NULL)")

        for (i in 1..5) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i})")
        }
        for (i in 1..10) {
            database.executeSql("INSERT INTO orders (oid, uid) VALUES ($i, ${i % 5 + 1})")
        }
        for (i in 1..20) {
            database.executeSql("INSERT INTO items (iid, oid) VALUES ($i, ${i % 10 + 1})")
        }
        database.executeSql("ANALYZE users")
        database.executeSql("ANALYZE orders")
        database.executeSql("ANALYZE items")

        val cond1 = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef("users", "id"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.ColumnRef("orders", "uid"),
        )
        val cond2 = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef("orders", "oid"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.ColumnRef("items", "oid"),
        )

        val plan = enumerator.bestJoinOrder(listOf("users", "orders", "items"), listOf(cond1, cond2))
        plan.shouldBeInstanceOf<PlanNode.NestedLoopJoin>()

        // 최상위 조인 조건에 양쪽 조건이 모두 포함되어야 한다
        val explainText = plan.explain()
        explainText shouldContain "uid"
        explainText shouldContain "oid"
    }

    @Test
    fun `2테이블 조인 순서 - 작은 테이블이 outer`() {
        database.executeSql("CREATE TABLE orders (oid INT NOT NULL, uid INT NOT NULL)")

        // users: 10행, orders: 100행
        for (i in 1..10) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i})")
        }
        for (i in 1..100) {
            database.executeSql("INSERT INTO orders (oid, uid) VALUES ($i, ${i % 10 + 1})")
        }
        database.executeSql("ANALYZE users")
        database.executeSql("ANALYZE orders")

        val joinCondition = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef(null, "id"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.ColumnRef(null, "uid"),
        )

        val plan = enumerator.bestJoinOrder(listOf("users", "orders"), listOf(joinCondition))
        plan.shouldBeInstanceOf<PlanNode.NestedLoopJoin>()
        // users(10행)가 outer여야 비용이 더 낮다
        val outerTable = plan.outer
        outerTable.shouldBeInstanceOf<PlanNode.SeqScan>()
        outerTable.tableName shouldBe "users"
    }
}
