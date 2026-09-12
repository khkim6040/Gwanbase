package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeyRange
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
     * [columnIndex] 컬럼에 대한 B+Tree 인덱스를 수동으로 구축한다 (복합 키 사용).
     */
    private fun buildIndex(columnIndex: Int, type: DataType): BPlusTree {
        val tree = BPlusTree.createNew(database.bpm)
        val iter = database.scanTable("students")
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            val value = ExpressionEvaluator.getTupleValue(tuple, columnIndex, type) ?: continue
            val columnKey = KeySerializer.serializeKey(value, type)
            tree.insert(
                KeySerializer.compositeKey(columnKey, rid),
                KeySerializer.serializeRid(rid),
            )
        }
        return tree
    }

    private fun buildIdIndex(): BPlusTree = buildIndex(0, DataType.INT32)

    /** id 인덱스로 [range]를 스캔해 id 목록을 반환한다. */
    private fun scanIds(range: KeyRange?, filter: gwanbase.sql.Expression? = null): List<Int> {
        val schema = database.getTable("students")!!.schema
        val op = IndexScanOperator(
            database = database, tableName = "students", schema = schema,
            tree = buildIdIndex(), indexColumnIndex = 0, indexColumnType = DataType.INT32,
            rangeSupplier = { range }, filter = filter,
        )
        op.open()
        val ids = generateSequence { op.next() }.map { it.getInt(0)!! }.toList()
        op.close()
        return ids
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
            rangeSupplier = { KeyRange.equal(2) },
            filter = null,
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
            rangeSupplier = { KeyRange.equal(999) },
            filter = null,
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
            rangeSupplier = { null },
            filter = null,
        )

        op.open()
        op.next() shouldBe null
        op.close()
    }

    @Test
    fun `비고유 인덱스 — 동일 키 값의 여러 행 반환`() {
        // score 컬럼에 동일 값을 가진 여러 행 삽입
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 90)
        insertStudent(3, "Charlie", 80)
        insertStudent(4, "Diana", 90)

        // score 컬럼(인덱스 2)에 대한 인덱스 구축
        val schema = database.getTable("students")!!.schema
        val tree = BPlusTree.createNew(database.bpm)
        val iter = database.scanTable("students")
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            val value = ExpressionEvaluator.getTupleValue(tuple, 2, schema.column(2).type) ?: continue
            val columnKey = KeySerializer.serializeKey(value, DataType.INT32)
            tree.insert(
                KeySerializer.compositeKey(columnKey, rid),
                KeySerializer.serializeRid(rid),
            )
        }

        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 2,
            indexColumnType = DataType.INT32,
            rangeSupplier = { KeyRange.equal(90) },
            filter = null,
        )

        op.open()
        val results = mutableListOf<String>()
        var t = op.next()
        while (t != null) {
            results.add(t.getString(1)!!)
            t = op.next()
        }
        op.close()

        results.size shouldBe 3
        results.sorted() shouldBe listOf("Alice", "Bob", "Diana")
    }

    @Test
    fun `open 재호출 시 다른 키로 검색 가능`() {
        insertStudent(1, "Alice", 90)
        insertStudent(2, "Bob", 80)
        insertStudent(3, "Charlie", 70)

        val tree = buildIdIndex()
        val schema = database.getTable("students")!!.schema

        var currentKey: Any? = 1
        val op = IndexScanOperator(
            database = database,
            tableName = "students",
            schema = schema,
            tree = tree,
            indexColumnIndex = 0,
            indexColumnType = DataType.INT32,
            rangeSupplier = { KeyRange.equal(currentKey!!) },
            filter = null,
        )

        // 첫 번째 검색: id=1
        op.open()
        op.next()!!.getInt(0) shouldBe 1
        op.next() shouldBe null

        // 두 번째 검색: id=3
        currentKey = 3
        op.open()
        op.next()!!.getInt(0) shouldBe 3
        op.next() shouldBe null

        op.close()
    }

    @Test
    fun `범위 - 제외 하한(gt)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(3, false, null, false)) shouldBe listOf(4, 5)
    }

    @Test
    fun `범위 - 포함 하한(gte)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(3, true, null, false)) shouldBe listOf(3, 4, 5)
    }

    @Test
    fun `범위 - 제외 상한(lt)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, 3, false)) shouldBe listOf(1, 2)
    }

    @Test
    fun `범위 - 포함 상한(lte)`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, 3, true)) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `범위 - 양방향 경계`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(2, false, 5, false)) shouldBe listOf(3, 4)
    }

    @Test
    fun `범위 - 하한이 상한보다 크면 빈 결과`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(4, true, 2, true)) shouldBe emptyList()
    }

    @Test
    fun `범위 - 양쪽 경계 없음은 전체 반환`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        scanIds(KeyRange(null, false, null, false)) shouldBe listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `범위 - 음수를 포함한 INT32 순서 보존`() {
        listOf(-5, -1, 0, 3, 7).forEach { insertStudent(it, "s$it", 0) }
        scanIds(KeyRange(-1, true, 3, true)) shouldBe listOf(-1, 0, 3)
    }

    @Test
    fun `범위 - 필터가 튜플을 재검사한다`() {
        (1..5).forEach { insertStudent(it, "s$it", it * 10) }
        // id >= 2 범위 안에서 score = 40 만 통과
        val filter = gwanbase.sql.Expression.BinaryOp(
            gwanbase.sql.Expression.ColumnRef(null, "score"),
            gwanbase.sql.BinaryOperator.EQ,
            gwanbase.sql.Expression.IntLiteral(40),
        )
        scanIds(KeyRange(2, true, null, false), filter) shouldBe listOf(4)
    }

    @Test
    fun `범위 - VARCHAR 제외 하한이 접두사를 공유하는 긴 문자열을 포함한다`() {
        insertStudent(1, "abc", 0)
        insertStudent(2, "abcd", 0)
        insertStudent(3, "abd", 0)
        insertStudent(4, "ab", 0)
        val schema = database.getTable("students")!!.schema
        val op = IndexScanOperator(
            database = database, tableName = "students", schema = schema,
            tree = buildIndex(1, DataType.VARCHAR), indexColumnIndex = 1, indexColumnType = DataType.VARCHAR,
            rangeSupplier = { KeyRange("abc", false, null, false) }, filter = null,
        )
        op.open()
        val names = generateSequence { op.next() }.map { it.getString(1) }.toList()
        op.close()
        names shouldBe listOf("abcd", "abd")
    }
}
