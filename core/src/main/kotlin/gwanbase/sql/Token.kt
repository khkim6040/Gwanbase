package gwanbase.sql

/**
 * SQL 토큰 타입.
 */
enum class TokenType {
    // 키워드
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, DROP, TABLE, ORDER, BY, ASC, DESC, LIMIT,
    AND, OR, NOT, NULL, TRUE, FALSE,
    IS,
    BEGIN, COMMIT, ROLLBACK,
    JOIN, ON, INDEX, ANALYZE, EXPLAIN,
    INT, INTEGER, BIGINT, BOOLEAN, DOUBLE, FLOAT, VARCHAR, TIMESTAMP,

    // 리터럴
    INTEGER_LITERAL, FLOAT_LITERAL, STRING_LITERAL,

    // 식별자
    IDENTIFIER,

    // 연산자
    PLUS, MINUS, STAR, SLASH,
    EQ, NEQ, LT, GT, LTE, GTE,

    // 구두점
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,

    // 특수
    EOF,
}

/**
 * SQL 토큰.
 *
 * @param type 토큰 타입
 * @param literal 원본 텍스트
 * @param position SQL 텍스트 내 시작 위치 (0-based)
 */
data class Token(
    val type: TokenType,
    val literal: String,
    val position: Int,
)
