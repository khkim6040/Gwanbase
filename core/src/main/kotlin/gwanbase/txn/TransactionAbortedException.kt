package gwanbase.txn

/**
 * 실패한 트랜잭션 블록 안에서 ROLLBACK/COMMIT 이외의 문장을 실행하려 할 때 발생한다.
 * PostgreSQL SQLSTATE 25P02 (in_failed_sql_transaction).
 *
 * PostgreSQL `exec_simple_query()`가 `IsAbortedTransactionBlockState()`이고
 * `IsTransactionExitStmt()`가 아닌 문장에 같은 메시지로 오류를 낸다.
 * https://github.com/postgres/postgres/blob/master/src/backend/tcop/postgres.c
 */
class TransactionAbortedException : RuntimeException(
    "current transaction is aborted, commands ignored until end of transaction block"
)
