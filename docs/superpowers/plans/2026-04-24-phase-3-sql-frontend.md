# Phase 3: SQL Frontend 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SQL 텍스트를 파싱하여 Phase 2의 Database API를 호출하고 결과를 반환하는 SQL 프론트엔드를 구현한다.

**Architecture:** Lexer → Parser (Pratt parsing) → Binder (Catalog 검증) → Executor (직접 실행) 4단계 파이프라인. Phase 4 Volcano 모델 도입 시 Executor만 교체하고 나머지는 재사용한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5 + Kotest assertions, 기존 Phase 2 Database/Catalog/Tuple API

---

## 파일 구조

### 새로 생성할 파일

| 파일 | 책임 |
|---|---|
| `core/src/main/kotlin/gwanbase/sql/Token.kt` | TokenType enum, Token data class |
| `core/src/main/kotlin/gwanbase/sql/Lexer.kt` | SQL 텍스트 → Token 리스트 변환 |
| `core/src/main/kotlin/gwanbase/sql/Ast.kt` | Statement, Expression 등 AST sealed class |
| `core/src/main/kotlin/gwanbase/sql/Parser.kt` | Token 리스트 → AST 변환 (Pratt parsing) |
| `core/src/main/kotlin/gwanbase/sql/Binder.kt` | AST를 Catalog과 대조하여 검증 |
| `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` | AST → Database API 호출 → ExecuteResult 반환 |
| `core/src/main/kotlin/gwanbase/sql/SqlException.kt` | ParseException, BindException 정의 |
| `core/src/test/kotlin/gwanbase/sql/LexerTest.kt` | Lexer 단위 테스트 |
| `core/src/test/kotlin/gwanbase/sql/ParserTest.kt` | Parser 단위 테스트 |
| `core/src/test/kotlin/gwanbase/sql/BinderTest.kt` | Binder 단위 테스트 |
| `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt` | Executor 통합 테스트 |

### 수정할 파일

| 파일 | 변경 내용 |
|---|---|
| `core/src/main/kotlin/gwanbase/table/Database.kt` | `dropTable()`, `updateTuple()`, `getCatalog()` 메서드 추가 |
| `core/src/test/kotlin/gwanbase/table/DatabaseTest.kt` | 추가 메서드 테스트 |

---

## Task 1: Token & TokenType 정의

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/Token.kt`

데이터 클래스 정의만 수행한다. 테스트는 Lexer 테스트에서 함께 검증한다.

- [ ] **Step 1: Token.kt 작성**

```kotlin
package gwanbase.sql

/**
 * SQL 토큰 타입.
 */
enum class TokenType {
    // 키워드
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, DROP, TABLE, ORDER, BY, ASC, DESC, LIMIT,
    AND, OR, NOT, NULL, TRUE, FALSE,
    IS, NOT_NULL,
    INT, INTEGER, BIGINT, BOOLEAN, DOUBLE, FLOAT, VARCHAR, TIMESTAMP,

    // 리터럴
    INTEGER_LITERAL, FLOAT_LITERAL, STRING_LITERAL,

    // 식별자
    IDENTIFIER,

    // 연산자
    PLUS, MINUS, STAR, SLASH,
    EQ, NEQ, LT, GT, LTE, GTE,

    // 구두점
    LPAREN, RPAREN, COMMA, SEMICOLON,

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
```

- [ ] **Step 2: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Token.kt
git commit -m "[Phase 3] Token, TokenType 데이터 클래스 정의"
```

---

## Task 2: SqlException 정의

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/SqlException.kt`

- [ ] **Step 1: SqlException.kt 작성**

```kotlin
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
```

- [ ] **Step 2: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/SqlException.kt
git commit -m "[Phase 3] ParseException, BindException 정의"
```

---

## Task 3: Lexer 구현

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/Lexer.kt`
- Create: `core/src/test/kotlin/gwanbase/sql/LexerTest.kt`

### Step 3-1: 빈 입력 테스트

- [ ] **Step 3-1a: 빈 입력 → EOF 테스트 작성**

```kotlin
package gwanbase.sql

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LexerTest {

    @Test
    fun `빈 입력은 EOF 토큰만 반환`() {
        val tokens = Lexer("").tokenize()
        tokens shouldHaveSize 1
        tokens[0].type shouldBe TokenType.EOF
    }

    @Test
    fun `공백만 있는 입력은 EOF 토큰만 반환`() {
        val tokens = Lexer("   \t\n  ").tokenize()
        tokens shouldHaveSize 1
        tokens[0].type shouldBe TokenType.EOF
    }
}
```

- [ ] **Step 3-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: 컴파일 에러 (Lexer 클래스 없음)

- [ ] **Step 3-1c: Lexer 기본 골격 구현**

```kotlin
package gwanbase.sql

/**
 * SQL 텍스트를 토큰 리스트로 변환하는 렉서.
 *
 * 단일 패스로 문자열을 순회하며 토큰을 생성한다.
 * 키워드는 대소문자를 구분하지 않는다.
 */
class Lexer(private val source: String) {

    private var pos = 0

    private val keywords = mapOf(
        "SELECT" to TokenType.SELECT, "FROM" to TokenType.FROM,
        "WHERE" to TokenType.WHERE, "INSERT" to TokenType.INSERT,
        "INTO" to TokenType.INTO, "VALUES" to TokenType.VALUES,
        "UPDATE" to TokenType.UPDATE, "SET" to TokenType.SET,
        "DELETE" to TokenType.DELETE, "CREATE" to TokenType.CREATE,
        "DROP" to TokenType.DROP, "TABLE" to TokenType.TABLE,
        "ORDER" to TokenType.ORDER, "BY" to TokenType.BY,
        "ASC" to TokenType.ASC, "DESC" to TokenType.DESC,
        "LIMIT" to TokenType.LIMIT, "AND" to TokenType.AND,
        "OR" to TokenType.OR, "NOT" to TokenType.NOT,
        "NULL" to TokenType.NULL, "TRUE" to TokenType.TRUE,
        "FALSE" to TokenType.FALSE, "IS" to TokenType.IS,
        "INT" to TokenType.INT, "INTEGER" to TokenType.INTEGER,
        "BIGINT" to TokenType.BIGINT, "BOOLEAN" to TokenType.BOOLEAN,
        "DOUBLE" to TokenType.DOUBLE, "FLOAT" to TokenType.FLOAT,
        "VARCHAR" to TokenType.VARCHAR, "TIMESTAMP" to TokenType.TIMESTAMP,
    )

    /**
     * SQL 텍스트를 토큰 리스트로 변환한다.
     * 마지막 토큰은 항상 EOF이다.
     */
    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            val token = nextToken()
            tokens.add(token)
            if (token.type == TokenType.EOF) break
        }
        return tokens
    }

    private fun nextToken(): Token {
        skipWhitespaceAndComments()

        if (pos >= source.length) {
            return Token(TokenType.EOF, "", pos)
        }

        val start = pos
        val ch = source[pos]

        return when {
            ch.isLetter() || ch == '_' -> readIdentifierOrKeyword()
            ch.isDigit() -> readNumber()
            ch == '\'' -> readString()
            else -> readSymbol()
        }
    }

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            val ch = source[pos]
            if (ch.isWhitespace()) {
                pos++
            } else if (ch == '-' && pos + 1 < source.length && source[pos + 1] == '-') {
                // 한줄 주석: 줄 끝까지 스킵
                while (pos < source.length && source[pos] != '\n') pos++
            } else {
                break
            }
        }
    }

    private fun readIdentifierOrKeyword(): Token {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
            pos++
        }
        val text = source.substring(start, pos)
        val type = keywords[text.uppercase()] ?: TokenType.IDENTIFIER
        return Token(type, text, start)
    }

    private fun readNumber(): Token {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++

        // 소수점 확인
        if (pos < source.length && source[pos] == '.' && pos + 1 < source.length && source[pos + 1].isDigit()) {
            pos++ // '.' 건너뛰기
            while (pos < source.length && source[pos].isDigit()) pos++
            return Token(TokenType.FLOAT_LITERAL, source.substring(start, pos), start)
        }

        return Token(TokenType.INTEGER_LITERAL, source.substring(start, pos), start)
    }

    private fun readString(): Token {
        val start = pos
        pos++ // 여는 따옴표 건너뛰기
        val sb = StringBuilder()
        while (pos < source.length) {
            if (source[pos] == '\'') {
                if (pos + 1 < source.length && source[pos + 1] == '\'') {
                    // 이스케이프된 따옴표
                    sb.append('\'')
                    pos += 2
                } else {
                    pos++ // 닫는 따옴표 건너뛰기
                    return Token(TokenType.STRING_LITERAL, sb.toString(), start)
                }
            } else {
                sb.append(source[pos])
                pos++
            }
        }
        throw ParseException("문자열 리터럴이 닫히지 않았다", start)
    }

    private fun readSymbol(): Token {
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
            '=' -> Token(TokenType.EQ, "=", start)
            '<' -> {
                if (pos < source.length && source[pos] == '=') {
                    pos++
                    Token(TokenType.LTE, "<=", start)
                } else if (pos < source.length && source[pos] == '>') {
                    pos++
                    Token(TokenType.NEQ, "<>", start)
                } else {
                    Token(TokenType.LT, "<", start)
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
            '!' -> {
                if (pos < source.length && source[pos] == '=') {
                    pos++
                    Token(TokenType.NEQ, "!=", start)
                } else {
                    throw ParseException("예상하지 못한 문자: '!'", start)
                }
            }
            else -> throw ParseException("예상하지 못한 문자: '$ch'", start)
        }
    }
}
```

- [ ] **Step 3-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: PASS

### Step 3-2: 키워드·식별자 테스트

- [ ] **Step 3-2a: 키워드 대소문자 무시 테스트 추가**

LexerTest에 추가:

```kotlin
@Test
fun `키워드는 대소문자를 구분하지 않는다`() {
    val inputs = listOf("SELECT", "select", "Select", "sElEcT")
    for (input in inputs) {
        val tokens = Lexer(input).tokenize()
        tokens[0].type shouldBe TokenType.SELECT
        tokens[0].literal shouldBe input
    }
}

@Test
fun `식별자를 인식한다`() {
    val tokens = Lexer("my_table column1").tokenize()
    tokens[0].type shouldBe TokenType.IDENTIFIER
    tokens[0].literal shouldBe "my_table"
    tokens[1].type shouldBe TokenType.IDENTIFIER
    tokens[1].literal shouldBe "column1"
}
```

- [ ] **Step 3-2b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: PASS

### Step 3-3: 리터럴 테스트

- [ ] **Step 3-3a: 숫자·문자열 리터럴 테스트 추가**

```kotlin
@Test
fun `정수 리터럴을 인식한다`() {
    val tokens = Lexer("42 0 999").tokenize()
    tokens[0].type shouldBe TokenType.INTEGER_LITERAL
    tokens[0].literal shouldBe "42"
    tokens[1].literal shouldBe "0"
    tokens[2].literal shouldBe "999"
}

