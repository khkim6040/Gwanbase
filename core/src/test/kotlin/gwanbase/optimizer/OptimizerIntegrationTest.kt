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

    @Test
    fun `VARCHAR 인덱스 등가 검색이 접두사를 공유하는 행을 반환하지 않는다`() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', 20)")
        }
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2001, 'abc', 1)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2002, 'abcd', 1)")
        database.executeSql("INSERT INTO users (id, name, age) VALUES (2003, 'abd', 1)")
        database.executeSql("CREATE INDEX idx_users_name ON users (name)")
        database.executeSql("ANALYZE users")

        val explain = database.executeSql("EXPLAIN SELECT id FROM users WHERE name = 'abc'")
        explain.shouldBeInstanceOf<ExecuteResult.Explained>().planText shouldContain "IndexScan"

        val result = database.executeSql("SELECT id FROM users WHERE name = 'abc'")
            .shouldBeInstanceOf<ExecuteResult.Selected>()
        result.rows shouldBe listOf(listOf(2001))
    }

    private fun prepareIndexedUsers() {
        for (i in 1..1100) {
            database.executeSql("INSERT INTO users (id, name, age) VALUES ($i, 'user$i', ${20 + i % 50})")
        }
        database.executeSql("CREATE INDEX idx_users_id ON users (id)")
        database.executeSql("ANALYZE users")
    }

    private fun selectIds(sql: String): List<Any?> =
        database.executeSql(sql).shouldBeInstanceOf<ExecuteResult.Selected>().rows.map { it[0] }

    private fun explain(sql: String): String =
        database.executeSql("EXPLAIN $sql").shouldBeInstanceOf<ExecuteResult.Explained>().planText

    @Test
    fun `범위 조건 SELECT가 IndexScan으로 정확한 행을 반환한다 - 단방향`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id > 1095"
        explain(sql) shouldContain "range=(1095, +inf)"
        selectIds(sql) shouldBe listOf(1096, 1097, 1098, 1099, 1100)
    }

    @Test
    fun `범위 조건 SELECT가 IndexScan으로 정확한 행을 반환한다 - 양방향`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id >= 1090 AND id <= 1094"
        explain(sql) shouldContain "range=[1090, 1094]"
        selectIds(sql) shouldBe listOf(1090, 1091, 1092, 1093, 1094)
    }

    @Test
    fun `범위 조건과 다른 컬럼 조건이 함께 있으면 필터로 재검사한다`() {
        prepareIndexedUsers()
        // age = 20 + i % 50 → id 1100은 age 20, 1099는 69, 1098은 68 …
        val sql = "SELECT id FROM users WHERE id > 1095 AND age = 20"
        explain(sql) shouldContain "IndexScan"
        selectIds(sql) shouldBe listOf(1100)
    }

    @Test
    fun `리터럴이 왼쪽인 범위 조건도 IndexScan을 쓴다`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE 4 > id"
        explain(sql) shouldContain "range=(-inf, 4)"
        selectIds(sql) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `모순된 범위 조건은 빈 결과를 반환한다`() {
        prepareIndexedUsers()
        val sql = "SELECT id FROM users WHERE id > 1095 AND id < 1090"
        explain(sql) shouldContain "IndexScan"
        selectIds(sql) shouldBe emptyList()
    }

    @Test
    fun `등가 조건 EXPLAIN은 key= 형식으로 출력된다`() {
        prepareIndexedUsers()
        explain("SELECT id FROM users WHERE id = 500") shouldContain "key=500"
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
