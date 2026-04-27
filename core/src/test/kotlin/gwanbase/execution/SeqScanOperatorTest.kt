package gwanbase.execution

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SeqScanOperatorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() { database.close() }

    private fun createStudentsTable() {
        val schema = Schema(
            listOf(
                Column("id", DataType.INT32, nullable = false),
                Column("name", DataType.VARCHAR, maxLength = 50, nullable = true),
                Column("score", DataType.INT32, nullable = true),
            )
        )
        database.createTable("students", schema)
    }

    private fun insertStudent(id: Int, name: String?, score: Int?) {
        val schema = database.getTable("students")!!.schema
        val tuple = Tuple(schema, arrayOf(id, name, score))
        database.insertTuple("students", tuple)
    }

    @Test
    fun `빈 테이블 스캔 시 next가 즉시 null 반환`() {
        createStudentsTable()
        val scan = SeqScanOperator(database, "students")

        scan.open()
        scan.next() shouldBe null
        scan.close()
    }

    @Test
    fun `단일 행 스캔`() {
        createStudentsTable()
        insertStudent(1, "Alice", 90)
        val scan = SeqScanOperator(database, "students")

        scan.open()
        val tuple = scan.next()!!
        tuple.getInt(0) shouldBe 1
        tuple.getString(1) shouldBe "Alice"
        tuple.getInt(2) shouldBe 90
        scan.next() shouldBe null
        scan.close()
    }

    @Test
    fun `다중 행 스캔 시 모든 행 반환`() {
        createStudentsTable()
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)
        insertStudent(3, "Charlie", 70)
        val scan = SeqScanOperator(database, "students")

        scan.open()
        val ids = mutableListOf<Int>()
        var tuple = scan.next()
        while (tuple != null) {
            ids.add(tuple.getInt(0)!!)
            tuple = scan.next()
        }
        scan.close()

        ids.toSet() shouldBe setOf(1, 2, 3)
    }

    @Test
    fun `outputSchema가 테이블 스키마와 동일`() {
        createStudentsTable()
        val scan = SeqScanOperator(database, "students")

        scan.outputSchema.columnCount shouldBe 3
        scan.outputSchema.column(0).name shouldBe "id"
        scan.outputSchema.column(1).name shouldBe "name"
        scan.outputSchema.column(2).name shouldBe "score"
    }

    @Test
    fun `NULL 값을 포함한 행 스캔`() {
        createStudentsTable()
        insertStudent(1, null, null)
        val scan = SeqScanOperator(database, "students")

        scan.open()
        val tuple = scan.next()!!
        tuple.getInt(0) shouldBe 1
        tuple.isNull(1) shouldBe true
        tuple.isNull(2) shouldBe true
        scan.next() shouldBe null
        scan.close()
    }
}