@Test
fun `실수 리터럴을 인식한다`() {
    val tokens = Lexer("3.14 0.5").tokenize()
    tokens[0].type shouldBe TokenType.FLOAT_LITERAL
    tokens[0].literal shouldBe "3.14"
    tokens[1].type shouldBe TokenType.FLOAT_LITERAL
    tokens[1].literal shouldBe "0.5"
}

@Test
fun `문자열 리터럴을 인식한다`() {
    val tokens = Lexer("'hello' 'world'").tokenize()
    tokens[0].type shouldBe TokenType.STRING_LITERAL
    tokens[0].literal shouldBe "hello"
    tokens[1].literal shouldBe "world"
}

@Test
fun `문자열 리터럴 이스케이프 처리`() {
    val tokens = Lexer("'it''s'").tokenize()
    tokens[0].type shouldBe TokenType.STRING_LITERAL
    tokens[0].literal shouldBe "it's"
}

@Test
fun `닫히지 않은 문자열은 에러`() {
    val ex = org.junit.jupiter.api.assertThrows<ParseException> {
        Lexer("'unclosed").tokenize()
    }
    ex.position shouldBe 0
}
```

- [ ] **Step 3-3b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: PASS

### Step 3-4: 연산자·구두점·주석 테스트

- [ ] **Step 3-4a: 테스트 추가**

```kotlin
@Test
fun `연산자를 인식한다`() {
    val tokens = Lexer("= != <> < > <= >= + - * /").tokenize()
    val types = tokens.dropLast(1).map { it.type }
    types shouldBe listOf(
        TokenType.EQ, TokenType.NEQ, TokenType.NEQ,
        TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE,
        TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH,
    )
}

@Test
fun `구두점을 인식한다`() {
    val tokens = Lexer("( ) , ;").tokenize()
    tokens[0].type shouldBe TokenType.LPAREN
    tokens[1].type shouldBe TokenType.RPAREN
    tokens[2].type shouldBe TokenType.COMMA
    tokens[3].type shouldBe TokenType.SEMICOLON
}

@Test
fun `주석을 건너뛴다`() {
    val tokens = Lexer("SELECT -- this is a comment\nFROM").tokenize()
    tokens[0].type shouldBe TokenType.SELECT
    tokens[1].type shouldBe TokenType.FROM
    tokens[2].type shouldBe TokenType.EOF
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
    org.junit.jupiter.api.assertThrows<ParseException> {
        Lexer("@").tokenize()
    }
}
```

- [ ] **Step 3-4b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: PASS

### Step 3-5: 완전한 SQL 문 토큰화 테스트

- [ ] **Step 3-5a: 통합 토큰화 테스트 추가**

```kotlin
@Test
fun `SELECT 문 전체 토큰화`() {
    val sql = "SELECT id, name FROM users WHERE age >= 18 ORDER BY name ASC LIMIT 10;"
    val tokens = Lexer(sql).tokenize()
    val types = tokens.map { it.type }
    types shouldBe listOf(
        TokenType.SELECT, TokenType.IDENTIFIER, TokenType.COMMA, TokenType.IDENTIFIER,
        TokenType.FROM, TokenType.IDENTIFIER,
        TokenType.WHERE, TokenType.IDENTIFIER, TokenType.GTE, TokenType.INTEGER_LITERAL,
        TokenType.ORDER, TokenType.BY, TokenType.IDENTIFIER, TokenType.ASC,
        TokenType.LIMIT, TokenType.INTEGER_LITERAL, TokenType.SEMICOLON, TokenType.EOF,
    )
}

@Test
fun `CREATE TABLE 문 토큰화`() {
    val sql = "CREATE TABLE users (id INT NOT NULL, name VARCHAR(100));"
    val tokens = Lexer(sql).tokenize()
    val types = tokens.map { it.type }
    types shouldBe listOf(
        TokenType.CREATE, TokenType.TABLE, TokenType.IDENTIFIER,
        TokenType.LPAREN,
        TokenType.IDENTIFIER, TokenType.INT, TokenType.NOT, TokenType.NULL, TokenType.COMMA,
        TokenType.IDENTIFIER, TokenType.VARCHAR, TokenType.LPAREN, TokenType.INTEGER_LITERAL, TokenType.RPAREN,
        TokenType.RPAREN, TokenType.SEMICOLON, TokenType.EOF,
    )
}
```

- [ ] **Step 3-5b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 3-6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Lexer.kt core/src/test/kotlin/gwanbase/sql/LexerTest.kt
git commit -m "[Phase 3] Lexer 구현 및 테스트"
```

---

## Task 4: AST 정의

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/Ast.kt`

데이터 클래스만 정의한다. 테스트는 Parser 테스트에서 함께 검증한다.

- [ ] **Step 1: Ast.kt 작성**

```kotlin
package gwanbase.sql

/**
 * SQL 문(statement) AST.
 */
sealed class Statement {
    data class CreateTable(
        val tableName: String,
        val columns: List<ColumnDef>,
    ) : Statement()

    data class DropTable(val tableName: String) : Statement()

    data class Insert(
        val tableName: String,
        val columns: List<String>,
        val values: List<Expression>,
    ) : Statement()

    data class Select(
        val columns: List<SelectItem>,
        val tableName: String,
        val where: Expression?,
        val orderBy: OrderByClause?,
        val limit: Int?,
    ) : Statement()

    data class Update(
        val tableName: String,
        val assignments: List<Assignment>,
        val where: Expression?,
    ) : Statement()

    data class Delete(
        val tableName: String,
        val where: Expression?,
    ) : Statement()
}

/**
 * CREATE TABLE의 컬럼 정의.
 */
data class ColumnDef(
    val name: String,
    val dataType: SqlDataType,
    val nullable: Boolean = true,
)

/**
 * SQL 데이터 타입 (AST 레벨).
 *
 * Phase 2의 DataType과는 별도 타입이다. Executor에서 변환한다.
 */
sealed class SqlDataType {
    data object BooleanType : SqlDataType()
    data object IntType : SqlDataType()
    data object BigIntType : SqlDataType()
    data object DoubleType : SqlDataType()
    data object TimestampType : SqlDataType()
    data class VarcharType(val maxLength: Int) : SqlDataType()
}

/**
 * SELECT 목록 항목.
 */
sealed class SelectItem {
    /** SELECT * */
    data object Star : SelectItem()
    /** SELECT expr */
    data class ExprItem(val expr: Expression) : SelectItem()
}

/**
 * ORDER BY 절.
 */
data class OrderByClause(val column: String, val ascending: Boolean = true)

/**
 * UPDATE SET 절의 대입.
 */
data class Assignment(val column: String, val value: Expression)

/**
 * 표현식 AST.
 */
sealed class Expression {
    data class IntLiteral(val value: Long) : Expression()
    data class FloatLiteral(val value: Double) : Expression()
    data class StringLiteral(val value: String) : Expression()
    data class BoolLiteral(val value: Boolean) : Expression()
    data object NullLiteral : Expression()

    data class ColumnRef(val name: String) : Expression()

    data class BinaryOp(
        val left: Expression,
        val op: BinaryOperator,
        val right: Expression,
    ) : Expression()

    data class UnaryOp(
        val op: UnaryOperator,
        val operand: Expression,
    ) : Expression()

    data class IsNull(val expr: Expression) : Expression()
    data class IsNotNull(val expr: Expression) : Expression()
}

/**
 * 이항 연산자.
 */
enum class BinaryOperator {
    ADD, SUB, MUL, DIV,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR,
}

/**
 * 단항 연산자.
 */
enum class UnaryOperator {
    NEGATE, NOT,
}
```

- [ ] **Step 2: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Ast.kt
git commit -m "[Phase 3] AST sealed class 정의"
```

---

## Task 5: Parser 구현 — DDL

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Create: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

Parser를 DDL → DML 순서로 점진적으로 구현한다.

### Step 5-1: Parser 골격 + CREATE TABLE

- [ ] **Step 5-1a: CREATE TABLE 테스트 작성**

```kotlin
package gwanbase.sql

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParserTest {

    private fun parse(sql: String): Statement {
        val tokens = Lexer(sql).tokenize()
        return Parser(tokens).parse()
    }

    // === CREATE TABLE ===

    @Test
    fun `CREATE TABLE 단일 컬럼`() {
        val stmt = parse("CREATE TABLE users (id INT);")
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.tableName shouldBe "users"
        stmt.columns.size shouldBe 1
        stmt.columns[0].name shouldBe "id"
        stmt.columns[0].dataType shouldBe SqlDataType.IntType
        stmt.columns[0].nullable shouldBe true
    }

    @Test
    fun `CREATE TABLE 다중 컬럼 및 NOT NULL`() {
        val stmt = parse("""
            CREATE TABLE users (
                id INT NOT NULL,
                name VARCHAR(100),
                active BOOLEAN NOT NULL
            );
        """.trimIndent())
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.columns.size shouldBe 3
        stmt.columns[0].let {
            it.name shouldBe "id"; it.dataType shouldBe SqlDataType.IntType; it.nullable shouldBe false
        }
        stmt.columns[1].let {
            it.name shouldBe "name"; it.dataType shouldBe SqlDataType.VarcharType(100); it.nullable shouldBe true
        }
        stmt.columns[2].let {
            it.name shouldBe "active"; it.dataType shouldBe SqlDataType.BooleanType; it.nullable shouldBe false
        }
    }

    @Test
    fun `CREATE TABLE 모든 데이터 타입`() {
        val stmt = parse("""
            CREATE TABLE t (
                a BOOLEAN, b INT, c INTEGER, d BIGINT,
                e DOUBLE, f FLOAT, g TIMESTAMP, h VARCHAR(255)
            );
        """.trimIndent())
        stmt.shouldBeInstanceOf<Statement.CreateTable>()
        stmt.columns.map { it.dataType } shouldBe listOf(
            SqlDataType.BooleanType, SqlDataType.IntType, SqlDataType.IntType, SqlDataType.BigIntType,
            SqlDataType.DoubleType, SqlDataType.DoubleType, SqlDataType.TimestampType, SqlDataType.VarcharType(255),
        )
    }

    // === DROP TABLE ===

    @Test
    fun `DROP TABLE`() {
        val stmt = parse("DROP TABLE users;")
        stmt.shouldBeInstanceOf<Statement.DropTable>()
        stmt.tableName shouldBe "users"
    }
}
```

