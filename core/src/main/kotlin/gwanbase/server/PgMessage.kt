package gwanbase.server

/**
 * PostgreSQL Wire Protocol v3.0 메시지.
 *
 * Simple Query 프로토콜에 필요한 메시지만 정의한다.
 */
sealed class PgMessage {
    // ── Client → Server ──

    /** Startup 메시지. 타입 바이트 없이 길이로 시작하는 특수 메시지. */
    data class StartupMessage(val version: Int, val params: Map<String, String>) : PgMessage()

    /** SQL 쿼리 메시지. */
    data class Query(val sql: String) : PgMessage()

    /** 연결 종료 메시지. */
    data object Terminate : PgMessage()

    // ── Server → Client ──

    /** 인증 성공. */
    data object AuthenticationOk : PgMessage()

    /** 서버 파라미터. */
    data class ParameterStatus(val name: String, val value: String) : PgMessage()

    /** 백엔드 키 데이터 (cancel용, MVP에서는 형식만). */
    data class BackendKeyData(val pid: Int, val secretKey: Int) : PgMessage()

    /** 쿼리 처리 완료, 다음 쿼리 대기. */
    data class ReadyForQuery(val txnStatus: Char) : PgMessage()

    /** SELECT 결과의 컬럼 메타데이터. */
    data class RowDescription(val columns: List<ColumnDesc>) : PgMessage()

    /** SELECT 결과의 한 행. null이면 SQL NULL. */
    data class DataRow(val values: List<String?>) : PgMessage()

    /** 명령 실행 완료 태그. */
    data class CommandComplete(val tag: String) : PgMessage()

    /** 에러 응답. */
    data class ErrorResponse(val severity: String, val message: String, val code: String) : PgMessage()
}

/**
 * RowDescription의 컬럼 디스크립터.
 *
 * PostgreSQL 프로토콜에서 정의하는 필드를 그대로 포함한다.
 * MVP에서 tableOid, columnAttr, typeMod는 고정값을 사용한다.
 */
data class ColumnDesc(
    val name: String,
    val tableOid: Int = 0,
    val columnAttr: Short = 0,
    val typeOid: Int,
    val typeSize: Short,
    val typeMod: Int = -1,
    val formatCode: Short = 0,
)
