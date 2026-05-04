package gwanbase.sql

/**
 * SQL 텍스트를 토큰 리스트로 변환하는 렉서.
 *
 * 단일 패스로 문자열을 순회하며 토큰을 생성한다.
 * 공백·줄바꿈을 스킵하고, `--` 주석을 무시하며,
 * 키워드와 식별자를 대소문자 무시로 구분한다.
 *
 * @param source SQL 텍스트
 */
class Lexer(private val source: String) {

    private var pos: Int = 0

    companion object {
        /** 키워드 테이블 (대문자 키 → TokenType) */
        private val KEYWORDS: Map<String, TokenType> = mapOf(
            "SELECT" to TokenType.SELECT,
            "FROM" to TokenType.FROM,
            "WHERE" to TokenType.WHERE,
            "INSERT" to TokenType.INSERT,
            "INTO" to TokenType.INTO,
            "VALUES" to TokenType.VALUES,
            "UPDATE" to TokenType.UPDATE,
            "SET" to TokenType.SET,
            "DELETE" to TokenType.DELETE,
            "CREATE" to TokenType.CREATE,
            "DROP" to TokenType.DROP,
            "TABLE" to TokenType.TABLE,
            "ORDER" to TokenType.ORDER,
            "BY" to TokenType.BY,
            "ASC" to TokenType.ASC,
            "DESC" to TokenType.DESC,
            "LIMIT" to TokenType.LIMIT,
            "AND" to TokenType.AND,
            "OR" to TokenType.OR,
            "NOT" to TokenType.NOT,
            "NULL" to TokenType.NULL,
            "TRUE" to TokenType.TRUE,
            "FALSE" to TokenType.FALSE,
            "IS" to TokenType.IS,
            "INT" to TokenType.INT,
            "INTEGER" to TokenType.INTEGER,
            "BIGINT" to TokenType.BIGINT,
            "BOOLEAN" to TokenType.BOOLEAN,
            "DOUBLE" to TokenType.DOUBLE,
            "FLOAT" to TokenType.FLOAT,
            "VARCHAR" to TokenType.VARCHAR,
            "TIMESTAMP" to TokenType.TIMESTAMP,
            "BEGIN" to TokenType.BEGIN,
            "COMMIT" to TokenType.COMMIT,
            "ROLLBACK" to TokenType.ROLLBACK,
            "JOIN" to TokenType.JOIN,
            "ON" to TokenType.ON,
            "INDEX" to TokenType.INDEX,
            "ANALYZE" to TokenType.ANALYZE,
            "EXPLAIN" to TokenType.EXPLAIN,
        )
    }

    /**
     * SQL 텍스트를 토큰 리스트로 변환한다.
     *
     * @return 토큰 리스트 (마지막은 항상 [TokenType.EOF])
     * @throws ParseException 잘못된 문자가 포함된 경우
     */
    fun tokenize(): List<Token> {
        pos = 0
        val tokens = mutableListOf<Token>()

        while (pos < source.length) {
            skipWhitespace()
            if (pos >= source.length) break

            // 주석 스킵
            if (current() == '-' && peek() == '-') {
                skipLineComment()
                continue
            }

            val ch = current()

            val token = when {
                ch.isLetter() || ch == '_' -> readIdentifierOrKeyword()
                ch.isDigit() -> readNumber()
                ch == '\'' -> readString()
                else -> readOperatorOrPunctuation()
            }

            tokens.add(token)
        }

        tokens.add(Token(TokenType.EOF, "", source.length))
        return tokens
    }

    private fun current(): Char = source[pos]

    private fun peek(): Char? = if (pos + 1 < source.length) source[pos + 1] else null

    private fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) {
            pos++
        }
    }

    private fun skipLineComment() {
        // '--' 이후 줄 끝까지 스킵
        while (pos < source.length && source[pos] != '\n') {
            pos++
        }
    }

    /**
     * 식별자 또는 키워드를 읽는다.
     * 알파벳/숫자/밑줄로 구성된 토큰을 읽고 키워드 테이블에서 매칭한다.
     */
    private fun readIdentifierOrKeyword(): Token {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
            pos++
        }
        val literal = source.substring(start, pos)
        val type = KEYWORDS[literal.uppercase()] ?: TokenType.IDENTIFIER
        return Token(type, literal, start)
    }

    /**
     * 숫자 리터럴을 읽는다. 정수 우선, `.` 뒤에 숫자가 있으면 실수.
     */
    private fun readNumber(): Token {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) {
            pos++
        }
        // 소수점 확인
        if (pos < source.length && source[pos] == '.' && pos + 1 < source.length && source[pos + 1].isDigit()) {
            pos++ // '.' 건너뛰기
            while (pos < source.length && source[pos].isDigit()) {
                pos++
            }
            return Token(TokenType.FLOAT_LITERAL, source.substring(start, pos), start)
        }
        return Token(TokenType.INTEGER_LITERAL, source.substring(start, pos), start)
    }

    /**
     * 문자열 리터럴을 읽는다. 작은따옴표로 감싸며, `''`는 이스케이프 처리한다.
     */
    private fun readString(): Token {
        val start = pos
        pos++ // 여는 따옴표 건너뛰기
        val sb = StringBuilder()
        while (pos < source.length) {
            if (source[pos] == '\'') {
                // 이스케이프 확인: '' → '
                if (pos + 1 < source.length && source[pos + 1] == '\'') {
                    sb.append('\'')
                    pos += 2
                } else {
                    pos++ // 닫는 따옴표
                    return Token(TokenType.STRING_LITERAL, sb.toString(), start)
                }
            } else {
                sb.append(source[pos])
                pos++
            }
        }
        throw ParseException("닫히지 않은 문자열 리터럴", start)
    }

    /**
     * 연산자 또는 구두점을 읽는다.
     */
    private fun readOperatorOrPunctuation(): Token {
        val start = pos
        val ch = source[pos]
        pos++

        return when (ch) {
            '+' -> Token(TokenType.PLUS, "+", start)
            '-' -> Token(TokenType.MINUS, "-", start)
            '*' -> Token(TokenType.STAR, "*", start)
            '/' -> Token(TokenType.SLASH, "/", start)
            '(' -> Token(TokenType.LPAREN, "(", start)
            ')' -> Token(TokenType.RPAREN, ")", start)
            ',' -> Token(TokenType.COMMA, ",", start)
            ';' -> Token(TokenType.SEMICOLON, ";", start)
            '.' -> Token(TokenType.DOT, ".", start)
            '=' -> Token(TokenType.EQ, "=", start)
            '!' -> {
                if (pos < source.length && source[pos] == '=') {
                    pos++
                    Token(TokenType.NEQ, "!=", start)
                } else {
                    throw ParseException("예상하지 못한 문자: '!'", start)
                }
            }
            '<' -> {
                when {
                    pos < source.length && source[pos] == '=' -> {
                        pos++
                        Token(TokenType.LTE, "<=", start)
                    }
                    pos < source.length && source[pos] == '>' -> {
                        pos++
                        Token(TokenType.NEQ, "<>", start)
                    }
                    else -> Token(TokenType.LT, "<", start)
                }
            }
            '>' -> {
                if (pos < source.length && source[pos] == '=') {
                    pos++
                    Token(TokenType.GTE, ">=", start)
                } else {
                    Token(TokenType.GT, ">", start)
                }
            }
            else -> throw ParseException("예상하지 못한 문자: '$ch'", start)
        }
    }
}