- [ ] **Step 5-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: 컴파일 에러 (Parser 클래스 없음)

- [ ] **Step 5-1c: Parser 골격 + DDL 파싱 구현**

```kotlin
package gwanbase.sql

/**
 * SQL 토큰 리스트를 AST로 변환하는 재귀 하강 파서.
 *
 * 표현식 파싱에는 Pratt parsing(precedence climbing)을 사용한다.
 */
class Parser(private val tokens: List<Token>) {

    private var pos = 0

    /**
     * 토큰 리스트를 파싱하여 하나의 Statement를 반환한다.
     */
    fun parse(): Statement {
        val stmt = parseStatement()
        // 세미콜론은 선택적
        if (peek().type == TokenType.SEMICOLON) advance()
        expect(TokenType.EOF, "SQL 문 뒤에 추가 토큰이 있다")
        return stmt
    }

    private fun parseStatement(): Statement {
        return when (peek().type) {
            TokenType.CREATE -> parseCreateTable()
            TokenType.DROP -> parseDropTable()
            TokenType.INSERT -> parseInsert()
            TokenType.SELECT -> parseSelect()
            TokenType.UPDATE -> parseUpdate()
            TokenType.DELETE -> parseDelete()
            else -> throw parseError("SQL 문이 예상되었다 (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP)")
        }
    }

    // ========== DDL ==========

    private fun parseCreateTable(): Statement.CreateTable {
        advance() // CREATE
        expect(TokenType.TABLE, "'TABLE' 키워드가 예상되었다")
        val name = expectIdentifier("테이블명이 예상되었다")
        expect(TokenType.LPAREN, "'(' 가 예상되었다")

        val columns = mutableListOf<ColumnDef>()
        columns.add(parseColumnDef())
        while (peek().type == TokenType.COMMA) {
            advance() // COMMA
            columns.add(parseColumnDef())
        }

        expect(TokenType.RPAREN, "')' 가 예상되었다")
        return Statement.CreateTable(name, columns)
    }

    private fun parseColumnDef(): ColumnDef {
        val name = expectIdentifier("컬럼명이 예상되었다")
        val dataType = parseDataType()
        var nullable = true
        if (peek().type == TokenType.NOT) {
            advance() // NOT
            expect(TokenType.NULL, "'NULL' 키워드가 예상되었다")
            nullable = false
        }
        return ColumnDef(name, dataType, nullable)
    }

    private fun parseDataType(): SqlDataType {
        val token = advance()
        return when (token.type) {
            TokenType.BOOLEAN -> SqlDataType.BooleanType
            TokenType.INT, TokenType.INTEGER -> SqlDataType.IntType
            TokenType.BIGINT -> SqlDataType.BigIntType
            TokenType.DOUBLE, TokenType.FLOAT -> SqlDataType.DoubleType
            TokenType.TIMESTAMP -> SqlDataType.TimestampType
            TokenType.VARCHAR -> {
                expect(TokenType.LPAREN, "'(' 가 예상되었다 (VARCHAR 길이)")
                val lengthToken = expect(TokenType.INTEGER_LITERAL, "VARCHAR 길이가 예상되었다")
                val maxLength = lengthToken.literal.toInt()
                expect(TokenType.RPAREN, "')' 가 예상되었다")
                SqlDataType.VarcharType(maxLength)
            }
            else -> throw ParseException("데이터 타입이 예상되었다", token.position)
        }
    }

    private fun parseDropTable(): Statement.DropTable {
        advance() // DROP
        expect(TokenType.TABLE, "'TABLE' 키워드가 예상되었다")
        val name = expectIdentifier("테이블명이 예상되었다")
        return Statement.DropTable(name)
    }

    // ========== DML (빈 스텁 - 다음 단계에서 구현) ==========

    private fun parseInsert(): Statement {
        throw parseError("INSERT 파싱은 아직 구현되지 않았다")
    }

    private fun parseSelect(): Statement {
        throw parseError("SELECT 파싱은 아직 구현되지 않았다")
    }

    private fun parseUpdate(): Statement {
        throw parseError("UPDATE 파싱은 아직 구현되지 않았다")
    }

    private fun parseDelete(): Statement {
        throw parseError("DELETE 파싱은 아직 구현되지 않았다")
    }

    // ========== 유틸리티 ==========

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token {
        val token = tokens[pos]
        if (token.type != TokenType.EOF) pos++
        return token
    }

    private fun expect(type: TokenType, message: String): Token {
        val token = peek()
        if (token.type != type) {
            throw ParseException("$message, 실제: '${token.literal}' (${token.type})", token.position)
        }
        return advance()
    }

    private fun expectIdentifier(message: String): String {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER) {
            throw ParseException("$message, 실제: '${token.literal}' (${token.type})", token.position)
        }
        advance()
        return token.literal
    }

    private fun parseError(message: String): ParseException {
        val token = peek()
        return ParseException("$message, 실제: '${token.literal}' (${token.type})", token.position)
    }
}
```

- [ ] **Step 5-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5-1e: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Parser.kt core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 3] Parser 골격 및 DDL (CREATE/DROP TABLE) 파싱 구현"
```

---

## Task 6: Parser 구현 — 표현식 (Pratt Parsing)

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

### Step 6-1: 표현식 파싱 테스트

- [ ] **Step 6-1a: 표현식 테스트 작성**

ParserTest에 추가:

```kotlin
// === 표현식 ===

/** 표현식을 직접 테스트하기 위한 헬퍼. SELECT expr FROM dummy; 형태로 감싼다. */
private fun parseExpr(exprSql: String): Expression {
    val stmt = parse("SELECT $exprSql FROM dummy;")
    stmt.shouldBeInstanceOf<Statement.Select>()
    val item = stmt.columns[0]
    item.shouldBeInstanceOf<SelectItem.ExprItem>()
    return item.expr
}

@Test
fun `리터럴 표현식`() {
    parseExpr("42").shouldBeInstanceOf<Expression.IntLiteral>().value shouldBe 42L
    parseExpr("3.14").shouldBeInstanceOf<Expression.FloatLiteral>().value shouldBe 3.14
    parseExpr("'hello'").shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "hello"
    parseExpr("TRUE").shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe true
    parseExpr("FALSE").shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe false
    parseExpr("NULL").shouldBeInstanceOf<Expression.NullLiteral>()
}

@Test
fun `컬럼 참조`() {
    parseExpr("age").shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "age"
}

@Test
fun `산술 연산자 우선순위 - 곱셈이 덧셈보다 높다`() {
    // a + b * c → a + (b * c)
    val expr = parseExpr("a + b * c")
    expr.shouldBeInstanceOf<Expression.BinaryOp>()
    expr.op shouldBe BinaryOperator.ADD
    expr.left.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "a"
    val right = expr.right.shouldBeInstanceOf<Expression.BinaryOp>()
    right.op shouldBe BinaryOperator.MUL
}

@Test
fun `괄호가 우선순위를 오버라이드한다`() {
    // (a + b) * c
    val expr = parseExpr("(a + b) * c")
    expr.shouldBeInstanceOf<Expression.BinaryOp>()
    expr.op shouldBe BinaryOperator.MUL
    val left = expr.left.shouldBeInstanceOf<Expression.BinaryOp>()
    left.op shouldBe BinaryOperator.ADD
}

@Test
fun `비교 연산자`() {
    val expr = parseExpr("age >= 18")
    expr.shouldBeInstanceOf<Expression.BinaryOp>()
    expr.op shouldBe BinaryOperator.GTE
}

@Test
fun `AND OR 우선순위 - AND가 OR보다 높다`() {
    // a = 1 OR b = 2 AND c = 3  →  a = 1 OR (b = 2 AND c = 3)
    val expr = parseExpr("a = 1 OR b = 2 AND c = 3")
    expr.shouldBeInstanceOf<Expression.BinaryOp>()
    expr.op shouldBe BinaryOperator.OR
    expr.right.shouldBeInstanceOf<Expression.BinaryOp>().op shouldBe BinaryOperator.AND
}

@Test
fun `NOT 단항 연산자`() {
    val expr = parseExpr("NOT active")
    expr.shouldBeInstanceOf<Expression.UnaryOp>()
    expr.op shouldBe UnaryOperator.NOT
    expr.operand.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "active"
}

@Test
fun `단항 부정`() {
    val expr = parseExpr("-price")
    expr.shouldBeInstanceOf<Expression.UnaryOp>()
    expr.op shouldBe UnaryOperator.NEGATE
    expr.operand.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "price"
}

@Test
fun `IS NULL 표현식`() {
    val expr = parseExpr("name IS NULL")
    expr.shouldBeInstanceOf<Expression.IsNull>()
    expr.expr.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "name"
}

@Test
fun `IS NOT NULL 표현식`() {
    val expr = parseExpr("name IS NOT NULL")
    expr.shouldBeInstanceOf<Expression.IsNotNull>()
    expr.expr.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "name"
}
```

- [ ] **Step 6-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -10`
Expected: FAIL (SELECT 파싱 미구현)

- [ ] **Step 6-1c: Pratt parsing 표현식 엔진 구현**

Parser.kt에서 `parseSelect` 스텁을 최소 구현으로 교체하고, 표현식 파싱 메서드를 추가한다.

Parser.kt에 추가/수정하는 내용:

