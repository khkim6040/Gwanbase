package gwanbase.table

/**
 * UNIQUE / PRIMARY KEY 제약 위반 (SQLSTATE 23505 `unique_violation`).
 *
 * 메시지는 PostgreSQL의 `duplicate key value violates unique constraint "..."` 형식을 따른다.
 * - https://www.postgresql.org/docs/current/errcodes-appendix.html (23505)
 * - https://github.com/postgres/postgres/blob/master/src/backend/access/nbtree/nbtinsert.c
 *   (`_bt_check_unique`의 `ereport(ERROR, errcode(ERRCODE_UNIQUE_VIOLATION), ...)`)
 *
 * @param indexName 위반된 유일 인덱스 이름
 * @param conflictingRid 이미 같은 키를 가진 기존 행의 RID. 세션 레이어가 그 행의 잠금을 기다려
 *   미커밋 트랜잭션의 결과(commit/abort)를 확인하는 데 사용한다.
 */
class UniqueViolationException(
    val indexName: String,
    val conflictingRid: RID,
) : RuntimeException("duplicate key value violates unique constraint \"$indexName\"") {
    val sqlState: String = "23505"
}
