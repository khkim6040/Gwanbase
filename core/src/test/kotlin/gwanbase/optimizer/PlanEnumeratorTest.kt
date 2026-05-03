package gwanbase.optimizer

import gwanbase.sql.ExecuteResult
import gwanbase.table.*
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
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

        val plan = enumerator.bestJoinOrder(listOf("users", "orders"), joinCondition)
        plan.shouldBeInstanceOf<PlanNode.NestedLoopJoin>()
        // users(10행)가 outer여야 비용이 더 낮다
        val outerTable = plan.outer
        outerTable.shouldBeInstanceOf<PlanNode.SeqScan>()
        outerTable.tableName shouldBe "users"
    }
}