```kotlin
// parseSelect를 다음으로 교체 (최소 구현 — 전체 SELECT는 Task 7에서 완성)
private fun parseSelect(): Statement.Select {
    advance() // SELECT

    val columns = parseSelectList()
    expect(TokenType.FROM, "'FROM' 키워드가 예상되었다")
    val tableName = expectIdentifier("테이블명이 예상되었다")

    val where = if (peek().type == TokenType.WHERE) {
        advance()
        parseExpression(0)
    } else null

    val orderBy = if (peek().type == TokenType.ORDER) {
        advance()
        expect(TokenType.BY, "'BY' 키워드가 예상되었다")
        parseOrderBy()
    } else null

    val limit = if (peek().type == TokenType.LIMIT) {
        advance()
        val token = expect(TokenType.INTEGER_LITERAL, "LIMIT 값이 예상되었다")
        token.literal.toInt()
    } else null

    return Statement.Select(columns, tableName, where, orderBy, limit)
}

private fun parseSelectList(): List<SelectItem> {
    if (peek().type == TokenType.STAR) {
        advance()
        return listOf(SelectItem.Star)
    }
    val items = mutableListOf<SelectItem>()
    items.add(SelectItem.ExprItem(parseExpression(0)))
    while (peek().type == TokenType.COMMA) {
        advance()
        items.add(SelectItem.ExprItem(parseExpression(0)))
    }
    return items
}

private fun parseOrderBy(): OrderByClause {
    val column = expectIdentifier("ORDER BY 컬럼명이 예상되었다")
    val ascending = when (peek().type) {
        TokenType.ASC -> { advance(); true }
        TokenType.DESC -> { advance(); false }
        else -> true
    }
    return OrderByClause(column, ascending)
}

// ========== Pratt Parsing 표현식 엔진 ==========

/**
 * Pratt parsing으로 표현식을 파싱한다.
 *
 * @param minPrecedence 최소 연산자 우선순위
 */
private fun parseExpression(minPrecedence: Int): Expression {
    var left = parsePrefixExpression()

    while (true) {
        // IS NULL / IS NOT NULL 처리
        if (peek().type == TokenType.IS) {
            val isPrecedence = 4
            if (isPrecedence < minPrecedence) break
            advance() // IS
            left = if (peek().type == TokenType.NOT) {
                advance() // NOT
                expect(TokenType.NULL, "'NULL' 키워드가 예상되었다")
                Expression.IsNotNull(left)
            } else {
                expect(TokenType.NULL, "'NULL' 키워드가 예상되었다")
                Expression.IsNull(left)
            }
            continue
        }

        val op = peekBinaryOperator() ?: break
        val precedence = binaryPrecedence(op)
        if (precedence < minPrecedence) break

        advance() // 연산자 토큰 소비
        // 좌결합: 오른쪽은 precedence + 1
        val right = parseExpression(precedence + 1)
        left = Expression.BinaryOp(left, op, right)
    }

    return left
}

private fun parsePrefixExpression(): Expression {
    val token = peek()
    return when (token.type) {
        TokenType.INTEGER_LITERAL -> {
            advance()
            Expression.IntLiteral(token.literal.toLong())
        }
        TokenType.FLOAT_LITERAL -> {
            advance()
            Expression.FloatLiteral(token.literal.toDouble())
        }
        TokenType.STRING_LITERAL -> {
            advance()
            Expression.StringLiteral(token.literal)
        }
        TokenType.TRUE -> {
            advance()
            Expression.BoolLiteral(true)
        }
        TokenType.FALSE -> {
            advance()
            Expression.BoolLiteral(false)
        }
        TokenType.NULL -> {
            advance()
            Expression.NullLiteral
        }
        TokenType.NOT -> {
            advance()
            val operand = parseExpression(3) // NOT 우선순위 = 3
            Expression.UnaryOp(UnaryOperator.NOT, operand)
        }
        TokenType.MINUS -> {
            advance()
            val operand = parseExpression(7) // 단항 부정 우선순위 = 7
            Expression.UnaryOp(UnaryOperator.NEGATE, operand)
        }
        TokenType.LPAREN -> {
            advance() // (
            val expr = parseExpression(0)
            expect(TokenType.RPAREN, "')' 가 예상되었다")
            expr
        }
        TokenType.IDENTIFIER -> {
            advance()
            Expression.ColumnRef(token.literal)
        }
        else -> throw ParseException("표현식이 예상되었다", token.position)
    }
}

private fun peekBinaryOperator(): BinaryOperator? {
    return when (peek().type) {
        TokenType.PLUS -> BinaryOperator.ADD
        TokenType.MINUS -> BinaryOperator.SUB
        TokenType.STAR -> BinaryOperator.MUL
        TokenType.SLASH -> BinaryOperator.DIV
        TokenType.EQ -> BinaryOperator.EQ
        TokenType.NEQ -> BinaryOperator.NEQ
        TokenType.LT -> BinaryOperator.LT
        TokenType.GT -> BinaryOperator.GT
        TokenType.LTE -> BinaryOperator.LTE
        TokenType.GTE -> BinaryOperator.GTE
        TokenType.AND -> BinaryOperator.AND
        TokenType.OR -> BinaryOperator.OR
        else -> null
    }
}

private fun binaryPrecedence(op: BinaryOperator): Int {
    return when (op) {
        BinaryOperator.OR -> 1
        BinaryOperator.AND -> 2
        BinaryOperator.EQ, BinaryOperator.NEQ,
        BinaryOperator.LT, BinaryOperator.GT,
        BinaryOperator.LTE, BinaryOperator.GTE -> 4
        BinaryOperator.ADD, BinaryOperator.SUB -> 5
        BinaryOperator.MUL, BinaryOperator.DIV -> 6
    }
}
```

- [ ] **Step 6-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 6-1e: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Parser.kt core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 3] Pratt parsing 표현식 엔진 및 SELECT 파싱 구현"
```

---

## Task 7: Parser 구현 — INSERT, UPDATE, DELETE

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

### Step 7-1: INSERT 테스트 및 구현

- [ ] **Step 7-1a: INSERT 테스트 작성**

ParserTest에 추가:

```kotlin
// === INSERT ===

@Test
fun `INSERT 기본`() {
    val stmt = parse("INSERT INTO users (id, name) VALUES (1, 'Alice');")
    stmt.shouldBeInstanceOf<Statement.Insert>()
    stmt.tableName shouldBe "users"
    stmt.columns shouldBe listOf("id", "name")
    stmt.values.size shouldBe 2
    stmt.values[0].shouldBeInstanceOf<Expression.IntLiteral>().value shouldBe 1L
    stmt.values[1].shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "Alice"
}

@Test
fun `INSERT NULL 값 포함`() {
    val stmt = parse("INSERT INTO t (a, b) VALUES (NULL, TRUE);")
    stmt.shouldBeInstanceOf<Statement.Insert>()
    stmt.values[0].shouldBeInstanceOf<Expression.NullLiteral>()
    stmt.values[1].shouldBeInstanceOf<Expression.BoolLiteral>().value shouldBe true
}
```

- [ ] **Step 7-1b: INSERT 파싱 구현**

Parser.kt의 `parseInsert` 를 교체:

```kotlin
private fun parseInsert(): Statement.Insert {
    advance() // INSERT
    expect(TokenType.INTO, "'INTO' 키워드가 예상되었다")
    val tableName = expectIdentifier("테이블명이 예상되었다")

    expect(TokenType.LPAREN, "'(' 가 예상되었다 (컬럼 목록)")
    val columns = mutableListOf<String>()
    columns.add(expectIdentifier("컬럼명이 예상되었다"))
    while (peek().type == TokenType.COMMA) {
        advance()
        columns.add(expectIdentifier("컬럼명이 예상되었다"))
    }
    expect(TokenType.RPAREN, "')' 가 예상되었다")

    expect(TokenType.VALUES, "'VALUES' 키워드가 예상되었다")
    expect(TokenType.LPAREN, "'(' 가 예상되었다 (값 목록)")
    val values = mutableListOf<Expression>()
    values.add(parseExpression(0))
    while (peek().type == TokenType.COMMA) {
        advance()
        values.add(parseExpression(0))
    }
    expect(TokenType.RPAREN, "')' 가 예상되었다")

    return Statement.Insert(tableName, columns, values)
}
```

- [ ] **Step 7-1c: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

### Step 7-2: UPDATE 테스트 및 구현

- [ ] **Step 7-2a: UPDATE 테스트 작성**

```kotlin
// === UPDATE ===

@Test
fun `UPDATE 단일 SET`() {
    val stmt = parse("UPDATE users SET name = 'Bob' WHERE id = 1;")
    stmt.shouldBeInstanceOf<Statement.Update>()
    stmt.tableName shouldBe "users"
    stmt.assignments.size shouldBe 1
    stmt.assignments[0].column shouldBe "name"
    stmt.assignments[0].value.shouldBeInstanceOf<Expression.StringLiteral>().value shouldBe "Bob"
    stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
}

@Test
fun `UPDATE 다중 SET WHERE 없음`() {
    val stmt = parse("UPDATE t SET a = 1, b = 2;")
    stmt.shouldBeInstanceOf<Statement.Update>()
    stmt.assignments.size shouldBe 2
    stmt.where shouldBe null
}
```

- [ ] **Step 7-2b: UPDATE 파싱 구현**

```kotlin
private fun parseUpdate(): Statement.Update {
    advance() // UPDATE
    val tableName = expectIdentifier("테이블명이 예상되었다")
    expect(TokenType.SET, "'SET' 키워드가 예상되었다")

    val assignments = mutableListOf<Assignment>()
    assignments.add(parseAssignment())
    while (peek().type == TokenType.COMMA) {
        advance()
        assignments.add(parseAssignment())
    }

    val where = if (peek().type == TokenType.WHERE) {
        advance()
        parseExpression(0)
    } else null

    return Statement.Update(tableName, assignments, where)
}

private fun parseAssignment(): Assignment {
    val column = expectIdentifier("컬럼명이 예상되었다")
    expect(TokenType.EQ, "'=' 가 예상되었다")
    val value = parseExpression(0)
    return Assignment(column, value)
}
```

- [ ] **Step 7-2c: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

### Step 7-3: DELETE 테스트 및 구현

- [ ] **Step 7-3a: DELETE 테스트 작성**

```kotlin
// === DELETE ===

@Test
fun `DELETE WHERE 있음`() {
    val stmt = parse("DELETE FROM users WHERE id = 1;")
    stmt.shouldBeInstanceOf<Statement.Delete>()
    stmt.tableName shouldBe "users"
    stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
}

@Test
fun `DELETE WHERE 없음 - 전체 삭제`() {
    val stmt = parse("DELETE FROM users;")
    stmt.shouldBeInstanceOf<Statement.Delete>()
    stmt.where shouldBe null
}
```

- [ ] **Step 7-3b: DELETE 파싱 구현**

```kotlin
private fun parseDelete(): Statement.Delete {
    advance() // DELETE
    expect(TokenType.FROM, "'FROM' 키워드가 예상되었다")
    val tableName = expectIdentifier("테이블명이 예상되었다")

    val where = if (peek().type == TokenType.WHERE) {
        advance()
        parseExpression(0)
    } else null

    return Statement.Delete(tableName, where)
}
```

- [ ] **Step 7-3c: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

### Step 7-4: SELECT 추가 테스트

- [ ] **Step 7-4a: SELECT 다양한 케이스 테스트 추가**

```kotlin
// === SELECT ===

@Test
fun `SELECT *`() {
    val stmt = parse("SELECT * FROM users;")
    stmt.shouldBeInstanceOf<Statement.Select>()
    stmt.columns shouldBe listOf(SelectItem.Star)
    stmt.tableName shouldBe "users"
    stmt.where shouldBe null
    stmt.orderBy shouldBe null
    stmt.limit shouldBe null
}

@Test
fun `SELECT 컬럼 목록`() {
    val stmt = parse("SELECT id, name FROM users;")
    stmt.shouldBeInstanceOf<Statement.Select>()
    stmt.columns.size shouldBe 2
    (stmt.columns[0] as SelectItem.ExprItem).expr.shouldBeInstanceOf<Expression.ColumnRef>().name shouldBe "id"
}

