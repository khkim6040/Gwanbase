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

/**
 * 데이터 예외 (SQLSTATE Class 22). 값 자체가 잘못되어 연산·저장이 불가능할 때 발생한다.
 *
 * PostgreSQL처럼 개별 클래스 대신 SQLSTATE 코드를 필드로 가진다.
 * 0으로 나누기(22012), 수치 범위 초과(22003), 문자열 길이 초과(22001) 등.
 */
class DataException(message: String, val sqlState: String) : RuntimeException("데이터 오류 ($sqlState): $message")
