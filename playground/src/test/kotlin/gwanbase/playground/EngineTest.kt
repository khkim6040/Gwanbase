package gwanbase.playground

import gwanbase.sql.ExecuteResult
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EngineTest {

    @TempDir
    lateinit var tempDir: Path

    private fun count(engine: Engine, table: String): Int {
        val r = engine.database.executeSql("SELECT * FROM $table") as ExecuteResult.Selected
        return r.rows.size
    }

    @Test
    fun `생성 시 샘플 스키마와 행이 적재된다`() {
        Engine(tempDir.resolve("pg.db")).use { engine ->
            engine.database.getCatalog().listTables().map { it.name } shouldContainExactlyInAnyOrder listOf("users", "orders")
            count(engine, "users") shouldBe 5
            count(engine, "orders") shouldBe 8
            engine.database.getCatalog().getIndexesForTable("orders").any { it.name == "idx_orders_user" } shouldBe true
        }
    }

    @Test
    fun `reset은 변경을 버리고 샘플 상태로 되돌린다`() {
        Engine(tempDir.resolve("pg.db")).use { engine ->
            engine.database.executeSql("DROP TABLE orders")
            engine.database.executeSql("INSERT INTO users (id, name, age) VALUES (99, 'Zed', 1)")

            engine.reset()

            engine.database.getCatalog().listTables().map { it.name } shouldContainExactlyInAnyOrder listOf("users", "orders")
            count(engine, "users") shouldBe 5
        }
    }

    @Test
    fun `이전 실행이 남긴 DB 파일이 있어도 생성 시 새로 시작한다`() {
        val path = tempDir.resolve("pg.db")
        Engine(path).use { it.database.executeSql("INSERT INTO users (id, name, age) VALUES (99, 'Zed', 1)") }

        Engine(path).use { engine -> count(engine, "users") shouldBe 5 }
    }
}
