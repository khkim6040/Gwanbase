package gwanbase.execution

import gwanbase.table.*
import gwanbase.txn.DatabaseSession

/**
 * 테이블 전체를 순차 스캔하는 연산자.
 *
 * Database.scanTable()을 통해 HeapFile의 모든 튜플을 순회한다.
 * DatabaseSession이 제공되면 각 행에 S 잠금을 획득한다.
 *
 * @param database 대상 데이터베이스
 * @param tableName 스캔할 테이블 이름
 * @param session 잠금 획득용 세션 (없으면 잠금 없이 스캔)
 */
class SeqScanOperator(
    private val database: Database,
    private val tableName: String,
    private val session: DatabaseSession? = null,
) : Operator {

    private var iterator: Iterator<Pair<RID, Tuple>>? = null

    override val outputSchema: Schema
        get() = database.getTable(tableName)!!.schema

    override fun open() {
        iterator = session?.scanTableWithLock(tableName)
            ?: database.scanTable(tableName)
    }

    override fun next(): Tuple? {
        val iter = iterator ?: return null
        return if (iter.hasNext()) iter.next().second else null
    }

    override fun close() {
        iterator = null
    }
}
