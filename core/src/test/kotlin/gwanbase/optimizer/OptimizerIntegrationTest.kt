package gwanbase.optimizer

import gwanbase.execution.ExpressionEvaluator
import gwanbase.execution.Planner
import gwanbase.sql.*
import gwanbase.table.DataType
import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Optimizer → PlanNode → Planner.toOperator() 통합 테스트.
 */
class OptimizerIntegrationTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        database.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50), age INT)")
    }

    @AfterEach
    fun tearDown() { database.close() }

    @Test
    fun `단일 테이블 SELECT → Project wrapping SeqScan 계획 생성`() {
        val stmt = parseSelect("SELECT * FROM users")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)

        // 최상위는 Project, 하위는 SeqScan이어야 한다
        val project = plan.shouldBeInstanceOf<PlanNode.Project>()
        project.child.shouldBeInstanceOf<PlanNode.SeqScan>()
    }

    @Test
    fun `WHERE 조건이 있는 SELECT → SeqScan에 filter 포함`() {
        val stmt = parseSelect("SELECT * FROM users WHERE age = 30")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)

        val project = plan.shouldBeInstanceOf<PlanNode.Project>()
        val seqScan = project.child.shouldBeInstanceOf<PlanNode.SeqScan>()
        seqScan.filter shouldBe stmt.where
    }

    @Test
    fun `ORDER BY가 있는 SELECT → Sort 노드 포함`() {
        val stmt = parseSelect("SELECT * FROM users ORDER BY age")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)

        val project = plan.shouldBeInstanceOf<PlanNode.Project>()
        project.child.shouldBeInstanceOf<PlanNode.Sort>()
    }

    @Test
    fun `LIMIT가 있는 SELECT → Limit 노드 포함`() {
        val stmt = parseSelect("SELECT * FROM users LIMIT 10")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)

        val project = plan.shouldBeInstanceOf<PlanNode.Project>()
        project.child.shouldBeInstanceOf<PlanNode.Limit>()
    }

    @Test
    fun `인덱스가 있고 충분한 행 수일 때 IndexScan 선택`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")

        val stmt = parseSelect("SELECT * FROM users WHERE id = 500")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)

        val project = plan.shouldBeInstanceOf<PlanNode.Project>()
        val indexScan = project.child.shouldBeInstanceOf<PlanNode.IndexScan>()
        indexScan.indexName shouldBe "idx_users_id"
    }

    @Test
    fun `PlanNode를 Operator로 변환하여 실행 — SeqScan 경로`() {
        database.executeSql("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")

        val stmt = parseSelect("SELECT * FROM users")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)
        val planner = Planner(database)
        val op = planner.toOperator(plan)

        op.open()
        val rows = mutableListOf<String>()
        var t = op.next()
        while (t != null) {
            rows.add(t.toString())
            t = op.next()
        }
        op.close()

        rows.size shouldBe 2
    }

    @Test
    fun `PlanNode를 Operator로 변환하여 실행 — IndexScan 경로`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")

        val stmt = parseSelect("SELECT * FROM users WHERE id = 500")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)
        val planner = Planner(database)
        val op = planner.toOperator(plan)

        op.open()
        val rows = mutableListOf<Any?>()
        var t = op.next()
        while (t != null) {
            rows.add(ExpressionEvaluator.getTupleValue(t, 0, DataType.INT32))
            t = op.next()
        }
        op.close()

        rows.size shouldBe 1
        rows[0] shouldBe 500
    }

    @Test
    fun `explain 출력에 SeqScan 포함`() {
        val stmt = parseSelect("SELECT * FROM users")
        val optimizer = Optimizer(database.getCatalog())
        val plan = optimizer.optimize(stmt)
        val text = plan.explain()

        text shouldContain "SeqScan"
        text shouldContain "Project"
    }

    /** SQL 텍스트에서 파싱 + 바인딩한 Statement.Select를 반환한다. */
    private fun parseSelect(sql: String): Statement.Select {
        val tokens = Lexer(sql).tokenize()
        val stmt = Parser(tokens).parse()
        val binder = Binder(database.getCatalog())
        binder.bind(stmt)
        return stmt as Statement.Select
    }
}
