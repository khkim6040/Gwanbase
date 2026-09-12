package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeyRange
import gwanbase.index.KeySerializer
import gwanbase.sql.Expression
import gwanbase.table.*
import gwanbase.txn.DatabaseSession

/**
 * B+Tree 인덱스로 [KeyRange] 범위에 매칭하는 튜플을 스캔하는 연산자.
 *
 * rangeSupplier로 범위를 동적으로 받을 수 있어 open() 재호출 시 다른 범위로
 * 스캔할 수 있다. 등가 조건은 `KeyRange.equal(v)`로 표현한다.
 *
 * [filter]는 힙 튜플에서 다시 평가한다. 옵티마이저가 인덱스 조건을 필터에서 제거하지
 * 않으므로 이 평가가 PostgreSQL의 recheck 역할을 한다:
 * https://www.postgresql.org/docs/current/index-scanning.html
 */
class IndexScanOperator(
    private val database: Database,
    private val tableName: String,
    private val schema: Schema,
    private val tree: BPlusTree,
    private val indexColumnIndex: Int,
    private val indexColumnType: DataType,
    private val rangeSupplier: () -> KeyRange?,
    private val filter: Expression?,
    private val session: DatabaseSession? = null,
) : Operator {

    private var matchedRids: Iterator<RID> = emptyList<RID>().iterator()

    override val outputSchema: Schema get() = schema

    override fun open() {
        val range = rangeSupplier() ?: run {
            matchedRids = emptyList<RID>().iterator()
            return
        }
        val (startKey, endKey) = KeySerializer.scanBounds(range, indexColumnType)
        val scanIter = tree.scan(startKey, endKey)
        val rids = mutableListOf<RID>()
        while (scanIter.hasNext()) {
            val (_, value) = scanIter.next()
            rids.add(KeySerializer.deserializeRid(value))
        }
        matchedRids = rids.iterator()
    }

    override fun next(): Tuple? {
        while (matchedRids.hasNext()) {
            val rid = matchedRids.next()
            if (session != null) {
                session.acquireSharedLock(tableName, rid)
            }
            val tuple = database.getTuple(tableName, rid) ?: continue
            if (filter != null &&
                !ExpressionEvaluator.evaluateCondition(schema, tuple, filter)
            ) {
                continue
            }
            return tuple
        }
        return null
    }

    override fun close() {
        matchedRids = emptyList<RID>().iterator()
    }
}
