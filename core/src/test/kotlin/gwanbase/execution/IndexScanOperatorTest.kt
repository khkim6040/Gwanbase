package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IndexScanOperatorTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        createStudentsTable()
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

    private fun insertStudent(id: Int, name: String?, score: Int?): RID {
        val schema = database.getTable("students")!!.schema
        val tuple = Tuple(schema, arrayOf(id, name, score))
        return database.insertTuple("students", tuple)
    }

    /**
     * id 컬럼에 대한 B+Tree 인덱스를 수동으로 구축한다.
     */
    private fun buildIdIndex(): BPlusTree {
        val tree = BPlusTree.createNew(database.bpm)
        val schema = database.getTable("students")!!.schema
        val iter = database.scanTable("students")
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            val value = ExpressionEvaluator.getTupleValue(tuple, 0, schema.column(0).type) ?: continue
            tree.insert(
                KeySerializer.serializeKey(value, DataType.INT32),
                KeySerializer.serializeRid(rid),
            )
        }
        return tree
    }

    @Test
    fun `등가 조건으로 정확한 결과 반환`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)
        insertStudent(3, "Charlie", 70)

        val tree = buildIdIndex()
        val schema = database.getTable("students")!!.schema

        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 0,
            indexColumnType = DataType.INT32,
            lookupKeySupplier = { 2 },
            remainingFilter = null,
        )

        op.open()
        val tuple = op.next()!!
        tuple.getInt(0) shouldBe 2
        tuple.getString(1) shouldBe "Bob"
        tuple.getInt(2) shouldBe 80
        op.next() shouldBe null
        op.close()
    }

    @Test
    fun `인덱스에 없는 키 조회 시 빈 결과`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)

        val tree = buildIdIndex()
        val schema = database.getTable("students")!!.schema

        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 0,
            indexColumnType = DataType.INT32,
            lookupKeySupplier = { 999 },
            remainingFilter = null,
        )

        op.open()
        op.next() shouldBe null
        op.close()
    }

    @Test
    fun `NULL 키 공급 시 빈 결과`() {
        insertStudent(1, "Alice", 90)

        val tree = buildIdIndex()
        val schema = database.getTable("students")!!.schema

        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 0,
            indexColumnType = DataType.INT32,
            lookupKeySupplier = { null },
            remainingFilter = null,
        )

        op.open()
        op.next() shouldBe null
        op.close()
    }

    @Test
    fun `open 재호출 시 다른 키로 검색 가능`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)
        insertStudent(3, "Charlie", 70)

        val tree = buildIdIndex()
        val schema = database.getTable("students")!!.schema

        var lookupKey: Any? = 1
        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 0,
            indexColumnType = DataType.INT32,
            lookupKeySupplier = { lookupKey },
            remainingFilter = null,
        )

        // 첫 번째 검색: id=1
        op.open()
        op.next()!!.getInt(0) shouldBe 1
        op.next() shouldBe null

        // 두 번째 검색: id=3
        lookupKey = 3
        op.open()
        op.next()!!.getInt(0) shouldBe 3
        op.next() shouldBe null

        op.close()
    }
}
