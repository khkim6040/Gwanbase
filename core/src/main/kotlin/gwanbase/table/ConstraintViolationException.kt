package gwanbase.table

/**
 * 무결성 제약 위반 (SQLSTATE Class 23). 외래 키(23503 `foreign_key_violation`)와
 * CHECK(23514 `check_violation`)가 사용한다. UNIQUE는 충돌 RID를 함께 실어야 하므로
 * [UniqueViolationException]으로 분리되어 있다.
 *
 * 메시지는 PostgreSQL 형식을 따른다.
 * - https://www.postgresql.org/docs/current/errcodes-appendix.html
 * - https://github.com/postgres/postgres/blob/master/src/backend/executor/execMain.c
 *   (`ExecConstraints`: `new row for relation "%s" violates check constraint "%s"`)
 * - https://github.com/postgres/postgres/blob/master/src/backend/utils/adt/ri_triggers.c
 *   (`ri_ReportViolation`: `insert or update on table "%s" violates foreign key constraint "%s"`,
 *   `update or delete on table "%s" violates foreign key constraint "%s" on table "%s"`)
 *
 * @param constraintName 위반된 제약 이름
 * @param sqlState 23503 또는 23514
 */
class ConstraintViolationException(
    val constraintName: String,
    val sqlState: String,
    message: String,
) : RuntimeException(message)