@Test
fun `SELECT WHERE ORDER BY LIMIT 조합`() {
    val stmt = parse("SELECT * FROM users WHERE age > 18 ORDER BY name DESC LIMIT 10;")
    stmt.shouldBeInstanceOf<Statement.Select>()
    stmt.where.shouldBeInstanceOf<Expression.BinaryOp>().op shouldBe BinaryOperator.GT
    stmt.orderBy shouldBe OrderByClause("name", ascending = false)
    stmt.limit shouldBe 10
}

@Test
fun `복합 WHERE 조건`() {
    val stmt = parse("SELECT * FROM t WHERE a > 1 AND b = 'x' OR c IS NULL;")
    stmt.shouldBeInstanceOf<Statement.Select>()
    // OR가 최상위: (a > 1 AND b = 'x') OR (c IS NULL)
    val where = stmt.where.shouldBeInstanceOf<Expression.BinaryOp>()
    where.op shouldBe BinaryOperator.OR
    where.left.shouldBeInstanceOf<Expression.BinaryOp>().op shouldBe BinaryOperator.AND
    where.right.shouldBeInstanceOf<Expression.IsNull>()
}
```

- [ ] **Step 7-4b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

### Step 7-5: 파서 에러 테스트

- [ ] **Step 7-5a: 에러 케이스 테스트 추가**

```kotlin
// === 에러 케이스 ===

@Test
fun `불완전한 SQL 문은 에러`() {
    assertThrows<ParseException> { parse("SELECT") }
    assertThrows<ParseException> { parse("CREATE") }
    assertThrows<ParseException> { parse("INSERT INTO") }
}

@Test
fun `잘못된 토큰 위치는 에러`() {
    assertThrows<ParseException> { parse("SELECT FROM users;") }
}

@Test
fun `SQL 문 뒤에 추가 토큰이 있으면 에러`() {
    assertThrows<ParseException> { parse("SELECT * FROM t; SELECT * FROM t;") }
}
```

- [ ] **Step 7-5b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 7-5c: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Parser.kt core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 3] INSERT, UPDATE, DELETE 파싱 및 SELECT 추가 테스트"
```

---

## Task 8: Database API 확장

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/table/Database.kt:108-173`
- Modify: `core/src/test/kotlin/gwanbase/table/DatabaseTest.kt`

### Step 8-1: dropTable, updateTuple, getCatalog 테스트

- [ ] **Step 8-1a: 테스트 작성**

DatabaseTest.kt에 추가:

```kotlin
@Test
fun `dropTable 후 테이블 조회 시 null 반환`() {
    Database.open(dbPath()).use { db ->
        db.createTable("users", userSchema)
        db.dropTable("users") shouldBe true
        db.getTable("users") shouldBe null
    }
}

@Test
fun `존재하지 않는 테이블 dropTable 시 false 반환`() {
    Database.open(dbPath()).use { db ->
        db.dropTable("nonexistent") shouldBe false
    }
}

@Test
fun `updateTuple 후 조회 시 변경된 값 반환`() {
    Database.open(dbPath()).use { db ->
        db.createTable("users", userSchema)
        val original = Tuple(userSchema, arrayOf(1, "Alice", true))
        val rid = db.insertTuple("users", original)

        val updated = Tuple(userSchema, arrayOf(1, "Bob", false))
        val newRid = db.updateTuple("users", rid, updated)

        val result = db.getTuple("users", newRid)
        result.shouldNotBeNull()
        result.getString(1) shouldBe "Bob"
        result.getBoolean(2) shouldBe false
    }
}

@Test
fun `getCatalog은 Catalog 인스턴스를 반환한다`() {
    Database.open(dbPath()).use { db ->
        db.createTable("users", userSchema)
        val catalog = db.getCatalog()
        catalog.getTable("users").shouldNotBeNull()
    }
}
```

주의: `userSchema`가 기존 테스트에 이미 정의되어 있다. 없다면 다음을 사용:
```kotlin
private val userSchema = Schema(listOf(
    Column("id", DataType.INT32),
    Column("name", DataType.VARCHAR, maxLength = 100),
    Column("active", DataType.BOOLEAN),
))
```

- [ ] **Step 8-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.DatabaseTest" 2>&1 | tail -10`
Expected: 컴파일 에러 (dropTable, updateTuple, getCatalog 없음)

- [ ] **Step 8-1c: Database에 메서드 추가**

Database.kt의 `checkOpen()` 바로 위에 추가:

```kotlin
/** 테이블을 삭제한다. 존재하지 않으면 false를 반환한다. */
fun dropTable(name: String): Boolean {
    checkOpen()
    return catalog.dropTable(name)
}

/** 튜플을 업데이트한다. 내부적으로 삭제 후 재삽입한다. */
fun updateTuple(tableName: String, rid: RID, tuple: Tuple): RID {
    checkOpen()
    val info = catalog.getTable(tableName)
        ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
    val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
    return heapFile.updateTuple(rid, tuple.serialize())
}

/** Catalog 인스턴스를 반환한다. Binder에서 스키마 검증용으로 사용한다. */
fun getCatalog(): Catalog {
    checkOpen()
    return catalog
}
```

- [ ] **Step 8-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.DatabaseTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 8-1e: 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -5`
Expected: PASS (기존 테스트 깨지지 않음)

- [ ] **Step 8-1f: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/table/Database.kt core/src/test/kotlin/gwanbase/table/DatabaseTest.kt
git commit -m "[Phase 3] Database에 dropTable, updateTuple, getCatalog 추가"
```

---

