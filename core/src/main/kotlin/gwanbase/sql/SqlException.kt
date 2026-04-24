package gwanbase.sql

/**
 * SQL 파싱 오류. 위치 정보를 포함한다.
 */
class ParseException(
    message: String,
    val position: Int,
) : RuntimeException("파싱 오류 (위치 $position): $message")

/**
 * SQL 바인딩 오류. 테이블/컬럼 검증 실패 시 발생한다.
 */
class BindException(message: String) : RuntimeException("바인딩 오류: $message")
