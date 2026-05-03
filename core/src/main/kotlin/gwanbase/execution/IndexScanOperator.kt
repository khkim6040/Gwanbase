package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.sql.Expression
import gwanbase.table.*
import gwanbase.txn.DatabaseSession

/**
 * B+Tree 인덱스를 사용하여 등가 조건에 매칭하는 튜플을 스캔하는 연산자.
 *
 * lookupKeySupplier로 검색 키를 동적으로 받을 수 있어
 * Index Nested Loop Join에서 outer 튜플에 따라 키가 바뀌는 경우를 지원한다.
 */
class IndexScanOperator(
    private val database: Database,
    private val tableName: String,
    private val schema: Schema,
    private val tree: BPlusTree,
    private val indexColumnIndex: Int,
    private val indexColumnType: DataType,
    private val lookupKeySupplier: () -> Any?,
    private val remainingFilter: Expression?,
    private val session: DatabaseSession? = null,
) : Operator {

    private var matchedRids: Iterator<RID> = emptyList<RID>().iterator()

    override val outputSchema: Schema get() = schema

    override fun open() {
        val lookupValue = lookupKeySupplier() ?: run {
            matchedRids = emptyList<RID>().iterator()
            return
        }
        val keyBytes = KeySerializer.serializeKey(lookupValue, indexColumnType)
        val resultBytes = tree.search(keyBytes)
        if (resultBytes != null) {
            matchedRids = listOf(KeySerializer.deserializeRid(resultBytes)).iterator()
        } else {
            matchedRids = emptyList<RID>().iterator()
        }
    }

    override fun next(): Tuple? {
        while (matchedRids.hasNext()) {
            val rid = matchedRids.next()
            if (session != null) {
                session.acquireSharedLock(tableName, rid)
            }
            val tuple = database.getTuple(tableName, rid) ?: continue
            if (remainingFilter != null &&
                !ExpressionEvaluator.evaluateCondition(schema, tuple, remainingFilter)
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