## Task 9: Binder 구현

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/Binder.kt`
- Create: `core/src/test/kotlin/gwanbase/sql/BinderTest.kt`

### Step 9-1: Binder 기본 테스트

- [ ] **Step 9-1a: 테스트 작성**

```kotlin
package gwanbase.sql

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BinderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Database
    private lateinit var binder: Binder

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        database.createTable("users", Schema(listOf(
            Column("id", DataType.INT32, nullable = false),
            Column("name", DataType.VARCHAR, maxLength = 100),
            Column("age", DataType.INT32),
        )))
        binder = Binder(database.getCatalog())
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        database.close()
    }

    private fun parse(sql: String): Statement {
        val tokens = Lexer(sql).tokenize()
        return Parser(tokens).parse()
    }

    // === 정상 케이스 ===

    @Test
    fun `정상 SELECT는 바인딩을 통과한다`() {
        val stmt = parse("SELECT id, name FROM users WHERE age > 18;")
        binder.bind(stmt) // 예외 없이 통과
    }

    @Test
    fun `정상 INSERT는 바인딩을 통과한다`() {
        val stmt = parse("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 25);")
        binder.bind(stmt)
    }

    // === 테이블 검증 ===

    @Test
    fun `존재하지 않는 테이블 SELECT 시 에러`() {
        val stmt = parse("SELECT * FROM nonexistent;")
        val ex = assertThrows<BindException> { binder.bind(stmt) }
        ex.message shouldContain "nonexistent"
    }

    @Test
    fun `존재하지 않는 테이블 INSERT 시 에러`() {
        val stmt = parse("INSERT INTO nonexistent (a) VALUES (1);")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `존재하지 않는 테이블 UPDATE 시 에러`() {
        val stmt = parse("UPDATE nonexistent SET a = 1;")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `존재하지 않는 테이블 DELETE 시 에러`() {
        val stmt = parse("DELETE FROM nonexistent;")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    // === 컬럼 검증 ===

    @Test
    fun `존재하지 않는 컬럼 SELECT 시 에러`() {
        val stmt = parse("SELECT unknown_col FROM users;")
        val ex = assertThrows<BindException> { binder.bind(stmt) }
        ex.message shouldContain "unknown_col"
    }

    @Test
    fun `존재하지 않는 컬럼 WHERE 시 에러`() {
        val stmt = parse("SELECT * FROM users WHERE unknown = 1;")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `INSERT 컬럼명이 스키마에 없으면 에러`() {
        val stmt = parse("INSERT INTO users (id, unknown) VALUES (1, 'x');")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `ORDER BY 컬럼이 스키마에 없으면 에러`() {
        val stmt = parse("SELECT * FROM users ORDER BY unknown;")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    @Test
    fun `UPDATE SET 컬럼이 스키마에 없으면 에러`() {
        val stmt = parse("UPDATE users SET unknown = 1;")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    // === NOT NULL 검증 ===

    @Test
    fun `NOT NULL 컬럼에 NULL 삽입 시 에러`() {
        val stmt = parse("INSERT INTO users (id, name, age) VALUES (NULL, 'Alice', 25);")
        val ex = assertThrows<BindException> { binder.bind(stmt) }
        ex.message shouldContain "id"
    }

    // === CREATE TABLE 검증 ===

    @Test
    fun `중복 테이블명 CREATE 시 에러`() {
        val stmt = parse("CREATE TABLE users (a INT);")
        assertThrows<BindException> { binder.bind(stmt) }
    }

    // === DROP TABLE 검증 ===

    @Test
    fun `존재하지 않는 테이블 DROP 시 에러`() {
        val stmt = parse("DROP TABLE nonexistent;")
        assertThrows<BindException> { binder.bind(stmt) }
    }
}
```

- [ ] **Step 9-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.BinderTest" 2>&1 | tail -5`
Expected: 컴파일 에러 (Binder 클래스 없음)

- [ ] **Step 9-1c: Binder 구현**

```kotlin
package gwanbase.sql

import gwanbase.table.Catalog
import gwanbase.table.Schema

/**
 * AST를 Catalog과 대조하여 검증하는 바인더.
 *
 * 테이블/컬럼 존재 여부, 타입 호환성, NOT NULL 제약 등을 검사한다.
 * 검증만 수행하며 AST를 변환하지 않는다.
 */
class Binder(private val catalog: Catalog) {

    /**
     * Statement를 검증한다. 오류가 있으면 [BindException]을 던진다.
     *
     * @return 검증된 Statement (변환 없이 그대로 반환)
     */
    fun bind(statement: Statement): Statement {
        when (statement) {
            is Statement.CreateTable -> bindCreateTable(statement)
            is Statement.DropTable -> bindDropTable(statement)
            is Statement.Insert -> bindInsert(statement)
            is Statement.Select -> bindSelect(statement)
            is Statement.Update -> bindUpdate(statement)
            is Statement.Delete -> bindDelete(statement)
        }
        return statement
    }

    private fun bindCreateTable(stmt: Statement.CreateTable) {
        if (catalog.getTable(stmt.tableName) != null) {
            throw BindException("테이블 '${stmt.tableName}'이 이미 존재한다")
        }
    }

    private fun bindDropTable(stmt: Statement.DropTable) {
        requireTable(stmt.tableName)
    }

    private fun bindInsert(stmt: Statement.Insert) {
        val schema = requireTable(stmt.tableName)

        // 컬럼 존재 확인
        for (col in stmt.columns) {
            requireColumn(schema, col)
        }

        // NOT NULL 검증: 값이 NULL 리터럴인데 해당 컬럼이 NOT NULL이면 에러
        for ((i, colName) in stmt.columns.withIndex()) {
            if (i < stmt.values.size && stmt.values[i] is Expression.NullLiteral) {
                val colIdx = schema.columnIndex(colName)
                val column = schema.column(colIdx)
                if (!column.nullable) {
                    throw BindException("NOT NULL 컬럼 '$colName'에 NULL을 삽입할 수 없다")
                }
            }
        }
    }

    private fun bindSelect(stmt: Statement.Select) {
        val schema = requireTable(stmt.tableName)

        // SELECT 컬럼 검증
        for (item in stmt.columns) {
            if (item is SelectItem.ExprItem) {
                validateExpression(schema, item.expr)
            }
        }

        // WHERE 검증
        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }

        // ORDER BY 검증
        if (stmt.orderBy != null) {
            requireColumn(schema, stmt.orderBy.column)
        }
    }

    private fun bindUpdate(stmt: Statement.Update) {
        val schema = requireTable(stmt.tableName)

        for (assignment in stmt.assignments) {
            requireColumn(schema, assignment.column)
            validateExpression(schema, assignment.value)
        }

        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }
    }

    private fun bindDelete(stmt: Statement.Delete) {
        val schema = requireTable(stmt.tableName)

        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }
    }

    /**
     * 테이블이 존재하는지 확인하고 스키마를 반환한다.
     */
    private fun requireTable(name: String): Schema {
        val info = catalog.getTable(name)
            ?: throw BindException("테이블 '$name'이 존재하지 않는다")
        return info.schema
    }

    /**
     * 컬럼이 스키마에 존재하는지 확인한다.
     */
    private fun requireColumn(schema: Schema, columnName: String) {
        try {
            schema.columnIndex(columnName)
        } catch (_: IllegalArgumentException) {
            throw BindException("컬럼 '$columnName'이 존재하지 않는다")
        }
    }

    /**
     * 표현식 내의 컬럼 참조를 재귀적으로 검증한다.
     */
    private fun validateExpression(schema: Schema, expr: Expression) {
        when (expr) {
            is Expression.ColumnRef -> requireColumn(schema, expr.name)
            is Expression.BinaryOp -> {
                validateExpression(schema, expr.left)
                validateExpression(schema, expr.right)
            }
            is Expression.UnaryOp -> validateExpression(schema, expr.operand)
            is Expression.IsNull -> validateExpression(schema, expr.expr)
            is Expression.IsNotNull -> validateExpression(schema, expr.expr)
            // 리터럴은 검증 불필요
            is Expression.IntLiteral,
            is Expression.FloatLiteral,
            is Expression.StringLiteral,
            is Expression.BoolLiteral,
            is Expression.NullLiteral -> { }
        }
    }
}
```

- [ ] **Step 9-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.BinderTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 9-1e: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Binder.kt core/src/test/kotlin/gwanbase/sql/BinderTest.kt
git commit -m "[Phase 3] Binder 구현 및 테스트"
```

---

## Task 10: Executor 구현 — DDL + INSERT

**Files:**
- Create: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt`
- Create: `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt`

### Step 10-1: ExecuteResult 및 DDL/INSERT 테스트

- [ ] **Step 10-1a: 테스트 작성**

```kotlin
package gwanbase.sql

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqlExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Database
    private lateinit var executor: SqlExecutor

    @BeforeEach
    fun setUp() {
        database = Database.open(tempDir.resolve("test.db"))
        executor = SqlExecutor(database)
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    // === DDL ===

    @Test
    fun `CREATE TABLE 후 테이블이 생성된다`() {
        val result = executor.execute("CREATE TABLE users (id INT NOT NULL, name VARCHAR(100));")
        result.shouldBeInstanceOf<ExecuteResult.Created>()
        result.tableName shouldBe "users"
        database.getTable("users")!!.schema.columnCount shouldBe 2
    }

    @Test
    fun `DROP TABLE 후 테이블이 삭제된다`() {
        executor.execute("CREATE TABLE t (a INT);")
        val result = executor.execute("DROP TABLE t;")
        result.shouldBeInstanceOf<ExecuteResult.Dropped>()
        result.tableName shouldBe "t"
        database.getTable("t") shouldBe null
    }

    // === INSERT ===

    @Test
    fun `INSERT 후 행이 삽입된다`() {
        executor.execute("CREATE TABLE users (id INT NOT NULL, name VARCHAR(100));")
        val result = executor.execute("INSERT INTO users (id, name) VALUES (1, 'Alice');")
        result.shouldBeInstanceOf<ExecuteResult.Inserted>()
    }

    @Test
    fun `INSERT NULL 값`() {
        executor.execute("CREATE TABLE t (a INT, b VARCHAR(50));")
        executor.execute("INSERT INTO t (a, b) VALUES (NULL, 'hello');")

        val result = executor.execute("SELECT * FROM t;")
        result.shouldBeInstanceOf<ExecuteResult.Selected>()
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe null
        result.rows[0][1] shouldBe "hello"
    }
}
```

- [ ] **Step 10-1b: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: 컴파일 에러

- [ ] **Step 10-1c: SqlExecutor 구현 — DDL + INSERT + 기본 SELECT**

```kotlin
package gwanbase.sql

import gwanbase.table.*

/**
 * SQL 실행 결과.
 */
sealed class ExecuteResult {
    data class Created(val tableName: String) : ExecuteResult()
    data class Dropped(val tableName: String) : ExecuteResult()
    data class Inserted(val rid: RID) : ExecuteResult()
    data class Selected(val columns: List<String>, val rows: List<List<Any?>>) : ExecuteResult()
    data class Updated(val count: Int) : ExecuteResult()
    data class Deleted(val count: Int) : ExecuteResult()
}

/**
 * SQL 텍스트를 받아 파싱, 검증, 실행하는 진입점.
 *
 * Phase 4 이전의 임시 인터프리터 방식 실행기이다.
 * Lexer → Parser → Binder → 직접 실행 파이프라인을 구성한다.
 */
class SqlExecutor(private val database: Database) {

    /**
     * SQL 문을 실행하고 결과를 반환한다.
     */
    fun execute(sql: String): ExecuteResult {
        val tokens = Lexer(sql).tokenize()
        val stmt = Parser(tokens).parse()
        val binder = Binder(database.getCatalog())
        binder.bind(stmt)
        return executeStatement(stmt)
    }

    private fun executeStatement(stmt: Statement): ExecuteResult {
        return when (stmt) {
            is Statement.CreateTable -> executeCreateTable(stmt)
            is Statement.DropTable -> executeDropTable(stmt)
            is Statement.Insert -> executeInsert(stmt)
            is Statement.Select -> executeSelect(stmt)
            is Statement.Update -> executeUpdate(stmt)
            is Statement.Delete -> executeDelete(stmt)
        }
    }

    // ========== DDL ==========

    private fun executeCreateTable(stmt: Statement.CreateTable): ExecuteResult.Created {
        val columns = stmt.columns.map { colDef ->
            Column(
                name = colDef.name,
                type = toDataType(colDef.dataType),
                maxLength = when (colDef.dataType) {
                    is SqlDataType.VarcharType -> colDef.dataType.maxLength
                    else -> 0
                },
                nullable = colDef.nullable,
            )
        }
        val schema = Schema(columns)
        database.createTable(stmt.tableName, schema)
        return ExecuteResult.Created(stmt.tableName)
    }

    private fun executeDropTable(stmt: Statement.DropTable): ExecuteResult.Dropped {
        database.dropTable(stmt.tableName)
        return ExecuteResult.Dropped(stmt.tableName)
    }

    // ========== INSERT ==========

    private fun executeInsert(stmt: Statement.Insert): ExecuteResult.Inserted {
        val info = database.getTable(stmt.tableName)!!
        val schema = info.schema

        // 컬럼 순서에 맞게 값 배열 구성
        val values = arrayOfNulls<Any?>(schema.columnCount)
        for ((i, colName) in stmt.columns.withIndex()) {
            val colIdx = schema.columnIndex(colName)
            values[colIdx] = evaluateLiteral(stmt.values[i])
        }

        val tuple = Tuple(schema, values)
        val rid = database.insertTuple(stmt.tableName, tuple)
        return ExecuteResult.Inserted(rid)
    }

    // ========== SELECT ==========

    private fun executeSelect(stmt: Statement.Select): ExecuteResult.Selected {
        val info = database.getTable(stmt.tableName)!!
        val schema = info.schema

        // 1. 전체 스캔
        val allRows = mutableListOf<Pair<RID, Tuple>>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            allRows.add(iter.next())
        }

        // 2. WHERE 필터링
        val filtered = if (stmt.where != null) {
            allRows.filter { (_, tuple) -> evaluateCondition(schema, tuple, stmt.where) }
        } else {
            allRows
        }

        // 3. ORDER BY 정렬
        val sorted = if (stmt.orderBy != null) {
            val colIdx = schema.columnIndex(stmt.orderBy.column)
            val comparator = compareBy<Pair<RID, Tuple>, Comparable<Any>?>(nullsLast()) { (_, tuple) ->
                @Suppress("UNCHECKED_CAST")
                getTupleValue(tuple, colIdx) as? Comparable<Any>
            }
            if (stmt.orderBy.ascending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())
        } else {
            filtered
        }

        // 4. LIMIT
        val limited = if (stmt.limit != null) sorted.take(stmt.limit) else sorted

        // 5. 프로젝션
        val columnNames: List<String>
        val rows: List<List<Any?>>

        if (stmt.columns.any { it is SelectItem.Star }) {
            columnNames = (0 until schema.columnCount).map { schema.column(it).name }
            rows = limited.map { (_, tuple) ->
                (0 until schema.columnCount).map { getTupleValue(tuple, it) }
            }
        } else {
            columnNames = stmt.columns.map { item ->
                when (item) {
                    is SelectItem.ExprItem -> {
                        when (val expr = item.expr) {
                            is Expression.ColumnRef -> expr.name
                            else -> "expr"
                        }
                    }
                    is SelectItem.Star -> "*"
                }
            }
            rows = limited.map { (_, tuple) ->
                stmt.columns.map { item ->
                    when (item) {
                        is SelectItem.ExprItem -> evaluateExpression(schema, tuple, item.expr)
                        is SelectItem.Star -> null
                    }
                }
            }
        }

        return ExecuteResult.Selected(columnNames, rows)
    }

    // ========== UPDATE ==========

    private fun executeUpdate(stmt: Statement.Update): ExecuteResult.Updated {
        val info = database.getTable(stmt.tableName)!!
        val schema = info.schema
        var count = 0

        val targets = mutableListOf<Pair<RID, Tuple>>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || evaluateCondition(schema, tuple, stmt.where)) {
                targets.add(rid to tuple)
            }
        }

        for ((rid, tuple) in targets) {
            val values = (0 until schema.columnCount).map { getTupleValue(tuple, it) }.toTypedArray()
            for (assignment in stmt.assignments) {
                val colIdx = schema.columnIndex(assignment.column)
                values[colIdx] = evaluateExpression(schema, tuple, assignment.value)
            }
            val newTuple = Tuple(schema, values)
            database.updateTuple(stmt.tableName, rid, newTuple)
            count++
        }

        return ExecuteResult.Updated(count)
    }

    // ========== DELETE ==========

    private fun executeDelete(stmt: Statement.Delete): ExecuteResult.Deleted {
        val info = database.getTable(stmt.tableName)!!
        val schema = info.schema
        var count = 0

        val targets = mutableListOf<RID>()
        val iter = database.scanTable(stmt.tableName)
        while (iter.hasNext()) {
            val (rid, tuple) = iter.next()
            if (stmt.where == null || evaluateCondition(schema, tuple, stmt.where)) {
                targets.add(rid)
            }
        }

        for (rid in targets) {
            database.deleteTuple(stmt.tableName, rid)
            count++
        }

        return ExecuteResult.Deleted(count)
    }

    // ========== 표현식 평가 ==========

    /**
     * 리터럴 표현식을 Kotlin 값으로 평가한다.
     */
    private fun evaluateLiteral(expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.NullLiteral -> null
            else -> throw IllegalArgumentException("INSERT VALUES에는 리터럴만 지원한다")
        }
    }

    /**
     * 표현식을 Tuple 컨텍스트에서 평가한다.
     */
    private fun evaluateExpression(schema: Schema, tuple: Tuple, expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.NullLiteral -> null
            is Expression.ColumnRef -> {
                val idx = schema.columnIndex(expr.name)
                getTupleValue(tuple, idx)
            }
            is Expression.BinaryOp -> evaluateBinaryOp(schema, tuple, expr)
            is Expression.UnaryOp -> evaluateUnaryOp(schema, tuple, expr)
            is Expression.IsNull -> {
                val value = evaluateExpression(schema, tuple, expr.expr)
                value == null
            }
            is Expression.IsNotNull -> {
                val value = evaluateExpression(schema, tuple, expr.expr)
                value != null
            }
        }
    }

    private fun evaluateBinaryOp(schema: Schema, tuple: Tuple, expr: Expression.BinaryOp): Any? {
        val left = evaluateExpression(schema, tuple, expr.left)
        val right = evaluateExpression(schema, tuple, expr.right)

        // 논리 연산자
        if (expr.op == BinaryOperator.AND) return (left as? Boolean ?: false) && (right as? Boolean ?: false)
        if (expr.op == BinaryOperator.OR) return (left as? Boolean ?: false) || (right as? Boolean ?: false)

        // NULL 비교: NULL과의 비교는 항상 NULL (SQL 3값 논리)
        if (left == null || right == null) {
            return when (expr.op) {
                BinaryOperator.EQ, BinaryOperator.NEQ,
                BinaryOperator.LT, BinaryOperator.GT,
                BinaryOperator.LTE, BinaryOperator.GTE -> null
                BinaryOperator.ADD, BinaryOperator.SUB,
                BinaryOperator.MUL, BinaryOperator.DIV -> null
                else -> null
            }
        }

        // 산술 연산자
        return when (expr.op) {
            BinaryOperator.ADD -> numericOp(left, right) { a, b -> a + b }
            BinaryOperator.SUB -> numericOp(left, right) { a, b -> a - b }
            BinaryOperator.MUL -> numericOp(left, right) { a, b -> a * b }
            BinaryOperator.DIV -> numericOp(left, right) { a, b -> a / b }
            BinaryOperator.EQ -> compareValues(left, right) == 0
            BinaryOperator.NEQ -> compareValues(left, right) != 0
            BinaryOperator.LT -> compareValues(left, right) < 0
            BinaryOperator.GT -> compareValues(left, right) > 0
            BinaryOperator.LTE -> compareValues(left, right) <= 0
            BinaryOperator.GTE -> compareValues(left, right) >= 0
            else -> null
        }
    }

    private fun evaluateUnaryOp(schema: Schema, tuple: Tuple, expr: Expression.UnaryOp): Any? {
        val value = evaluateExpression(schema, tuple, expr.operand)
        return when (expr.op) {
            UnaryOperator.NOT -> !(value as? Boolean ?: return null)
            UnaryOperator.NEGATE -> {
                when (value) {
                    is Long -> -value
                    is Double -> -value
                    is Int -> -value
                    else -> null
                }
            }
        }
    }

    /**
     * WHERE 조건을 평가하여 boolean으로 반환한다.
     * SQL 3값 논리에서 NULL은 false로 취급한다.
     */
    private fun evaluateCondition(schema: Schema, tuple: Tuple, expr: Expression): Boolean {
        val result = evaluateExpression(schema, tuple, expr)
        return result == true
    }

    // ========== 유틸리티 ==========

    private fun toDataType(sqlType: SqlDataType): DataType {
        return when (sqlType) {
            is SqlDataType.BooleanType -> DataType.BOOLEAN
            is SqlDataType.IntType -> DataType.INT32
            is SqlDataType.BigIntType -> DataType.INT64
            is SqlDataType.DoubleType -> DataType.FLOAT64
            is SqlDataType.TimestampType -> DataType.TIMESTAMP
            is SqlDataType.VarcharType -> DataType.VARCHAR
        }
    }

    /**
     * Tuple에서 인덱스로 값을 꺼낸다. 타입에 맞는 getter를 호출한다.
     */
    private fun getTupleValue(tuple: Tuple, index: Int): Any? {
        if (tuple.isNull(index)) return null
        return when (tuple.schema.column(index).type) {
            DataType.BOOLEAN -> tuple.getBoolean(index)
            DataType.INT32 -> tuple.getInt(index)
            DataType.INT64 -> tuple.getLong(index)
            DataType.FLOAT64 -> tuple.getDouble(index)
            DataType.TIMESTAMP -> tuple.getTimestamp(index)
            DataType.VARCHAR -> tuple.getString(index)
        }
    }

    private fun numericOp(left: Any, right: Any, op: (Double, Double) -> Double): Any {
        val l = toDouble(left)
        val r = toDouble(right)
        val result = op(l, r)
        // 양쪽 모두 정수 타입이면 Long으로 반환
        if (left is Long && right is Long) return result.toLong()
        if (left is Int && right is Int) return result.toLong()
        return result
    }

    private fun toDouble(value: Any): Double {
        return when (value) {
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Double -> value
            else -> throw IllegalArgumentException("숫자로 변환할 수 없는 타입: ${value::class}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareValues(left: Any, right: Any): Int {
        // 타입 승격: 한쪽이 Double이면 다른 쪽도 Double로 비교
        if (left is Number && right is Number) {
            return left.toDouble().compareTo(right.toDouble())
        }
        if (left is String && right is String) {
            return left.compareTo(right)
        }
        if (left is Boolean && right is Boolean) {
            return left.compareTo(right)
        }
        return (left as Comparable<Any>).compareTo(right)
    }
}
```

- [ ] **Step 10-1d: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 10-1e: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt
git commit -m "[Phase 3] SqlExecutor DDL, INSERT, 기본 SELECT 구현"
```

---

## Task 11: Executor 통합 테스트 — SELECT, UPDATE, DELETE

**Files:**
- Modify: `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt`

### Step 11-1: SELECT 필터링·정렬·LIMIT 테스트

- [ ] **Step 11-1a: SELECT 통합 테스트 추가**

SqlExecutorTest에 추가:

```kotlin
// === SELECT 고급 ===

@Test
fun `SELECT WHERE 비교 필터링`() {
    executor.execute("CREATE TABLE t (id INT NOT NULL, score INT);")
    executor.execute("INSERT INTO t (id, score) VALUES (1, 90);")
    executor.execute("INSERT INTO t (id, score) VALUES (2, 60);")
    executor.execute("INSERT INTO t (id, score) VALUES (3, 80);")

    val result = executor.execute("SELECT id, score FROM t WHERE score >= 80;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.size shouldBe 2
}

@Test
fun `SELECT WHERE AND OR 복합 조건`() {
    executor.execute("CREATE TABLE t (a INT, b BOOLEAN);")
    executor.execute("INSERT INTO t (a, b) VALUES (1, TRUE);")
    executor.execute("INSERT INTO t (a, b) VALUES (2, FALSE);")
    executor.execute("INSERT INTO t (a, b) VALUES (3, TRUE);")

    val result = executor.execute("SELECT * FROM t WHERE a > 1 AND b = TRUE;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.size shouldBe 1
    result.rows[0][0] shouldBe 3
}

@Test
fun `SELECT WHERE IS NULL`() {
    executor.execute("CREATE TABLE t (a INT, b VARCHAR(50));")
    executor.execute("INSERT INTO t (a, b) VALUES (1, 'hello');")
    executor.execute("INSERT INTO t (a, b) VALUES (NULL, 'world');")

    val result = executor.execute("SELECT * FROM t WHERE a IS NULL;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.size shouldBe 1
    result.rows[0][1] shouldBe "world"
}

@Test
fun `SELECT ORDER BY ASC`() {
    executor.execute("CREATE TABLE t (name VARCHAR(50) NOT NULL);")
    executor.execute("INSERT INTO t (name) VALUES ('Charlie');")
    executor.execute("INSERT INTO t (name) VALUES ('Alice');")
    executor.execute("INSERT INTO t (name) VALUES ('Bob');")

    val result = executor.execute("SELECT name FROM t ORDER BY name ASC;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.map { it[0] } shouldBe listOf("Alice", "Bob", "Charlie")
}

@Test
fun `SELECT ORDER BY DESC`() {
    executor.execute("CREATE TABLE t (val INT NOT NULL);")
    executor.execute("INSERT INTO t (val) VALUES (3);")
    executor.execute("INSERT INTO t (val) VALUES (1);")
    executor.execute("INSERT INTO t (val) VALUES (2);")

    val result = executor.execute("SELECT val FROM t ORDER BY val DESC;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.map { it[0] } shouldBe listOf(3, 1, 2).map { it } // 3, 2, 1
}

@Test
fun `SELECT LIMIT`() {
    executor.execute("CREATE TABLE t (id INT NOT NULL);")
    for (i in 1..10) {
        executor.execute("INSERT INTO t (id) VALUES ($i);")
    }

    val result = executor.execute("SELECT * FROM t LIMIT 3;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.size shouldBe 3
}

@Test
fun `빈 테이블 SELECT 시 빈 결과`() {
    executor.execute("CREATE TABLE t (a INT);")
    val result = executor.execute("SELECT * FROM t;")
    result.shouldBeInstanceOf<ExecuteResult.Selected>()
    result.rows.size shouldBe 0
}
```

주의: `SELECT ORDER BY DESC` 테스트의 기대값은 실제 정렬 결과에 맞게 조정한다 (`listOf(3, 2, 1)`). 위 코드에서 주석으로 표시했으니 구현 시 확인할 것.

- [ ] **Step 11-1b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: PASS

### Step 11-2: UPDATE, DELETE 통합 테스트

- [ ] **Step 11-2a: UPDATE, DELETE 테스트 추가**

```kotlin
// === UPDATE ===

@Test
fun `UPDATE WHERE 조건으로 부분 업데이트`() {
    executor.execute("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50));")
    executor.execute("INSERT INTO t (id, name) VALUES (1, 'Alice');")
    executor.execute("INSERT INTO t (id, name) VALUES (2, 'Bob');")

    val result = executor.execute("UPDATE t SET name = 'Updated' WHERE id = 1;")
    result.shouldBeInstanceOf<ExecuteResult.Updated>()
    result.count shouldBe 1

    val select = executor.execute("SELECT * FROM t WHERE id = 1;") as ExecuteResult.Selected
    select.rows[0][1] shouldBe "Updated"
}

@Test
fun `UPDATE WHERE 없이 전체 업데이트`() {
    executor.execute("CREATE TABLE t (a INT);")
    executor.execute("INSERT INTO t (a) VALUES (1);")
    executor.execute("INSERT INTO t (a) VALUES (2);")

    val result = executor.execute("UPDATE t SET a = 0;")
    result.shouldBeInstanceOf<ExecuteResult.Updated>()
    result.count shouldBe 2
}

// === DELETE ===

@Test
fun `DELETE WHERE 조건으로 부분 삭제`() {
    executor.execute("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50));")
    executor.execute("INSERT INTO t (id, name) VALUES (1, 'Alice');")
    executor.execute("INSERT INTO t (id, name) VALUES (2, 'Bob');")

    val result = executor.execute("DELETE FROM t WHERE id = 1;")
    result.shouldBeInstanceOf<ExecuteResult.Deleted>()
    result.count shouldBe 1

    val select = executor.execute("SELECT * FROM t;") as ExecuteResult.Selected
    select.rows.size shouldBe 1
    select.rows[0][1] shouldBe "Bob"
}

@Test
fun `DELETE WHERE 없이 전체 삭제`() {
    executor.execute("CREATE TABLE t (a INT);")
    executor.execute("INSERT INTO t (a) VALUES (1);")
    executor.execute("INSERT INTO t (a) VALUES (2);")

    val result = executor.execute("DELETE FROM t;")
    result.shouldBeInstanceOf<ExecuteResult.Deleted>()
    result.count shouldBe 2

    val select = executor.execute("SELECT * FROM t;") as ExecuteResult.Selected
    select.rows.size shouldBe 0
}

// === DROP 후 에러 ===

@Test
fun `DROP TABLE 후 SELECT 시 에러`() {
    executor.execute("CREATE TABLE t (a INT);")
    executor.execute("DROP TABLE t;")
    assertThrows<BindException> {
        executor.execute("SELECT * FROM t;")
    }
}
```

- [ ] **Step 11-2b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: PASS

### Step 11-3: 전체 데이터 타입 라운드트립 테스트

- [ ] **Step 11-3a: 라운드트립 테스트 추가**

```kotlin
@Test
fun `모든 DataType INSERT 후 SELECT 라운드트립`() {
    executor.execute("""
        CREATE TABLE all_types (
            b BOOLEAN,
            i INT,
            l BIGINT,
            d DOUBLE,
            ts TIMESTAMP,
            s VARCHAR(200)
        );
    """.trimIndent())

    executor.execute("INSERT INTO all_types (b, i, l, d, ts, s) VALUES (TRUE, 42, 9999999999, 3.14, 1700000000000, 'hello');")

    val result = executor.execute("SELECT * FROM all_types;") as ExecuteResult.Selected
    result.rows.size shouldBe 1
    val row = result.rows[0]
    row[0] shouldBe true
    row[1] shouldBe 42
    row[2] shouldBe 9999999999L
    row[3] shouldBe 3.14
    row[4] shouldBe 1700000000000L
    row[5] shouldBe "hello"
}
```

- [ ] **Step 11-3b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: PASS

주의: `evaluateLiteral`에서 `IntLiteral.value`는 `Long`이지만 Tuple은 `INT32` 컬럼에 `Int`를 기대한다. `executeInsert`에서 `Long → Int` 변환 로직이 필요할 수 있다. 구현 시 `INT32` 컬럼에 `Long` 값이 들어오면 `toInt()`로 변환하는 코드를 `executeInsert` 안에 추가한다:

```kotlin
// executeInsert의 값 배열 구성 부분에서, 컬럼 타입에 맞게 변환
val rawValue = evaluateLiteral(stmt.values[i])
values[colIdx] = coerceValue(rawValue, schema.column(colIdx).type)
```

```kotlin
/**
 * 리터럴 값을 대상 DataType에 맞게 변환한다.
 */
private fun coerceValue(value: Any?, targetType: DataType): Any? {
    if (value == null) return null
    return when (targetType) {
        DataType.INT32 -> when (value) {
            is Long -> value.toInt()
            is Int -> value
            else -> value
        }
        DataType.INT64 -> when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> value
        }
        DataType.FLOAT64 -> when (value) {
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Double -> value
            else -> value
        }
        else -> value
    }
}
```

- [ ] **Step 11-3c: 전체 테스트 통과 확인**

Run: `./gradlew :core:test 2>&1 | tail -5`
Expected: PASS (기존 테스트 포함 전부 통과)

- [ ] **Step 11-3d: 커밋**

```bash
git add core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt
git commit -m "[Phase 3] Executor 통합 테스트 (SELECT/UPDATE/DELETE/DataType 라운드트립)"
```

---

## Task 12: CREATE TABLE → INSERT → SELECT 엔드투엔드 흐름 테스트

**Files:**
- Modify: `core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt`

이 테스트는 단일 시나리오로 전체 파이프라인의 정합성을 검증한다.

- [ ] **Step 12-1a: 엔드투엔드 테스트 추가**

```kotlin
@Test
fun `엔드투엔드 - 테이블 생성부터 CRUD까지`() {
    // 1. CREATE TABLE
    executor.execute("CREATE TABLE products (id INT NOT NULL, name VARCHAR(100), price DOUBLE);")

    // 2. INSERT 여러 건
    executor.execute("INSERT INTO products (id, name, price) VALUES (1, 'Apple', 1.5);")
    executor.execute("INSERT INTO products (id, name, price) VALUES (2, 'Banana', 0.5);")
    executor.execute("INSERT INTO products (id, name, price) VALUES (3, 'Cherry', 3.0);")

    // 3. SELECT 전체
    val all = executor.execute("SELECT * FROM products;") as ExecuteResult.Selected
    all.rows.size shouldBe 3

    // 4. SELECT WHERE + ORDER BY + LIMIT
    val expensive = executor.execute(
        "SELECT name, price FROM products WHERE price >= 1.0 ORDER BY price DESC LIMIT 2;"
    ) as ExecuteResult.Selected
    expensive.rows.size shouldBe 2
    expensive.rows[0][0] shouldBe "Cherry"
    expensive.rows[1][0] shouldBe "Apple"

    // 5. UPDATE
    val updated = executor.execute("UPDATE products SET price = 2.0 WHERE name = 'Banana';")
    updated.shouldBeInstanceOf<ExecuteResult.Updated>()
    updated.count shouldBe 1

    // 6. 검증 SELECT
    val banana = executor.execute("SELECT price FROM products WHERE name = 'Banana';") as ExecuteResult.Selected
    banana.rows[0][0] shouldBe 2.0

    // 7. DELETE
    val deleted = executor.execute("DELETE FROM products WHERE id = 1;")
    deleted.shouldBeInstanceOf<ExecuteResult.Deleted>()
    deleted.count shouldBe 1

    // 8. 최종 확인
    val remaining = executor.execute("SELECT * FROM products;") as ExecuteResult.Selected
    remaining.rows.size shouldBe 2

    // 9. DROP TABLE
    executor.execute("DROP TABLE products;")
    assertThrows<BindException> { executor.execute("SELECT * FROM products;") }
}
```

- [ ] **Step 12-1b: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.SqlExecutorTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 12-1c: 전체 테스트 최종 확인**

Run: `./gradlew :core:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 12-1d: 커밋**

```bash
git add core/src/test/kotlin/gwanbase/sql/SqlExecutorTest.kt
git commit -m "[Phase 3] 엔드투엔드 CRUD 통합 테스트"
```

---

## 요약

| Task | 내용 | 산출 파일 |
|---|---|---|
| 1 | Token, TokenType 데이터 클래스 | `sql/Token.kt` |
| 2 | ParseException, BindException | `sql/SqlException.kt` |
| 3 | Lexer 구현 + 테스트 | `sql/Lexer.kt`, `sql/LexerTest.kt` |
| 4 | AST sealed class 정의 | `sql/Ast.kt` |
| 5 | Parser — DDL (CREATE/DROP TABLE) | `sql/Parser.kt`, `sql/ParserTest.kt` |
| 6 | Parser — Pratt parsing 표현식 + SELECT | `sql/Parser.kt`, `sql/ParserTest.kt` |
| 7 | Parser — INSERT, UPDATE, DELETE | `sql/Parser.kt`, `sql/ParserTest.kt` |
| 8 | Database API 확장 | `table/Database.kt`, `table/DatabaseTest.kt` |
| 9 | Binder 구현 + 테스트 | `sql/Binder.kt`, `sql/BinderTest.kt` |
| 10 | Executor — DDL + INSERT | `sql/SqlExecutor.kt`, `sql/SqlExecutorTest.kt` |
| 11 | Executor — SELECT/UPDATE/DELETE 통합 | `sql/SqlExecutor.kt`, `sql/SqlExecutorTest.kt` |
| 12 | 엔드투엔드 CRUD 테스트 | `sql/SqlExecutorTest.kt` |
