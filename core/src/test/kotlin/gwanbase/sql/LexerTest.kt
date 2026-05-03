package gwanbase.sql

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LexerTest {

    @Test
    fun `빈 입력은 EOF 토큰만 반환`() {
        val tokens = Lexer("").tokenize()
        tokens shouldHaveSize 1
        tokens[0].type shouldBe TokenType.EOF
    }

    @Test
    fun `공백만 있는 입력은 EOF 토큰만 반환`() {
        val tokens = Lexer("   \t\n\r  ").tokenize()
        tokens shouldHaveSize 1
        tokens[0].type shouldBe TokenType.EOF
    }

    @Test
    fun `키워드는 대소문자를 구분하지 않는다`() {
        for (keyword in listOf("SELECT", "select", "Select", "sElEcT")) {
            val tokens = Lexer(keyword).tokenize()
            tokens shouldHaveSize 2
            tokens[0].type shouldBe TokenType.SELECT
            tokens[0].literal shouldBe keyword
        }
    }

    @Test
    fun `식별자를 인식한다`() {
        val tokens = Lexer("my_table column1").tokenize()
        tokens shouldHaveSize 3
        tokens[0].type shouldBe TokenType.IDENTIFIER
        tokens[0].literal shouldBe "my_table"
        tokens[1].type shouldBe TokenType.IDENTIFIER
        tokens[1].literal shouldBe "column1"
    }

    @Test
    fun `정수 리터럴을 인식한다`() {
        val tokens = Lexer("42 0 999").tokenize()
        tokens shouldHaveSize 4
        tokens[0].type shouldBe TokenType.INTEGER_LITERAL
        tokens[0].literal shouldBe "42"
        tokens[1].type shouldBe TokenType.INTEGER_LITERAL
        tokens[1].literal shouldBe "0"
        tokens[2].type shouldBe TokenType.INTEGER_LITERAL
        tokens[2].literal shouldBe "999"
    }

    @Test
    fun `실수 리터럴을 인식한다`() {
        val tokens = Lexer("3.14 0.5").tokenize()
        tokens shouldHaveSize 3
        tokens[0].type shouldBe TokenType.FLOAT_LITERAL
        tokens[0].literal shouldBe "3.14"
        tokens[1].type shouldBe TokenType.FLOAT_LITERAL
        tokens[1].literal shouldBe "0.5"
    }

    @Test
    fun `문자열 리터럴을 인식한다`() {
        val tokens = Lexer("'hello' 'world'").tokenize()
        tokens shouldHaveSize 3
        tokens[0].type shouldBe TokenType.STRING_LITERAL
        tokens[0].literal shouldBe "hello"
        tokens[1].type shouldBe TokenType.STRING_LITERAL
        tokens[1].literal shouldBe "world"
    }

    @Test
    fun `문자열 리터럴 이스케이프 처리`() {
        val tokens = Lexer("'it''s'").tokenize()
        tokens shouldHaveSize 2
        tokens[0].type shouldBe TokenType.STRING_LITERAL
        tokens[0].literal shouldBe "it's"
    }

    @Test
    fun `닫히지 않은 문자열은 에러`() {
        val ex = assertThrows<ParseException> { Lexer("'unterminated").tokenize() }
        ex.position shouldBe 0
    }

    @Test
    fun `연산자를 인식한다`() {
        val tokens = Lexer("= != <> < > <= >= + - * /").tokenize()
        tokens shouldHaveSize 12
        tokens[0].type shouldBe TokenType.EQ
        tokens[1].type shouldBe TokenType.NEQ
        tokens[2].type shouldBe TokenType.NEQ
        tokens[3].type shouldBe TokenType.LT
        tokens[4].type shouldBe TokenType.GT
        tokens[5].type shouldBe TokenType.LTE
        tokens[6].type shouldBe TokenType.GTE
        tokens[7].type shouldBe TokenType.PLUS
        tokens[8].type shouldBe TokenType.MINUS
        tokens[9].type shouldBe TokenType.STAR
        tokens[10].type shouldBe TokenType.SLASH
    }

    @Test
    fun `구두점을 인식한다`() {
        val tokens = Lexer("( ) , ;").tokenize()
        tokens shouldHaveSize 5
        tokens[0].type shouldBe TokenType.LPAREN
        tokens[1].type shouldBe TokenType.RPAREN
        tokens[2].type shouldBe TokenType.COMMA
        tokens[3].type shouldBe TokenType.SEMICOLON
    }

    @Test
    fun `주석을 건너뛴다`() {
        val tokens = Lexer("SELECT -- comment\nFROM").tokenize()
        tokens shouldHaveSize 3
        tokens[0].type shouldBe TokenType.SELECT
        tokens[1].type shouldBe TokenType.FROM
    }

    @Test
    fun `위치를 정확히 추적한다`() {
        val tokens = Lexer("SELECT id FROM t").tokenize()
        tokens[0].position shouldBe 0   // SELECT
        tokens[1].position shouldBe 7   // id
        tokens[2].position shouldBe 10  // FROM
        tokens[3].position shouldBe 15  // t
    }

    @Test
    fun `잘못된 문자는 에러`() {
        assertThrows<ParseException> { Lexer("@").tokenize() }
    }

    @Test
    fun `SELECT 문 전체 토큰화`() {
        val sql = "SELECT id, name FROM users WHERE age >= 18 ORDER BY name ASC LIMIT 10"
        val tokens = Lexer(sql).tokenize()
        val types = tokens.map { it.type }
        types shouldBe listOf(
            TokenType.SELECT,
            TokenType.IDENTIFIER,  // id
            TokenType.COMMA,
            TokenType.IDENTIFIER,  // name
            TokenType.FROM,
            TokenType.IDENTIFIER,  // users
            TokenType.WHERE,
            TokenType.IDENTIFIER,  // age
            TokenType.GTE,
            TokenType.INTEGER_LITERAL, // 18
            TokenType.ORDER,
            TokenType.BY,
            TokenType.IDENTIFIER,  // name
            TokenType.ASC,
            TokenType.LIMIT,
            TokenType.INTEGER_LITERAL, // 10
            TokenType.EOF,
        )
    }

    @Test
    fun `BEGIN 키워드를 토큰화한다`() {
        val tokens = Lexer("BEGIN").tokenize()
        tokens[0].type shouldBe TokenType.BEGIN
    }

    @Test
    fun `COMMIT 키워드를 토큰화한다`() {
        val tokens = Lexer("COMMIT").tokenize()
        tokens[0].type shouldBe TokenType.COMMIT
    }

    @Test
    fun `ROLLBACK 키워드를 토큰화한다`() {
        val tokens = Lexer("ROLLBACK").tokenize()
        tokens[0].type shouldBe TokenType.ROLLBACK
    }

    @Test
    fun `JOIN 키워드를 토큰화한다`() {
        val tokens = Lexer("JOIN").tokenize()
        tokens[0].type shouldBe TokenType.JOIN
    }

    @Test
    fun `ON 키워드를 토큰화한다`() {
        val tokens = Lexer("ON").tokenize()
        tokens[0].type shouldBe TokenType.ON
    }

    @Test
    fun `INDEX 키워드를 토큰화한다`() {
        val tokens = Lexer("INDEX").tokenize()
        tokens[0].type shouldBe TokenType.INDEX
    }

    @Test
    fun `ANALYZE 키워드를 토큰화한다`() {
        val tokens = Lexer("ANALYZE").tokenize()
        tokens[0].type shouldBe TokenType.ANALYZE
    }

    @Test
    fun `EXPLAIN 키워드를 토큰화한다`() {
        val tokens = Lexer("EXPLAIN").tokenize()
        tokens[0].type shouldBe TokenType.EXPLAIN
    }

    @Test
    fun `DOT 구두점을 토큰화한다`() {
        val tokens = Lexer("t.col").tokenize()
        tokens shouldHaveSize 4
        tokens[0].type shouldBe TokenType.IDENTIFIER
        tokens[0].literal shouldBe "t"
        tokens[1].type shouldBe TokenType.DOT
        tokens[1].literal shouldBe "."
        tokens[2].type shouldBe TokenType.IDENTIFIER
        tokens[2].literal shouldBe "col"
    }

    @Test
    fun `CREATE INDEX 문 토큰화`() {
        val sql = "CREATE INDEX idx_age ON users (age)"
        val tokens = Lexer(sql).tokenize()
        val types = tokens.map { it.type }
        types shouldBe listOf(
            TokenType.CREATE,
            TokenType.INDEX,
            TokenType.IDENTIFIER,  // idx_age
            TokenType.ON,
            TokenType.IDENTIFIER,  // users
            TokenType.LPAREN,
            TokenType.IDENTIFIER,  // age
            TokenType.RPAREN,
            TokenType.EOF,
        )
    }

    @Test
    fun `CREATE TABLE 문 토큰화`() {
        val sql = "CREATE TABLE users (id INT NOT NULL, name VARCHAR(100))"
        val tokens = Lexer(sql).tokenize()
        val types = tokens.map { it.type }
        types shouldBe listOf(
            TokenType.CREATE,
            TokenType.TABLE,
            TokenType.IDENTIFIER,     // users
            TokenType.LPAREN,
            TokenType.IDENTIFIER,     // id
            TokenType.INT,
            TokenType.NOT,
            TokenType.NULL,
            TokenType.COMMA,
            TokenType.IDENTIFIER,     // name
            TokenType.VARCHAR,
            TokenType.LPAREN,
            TokenType.INTEGER_LITERAL, // 100
            TokenType.RPAREN,
            TokenType.RPAREN,
            TokenType.EOF,
        )
    }
}
