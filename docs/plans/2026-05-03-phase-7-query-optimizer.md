# Phase 7: Query Optimizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 쿼리 옵티마이저를 도입하여 인덱스 스캔, 조인, 비용 기반 실행 계획 선택을 지원한다.

**Architecture:** AST → Optimizer → PlanNode → Planner → Operator 파이프라인. Optimizer가 통계 기반으로 최적 논리 계획(PlanNode)을 생성하고, Planner가 이를 물리 연산자(Operator)로 변환한다. Secondary Index(B+Tree → RID)로 기존 HeapFile과 연동한다.

**Tech Stack:** Kotlin 1.9.22, JUnit 5, Kotest assertions, B+Tree (Phase 1), Volcano Operator 모델 (Phase 4)

---

## 파일 맵

| 파일 | 역할 | 변경 유형 |
|------|------|-----------|
| `core/src/main/kotlin/gwanbase/sql/Token.kt` | TokenType에 INDEX, JOIN, ON, ANALYZE, EXPLAIN, DOT 추가 | 수정 |
| `core/src/main/kotlin/gwanbase/sql/Lexer.kt` | 새 키워드 매핑 + DOT 토큰 | 수정 |
| `core/src/main/kotlin/gwanbase/sql/Ast.kt` | FromClause, ColumnRef.table, 새 Statement 타입 | 수정 |
| `core/src/main/kotlin/gwanbase/sql/Parser.kt` | JOIN/ANALYZE/EXPLAIN/CREATE INDEX/DROP INDEX 파싱 | 수정 |
| `core/src/main/kotlin/gwanbase/sql/Binder.kt` | 다중 테이블 스코프 검증 | 수정 |
| `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` | 새 Statement 실행 분기 | 수정 |
| `core/src/main/kotlin/gwanbase/index/KeySerializer.kt` | 컬럼 값 ↔ B+Tree 키 직렬화 | 생성 |
| `core/src/main/kotlin/gwanbase/table/Catalog.kt` | IndexInfo, TableStats 추가 + 직렬화 | 수정 |
| `core/src/main/kotlin/gwanbase/table/Database.kt` | 인덱스 유지보수 훅 | 수정 |
| `core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt` | 논리 계획 트리 sealed class | 생성 |
| `core/src/main/kotlin/gwanbase/optimizer/StatisticsManager.kt` | 통계 관리 + ANALYZE 실행 | 생성 |
| `core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt` | 비용·선택도 추정 | 생성 |
| `core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt` | 접근 경로·조인 순서 선택 | 생성 |
| `core/src/main/kotlin/gwanbase/optimizer/Optimizer.kt` | AST → 최적 PlanNode 진입점 | 생성 |
| `core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt` | 인덱스 기반 스캔 연산자 | 생성 |
| `core/src/main/kotlin/gwanbase/execution/NestedLoopJoinOperator.kt` | NLJ 연산자 | 생성 |
| `core/src/main/kotlin/gwanbase/execution/Planner.kt` | PlanNode → Operator 변환으로 리팩토링 | 수정 |
| `core/src/test/kotlin/gwanbase/sql/LexerTest.kt` | 새 키워드 토큰화 테스트 | 수정 |
| `core/src/test/kotlin/gwanbase/sql/ParserTest.kt` | 새 구문 파싱 테스트 | 수정 |
| `core/src/test/kotlin/gwanbase/sql/BinderTest.kt` | 다중 테이블 바인딩 테스트 | 수정 |
| `core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt` | 키 직렬화 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/table/CatalogIndexTest.kt` | 인덱스 메타데이터 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt` | 비용·선택도 추정 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt` | 계획 열거 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt` | 인덱스 스캔 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/execution/NestedLoopJoinOperatorTest.kt` | NLJ 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt` | 옵티마이저 통합 테스트 | 생성 |
| `core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt` | E2E SQL 통합 테스트 | 생성 |

---

### Task 1: AST 확장 — ColumnRef.table, FromClause, 새 Statement 타입

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Ast.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt` (컴파일 수정)
- Modify: `core/src/main/kotlin/gwanbase/sql/Binder.kt` (컴파일 수정)
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt` (컴파일 수정)
- Modify: `core/src/main/kotlin/gwanbase/execution/Planner.kt` (컴파일 수정)
- Modify: `core/src/main/kotlin/gwanbase/execution/ExpressionEvaluator.kt` (컴파일 수정)

AST를 변경하면 기존 코드 전체가 컴파일 실패한다. 이 태스크에서 AST 변경 + 컴파일 수정까지 한 번에 처리한다.

- [ ] **Step 1: ColumnRef에 table 필드 추가**

`core/src/main/kotlin/gwanbase/sql/Ast.kt` — `Expression.ColumnRef` 변경:

```kotlin
/** 컬럼 참조. table이 null이면 단일 테이블에서 해석. */
data class ColumnRef(val table: String?, val name: String) : Expression()
```

- [ ] **Step 2: FromClause sealed class 추가**

`core/src/main/kotlin/gwanbase/sql/Ast.kt`에 추가:

```kotlin
/**
 * FROM 절 AST.
 */
sealed class FromClause {
    /** 단일 테이블 참조. */
    data class Table(val tableName: String, val alias: String? = null) : FromClause()
    /** INNER JOIN. */
    data class Join(
        val left: FromClause,
        val right: FromClause,
        val condition: Expression,
    ) : FromClause()
}
```

- [ ] **Step 3: Select.tableName → Select.from 변경**

```kotlin
data class Select(
    val columns: List<SelectItem>,
    val from: FromClause,
    val where: Expression?,
    val orderBy: OrderByClause?,
    val limit: Int?,
) : Statement()
```

- [ ] **Step 4: 새 Statement 타입 추가**

```kotlin
/** CREATE INDEX 문. */
data class CreateIndex(
    val indexName: String,
    val tableName: String,
    val columnName: String,
) : Statement()

/** DROP INDEX 문. */
data class DropIndex(val indexName: String) : Statement()

/** ANALYZE 문. */
data class Analyze(val tableName: String) : Statement()

/** EXPLAIN 문. */
data class Explain(val statement: Statement) : Statement()
```

- [ ] **Step 5: Parser — Select.tableName → Select.from 컴파일 수정**

`Parser.kt`의 `parseSelect()`:

```kotlin
private fun parseSelect(): Statement.Select {
    expect(TokenType.SELECT, "SELECT 키워드가 필요하다")
    val columns = parseSelectList()
    expect(TokenType.FROM, "FROM 키워드가 필요하다")
    val tableName = expectIdentifier("테이블 이름이 필요하다")

    // WHERE
    val where = if (peek().type == TokenType.WHERE) {
        advance()
        parseExpression(0)
    } else null

    // ORDER BY
    val orderBy = if (peek().type == TokenType.ORDER) {
        parseOrderBy()
    } else null

    // LIMIT
    val limit = if (peek().type == TokenType.LIMIT) {
        advance()
        val limitToken = expect(TokenType.INTEGER_LITERAL, "LIMIT 값이 필요하다")
        limitToken.literal.toInt()
    } else null

    return Statement.Select(columns, FromClause.Table(tableName), where, orderBy, limit)
}
```

- [ ] **Step 6: Parser — ColumnRef 파싱 수정 (table = null)**

`Parser.kt`의 `parsePrefixExpression()`:

```kotlin
TokenType.IDENTIFIER -> {
    advance()
    Expression.ColumnRef(null, token.literal)
}
```

- [ ] **Step 7: Binder — Select.tableName → Select.from 컴파일 수정**

`Binder.kt`의 `bindSelect()`:

```kotlin
private fun bindSelect(stmt: Statement.Select) {
    val tableName = (stmt.from as? FromClause.Table)?.tableName
        ?: throw BindException("JOIN은 아직 바인딩을 지원하지 않는다")
    val schema = requireTable(tableName)
    // ... 나머지 동일
}
```

`validateExpression()`의 ColumnRef 분기:

```kotlin
is Expression.ColumnRef -> requireColumn(schema, expr.name)
```

- [ ] **Step 8: SqlExecutor — when 분기에 새 Statement 타입 stub 추가**

```kotlin
is Statement.CreateIndex -> TODO("Task 10에서 구현")
is Statement.DropIndex -> TODO("Task 10에서 구현")
is Statement.Analyze -> TODO("Task 13에서 구현")
is Statement.Explain -> TODO("Task 17에서 구현")
```

- [ ] **Step 9: Planner — Select.tableName → Select.from 컴파일 수정**

```kotlin
fun planSelect(stmt: Statement.Select): Operator {
    val tableName = (stmt.from as FromClause.Table).tableName
    var op: Operator = SeqScanOperator(database, tableName, session)
    // ... 나머지 동일
}
```

- [ ] **Step 10: ExpressionEvaluator — ColumnRef.name 참조 수정**

기존 `expr.name` → 동일하게 `expr.name`. `expr.table`은 무시 (단일 테이블에서는 불필요). 컴파일 에러가 나는 부분만 수정.

- [ ] **Step 11: 전체 빌드 확인**

Run: `./gradlew :core:test`
Expected: 모든 기존 테스트 PASS (회귀 없음)

- [ ] **Step 12: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Ast.kt \
       core/src/main/kotlin/gwanbase/sql/Parser.kt \
       core/src/main/kotlin/gwanbase/sql/Binder.kt \
       core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
       core/src/main/kotlin/gwanbase/execution/Planner.kt \
       core/src/main/kotlin/gwanbase/execution/ExpressionEvaluator.kt
git commit -m "[Phase 7] AST 확장: FromClause, ColumnRef.table, 새 Statement 타입"
```

---

### Task 2: Token/Lexer 확장 — 새 키워드 및 DOT 토큰

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Token.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/Lexer.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/LexerTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`core/src/test/kotlin/gwanbase/sql/LexerTest.kt`에 추가:

```kotlin
@Test
fun `JOIN 관련 키워드 토큰화`() {
    val tokens = Lexer("SELECT a FROM t1 JOIN t2 ON t1.id = t2.t1_id").tokenize()
    val types = tokens.map { it.type }
    types shouldContain TokenType.JOIN
    types shouldContain TokenType.ON
    types shouldContain TokenType.DOT
}

@Test
fun `INDEX 관련 키워드 토큰화`() {
    val tokens = Lexer("CREATE INDEX idx ON users (age)").tokenize()
    val types = tokens.map { it.type }
    types shouldContain TokenType.INDEX
    types shouldContain TokenType.ON
}

@Test
fun `ANALYZE 키워드 토큰화`() {
    val tokens = Lexer("ANALYZE users").tokenize()
    tokens[0].type shouldBe TokenType.ANALYZE
}

@Test
fun `EXPLAIN 키워드 토큰화`() {
    val tokens = Lexer("EXPLAIN SELECT * FROM users").tokenize()
    tokens[0].type shouldBe TokenType.EXPLAIN
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest"`
Expected: FAIL — TokenType에 JOIN, ON, DOT, INDEX, ANALYZE, EXPLAIN 없음

- [ ] **Step 3: TokenType에 새 키워드 추가**

`core/src/main/kotlin/gwanbase/sql/Token.kt`:

```kotlin
enum class TokenType {
    // 키워드
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, DROP, TABLE, ORDER, BY, ASC, DESC, LIMIT,
    AND, OR, NOT, NULL, TRUE, FALSE,
    IS,
    BEGIN, COMMIT, ROLLBACK,
    JOIN, ON, INDEX, ANALYZE, EXPLAIN,  // Phase 7 추가
    INT, INTEGER, BIGINT, BOOLEAN, DOUBLE, FLOAT, VARCHAR, TIMESTAMP,

    // 리터럴
    INTEGER_LITERAL, FLOAT_LITERAL, STRING_LITERAL,

    // 식별자
    IDENTIFIER,

    // 연산자
    PLUS, MINUS, STAR, SLASH,
    EQ, NEQ, LT, GT, LTE, GTE,

    // 구두점
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,  // DOT 추가

    // 특수
    EOF,
}
```

- [ ] **Step 4: Lexer에 키워드 매핑 + DOT 토큰 추가**

`core/src/main/kotlin/gwanbase/sql/Lexer.kt`의 KEYWORDS 맵에 추가:

```kotlin
"JOIN" to TokenType.JOIN,
"ON" to TokenType.ON,
"INDEX" to TokenType.INDEX,
"ANALYZE" to TokenType.ANALYZE,
"EXPLAIN" to TokenType.EXPLAIN,
```

`readOperatorOrPunctuation()`에 DOT 추가:

```kotlin
'.' -> Token(TokenType.DOT, ".", start)
```

주의: `readNumber()`가 `123.456` 같은 실수를 먼저 처리하므로 DOT과 충돌 없음. 숫자 뒤의 `.`은 readNumber가 소비하고, 식별자 뒤의 `.`은 readOperatorOrPunctuation이 처리한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.LexerTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Token.kt \
       core/src/main/kotlin/gwanbase/sql/Lexer.kt \
       core/src/test/kotlin/gwanbase/sql/LexerTest.kt
git commit -m "[Phase 7] Lexer 확장: JOIN, ON, INDEX, ANALYZE, EXPLAIN, DOT 토큰"
```

---

### Task 3: Parser — CREATE INDEX, DROP INDEX, ANALYZE, EXPLAIN 파싱

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`core/src/test/kotlin/gwanbase/sql/ParserTest.kt`에 추가:

```kotlin
@Test
fun `CREATE INDEX 파싱`() {
    val stmt = parse("CREATE INDEX idx_age ON users (age)")
    stmt shouldBe Statement.CreateIndex("idx_age", "users", "age")
}

@Test
fun `DROP INDEX 파싱`() {
    val stmt = parse("DROP INDEX idx_age")
    stmt shouldBe Statement.DropIndex("idx_age")
}

@Test
fun `ANALYZE 파싱`() {
    val stmt = parse("ANALYZE users")
    stmt shouldBe Statement.Analyze("users")
}

@Test
fun `EXPLAIN SELECT 파싱`() {
    val stmt = parse("EXPLAIN SELECT * FROM users")
    stmt as Statement.Explain
    val inner = stmt.statement as Statement.Select
    (inner.from as FromClause.Table).tableName shouldBe "users"
}

// 헬퍼 (이미 있으면 재사용)
private fun parse(sql: String): Statement {
    val tokens = Lexer(sql).tokenize()
    return Parser(tokens).parse()
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest"`
Expected: FAIL

- [ ] **Step 3: Parser에 parseStatement 분기 추가**

`Parser.kt`의 `parseStatement()`:

```kotlin
private fun parseStatement(): Statement {
    return when (peek().type) {
        TokenType.CREATE -> parseCreate()        // CREATE TABLE 또는 CREATE INDEX
        TokenType.DROP -> parseDrop()             // DROP TABLE 또는 DROP INDEX
        TokenType.SELECT -> parseSelect()
        TokenType.INSERT -> parseInsert()
        TokenType.UPDATE -> parseUpdate()
        TokenType.DELETE -> parseDelete()
        TokenType.BEGIN -> { advance(); Statement.Begin }
        TokenType.COMMIT -> { advance(); Statement.Commit }
        TokenType.ROLLBACK -> { advance(); Statement.Rollback }
        TokenType.ANALYZE -> parseAnalyze()
        TokenType.EXPLAIN -> parseExplain()
        else -> throw ParseException("예상하지 못한 토큰: '${peek().literal}'", peek().position)
    }
}
```

- [ ] **Step 4: parseCreate — TABLE vs INDEX 분기**

기존 `parseCreateTable()`을 `parseCreate()`로 감싸기:

```kotlin
private fun parseCreate(): Statement {
    expect(TokenType.CREATE, "CREATE 키워드가 필요하다")
    return when (peek().type) {
        TokenType.TABLE -> parseCreateTableBody()
        TokenType.INDEX -> parseCreateIndex()
        else -> throw ParseException(
            "CREATE 뒤에 TABLE 또는 INDEX가 필요하다", peek().position
        )
    }
}

private fun parseCreateTableBody(): Statement.CreateTable {
    expect(TokenType.TABLE, "TABLE 키워드가 필요하다")
    val tableName = expectIdentifier("테이블 이름이 필요하다")
    expect(TokenType.LPAREN, "'(' 가 필요하다")
    val columns = mutableListOf<ColumnDef>()
    columns.add(parseColumnDef())
    while (peek().type == TokenType.COMMA) {
        advance()
        columns.add(parseColumnDef())
    }
    expect(TokenType.RPAREN, "')' 가 필요하다")
    return Statement.CreateTable(tableName, columns)
}

private fun parseCreateIndex(): Statement.CreateIndex {
    expect(TokenType.INDEX, "INDEX 키워드가 필요하다")
    val indexName = expectIdentifier("인덱스 이름이 필요하다")
    expect(TokenType.ON, "ON 키워드가 필요하다")
    val tableName = expectIdentifier("테이블 이름이 필요하다")
    expect(TokenType.LPAREN, "'(' 가 필요하다")
    val columnName = expectIdentifier("컬럼 이름이 필요하다")
    expect(TokenType.RPAREN, "')' 가 필요하다")
    return Statement.CreateIndex(indexName, tableName, columnName)
}
```

- [ ] **Step 5: parseDrop — TABLE vs INDEX 분기**

```kotlin
private fun parseDrop(): Statement {
    expect(TokenType.DROP, "DROP 키워드가 필요하다")
    return when (peek().type) {
        TokenType.TABLE -> {
            advance()
            Statement.DropTable(expectIdentifier("테이블 이름이 필요하다"))
        }
        TokenType.INDEX -> {
            advance()
            Statement.DropIndex(expectIdentifier("인덱스 이름이 필요하다"))
        }
        else -> throw ParseException(
            "DROP 뒤에 TABLE 또는 INDEX가 필요하다", peek().position
        )
    }
}
```

- [ ] **Step 6: parseAnalyze, parseExplain**

```kotlin
private fun parseAnalyze(): Statement.Analyze {
    expect(TokenType.ANALYZE, "ANALYZE 키워드가 필요하다")
    val tableName = expectIdentifier("테이블 이름이 필요하다")
    return Statement.Analyze(tableName)
}

private fun parseExplain(): Statement.Explain {
    expect(TokenType.EXPLAIN, "EXPLAIN 키워드가 필요하다")
    val inner = parseStatement()
    return Statement.Explain(inner)
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Parser.kt \
       core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 7] Parser: CREATE/DROP INDEX, ANALYZE, EXPLAIN 파싱"
```

---

### Task 4: Parser — JOIN 구문 + 테이블 별칭 + 테이블 한정 컬럼 참조

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Parser.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/ParserTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
@Test
fun `단일 테이블 별칭 파싱`() {
    val stmt = parse("SELECT u.name FROM users u WHERE u.age = 25") as Statement.Select
    val from = stmt.from as FromClause.Table
    from.tableName shouldBe "users"
    from.alias shouldBe "u"
}

@Test
fun `테이블 한정 컬럼 참조 파싱`() {
    val stmt = parse("SELECT u.name FROM users u") as Statement.Select
    val item = stmt.columns[0] as SelectItem.ExprItem
    val ref = item.expr as Expression.ColumnRef
    ref.table shouldBe "u"
    ref.name shouldBe "name"
}

@Test
fun `INNER JOIN 파싱`() {
    val stmt = parse(
        "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id"
    ) as Statement.Select
    val join = stmt.from as FromClause.Join
    (join.left as FromClause.Table).tableName shouldBe "users"
    (join.right as FromClause.Table).tableName shouldBe "orders"
}

@Test
fun `별칭 없는 JOIN 파싱`() {
    val stmt = parse(
        "SELECT * FROM users JOIN orders ON users.id = orders.user_id"
    ) as Statement.Select
    val join = stmt.from as FromClause.Join
    (join.left as FromClause.Table).alias shouldBe null
    (join.right as FromClause.Table).alias shouldBe null
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest"`
Expected: FAIL

- [ ] **Step 3: parseSelect의 FROM 절 변경**

```kotlin
private fun parseSelect(): Statement.Select {
    expect(TokenType.SELECT, "SELECT 키워드가 필요하다")
    val columns = parseSelectList()
    expect(TokenType.FROM, "FROM 키워드가 필요하다")
    val from = parseFromClause()

    val where = if (peek().type == TokenType.WHERE) {
        advance()
        parseExpression(0)
    } else null

    val orderBy = if (peek().type == TokenType.ORDER) {
        parseOrderBy()
    } else null

    val limit = if (peek().type == TokenType.LIMIT) {
        advance()
        val limitToken = expect(TokenType.INTEGER_LITERAL, "LIMIT 값이 필요하다")
        limitToken.literal.toInt()
    } else null

    return Statement.Select(columns, from, where, orderBy, limit)
}
```

- [ ] **Step 4: parseFromClause — 테이블 + 별칭 + JOIN 체인**

```kotlin
private fun parseFromClause(): FromClause {
    var left: FromClause = parseTableRef()
    while (peek().type == TokenType.JOIN) {
        advance() // JOIN 소비
        val right = parseTableRef()
        expect(TokenType.ON, "ON 키워드가 필요하다")
        val condition = parseExpression(0)
        left = FromClause.Join(left, right, condition)
    }
    return left
}

private fun parseTableRef(): FromClause.Table {
    val tableName = expectIdentifier("테이블 이름이 필요하다")
    // 별칭: 다음 토큰이 IDENTIFIER이고 키워드가 아닌 경우
    val alias = if (peek().type == TokenType.IDENTIFIER) {
        advance().literal
    } else null
    return FromClause.Table(tableName, alias)
}
```

- [ ] **Step 5: 테이블 한정 컬럼 참조 파싱 (IDENTIFIER.IDENTIFIER)**

`parsePrefixExpression()`에서 IDENTIFIER 뒤에 DOT이 오면 `table.column`으로 파싱:

```kotlin
TokenType.IDENTIFIER -> {
    advance()
    if (peek().type == TokenType.DOT) {
        advance() // DOT 소비
        val colName = expectIdentifier("컬럼 이름이 필요하다")
        Expression.ColumnRef(token.literal, colName)
    } else {
        Expression.ColumnRef(null, token.literal)
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.ParserTest"`
Expected: PASS

- [ ] **Step 7: 전체 테스트 회귀 확인**

Run: `./gradlew :core:test`
Expected: 모든 기존 테스트 PASS

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Parser.kt \
       core/src/test/kotlin/gwanbase/sql/ParserTest.kt
git commit -m "[Phase 7] Parser: JOIN 구문, 테이블 별칭, 테이블 한정 컬럼 참조 파싱"
```

---

### Task 5: Binder — 다중 테이블 스코프

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/Binder.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/BinderTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
@Test
fun `JOIN 바인딩 — 양쪽 테이블 컬럼 검증 통과`() {
    // Catalog에 users(id INT, name VARCHAR), orders(id INT, user_id INT, amount INT) 생성
    // SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id
    // → 바인딩 성공
}

@Test
fun `JOIN 바인딩 — 존재하지 않는 컬럼 참조 시 BindException`() {
    // SELECT u.nonexistent FROM users u JOIN orders o ON u.id = o.user_id
    // → BindException
}

@Test
fun `비한정 컬럼 참조 — 한쪽에만 존재하면 통과`() {
    // SELECT name FROM users u JOIN orders o ON u.id = o.user_id
    // name은 users에만 존재 → 통과
}

@Test
fun `비한정 컬럼 참조 — 양쪽에 존재하면 ambiguous BindException`() {
    // SELECT id FROM users u JOIN orders o ON u.id = o.user_id
    // id는 양쪽에 존재 → BindException
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.BinderTest"`
Expected: FAIL

- [ ] **Step 3: Binder에 다중 테이블 스코프 구현**

FROM 절을 재귀 순회하여 `Map<String/alias, Schema>` 구성:

```kotlin
private fun bindSelect(stmt: Statement.Select) {
    val tableScopes = collectTableScopes(stmt.from)

    if (tableScopes.size == 1) {
        // 기존 단일 테이블 로직 (하위 호환)
        val schema = tableScopes.values.first()
        for (item in stmt.columns) {
            when (item) {
                is SelectItem.Star -> {}
                is SelectItem.ExprItem -> validateExpression(schema, item.expr)
            }
        }
        if (stmt.where != null) validateExpression(schema, stmt.where)
        if (stmt.orderBy != null) requireColumn(schema, stmt.orderBy.column)
    } else {
        // 다중 테이블 스코프
        for (item in stmt.columns) {
            when (item) {
                is SelectItem.Star -> {}
                is SelectItem.ExprItem -> validateMultiTableExpression(tableScopes, item.expr)
            }
        }
        if (stmt.where != null) validateMultiTableExpression(tableScopes, stmt.where)
        // JOIN의 ON 조건은 parseFromClause에서 이미 AST에 포함됨
        validateJoinConditions(stmt.from, tableScopes)
    }
}

private fun collectTableScopes(from: FromClause): Map<String, Schema> {
    val scopes = mutableMapOf<String, Schema>()
    collectTablesRecursive(from, scopes)
    return scopes
}

private fun collectTablesRecursive(from: FromClause, scopes: MutableMap<String, Schema>) {
    when (from) {
        is FromClause.Table -> {
            val schema = requireTable(from.tableName)
            val key = from.alias ?: from.tableName
            scopes[key] = schema
        }
        is FromClause.Join -> {
            collectTablesRecursive(from.left, scopes)
            collectTablesRecursive(from.right, scopes)
        }
    }
}

private fun validateMultiTableExpression(scopes: Map<String, Schema>, expr: Expression) {
    when (expr) {
        is Expression.ColumnRef -> {
            if (expr.table != null) {
                val schema = scopes[expr.table]
                    ?: throw BindException("테이블 또는 별칭 '${expr.table}'이 FROM 절에 없다")
                requireColumn(schema, expr.name)
            } else {
                // 비한정 참조 — 모든 스코프에서 검색
                val matches = scopes.filter { (_, schema) ->
                    try { schema.columnIndex(expr.name); true } catch (_: Exception) { false }
                }
                when {
                    matches.isEmpty() -> throw BindException("컬럼 '${expr.name}'이 어떤 테이블에도 존재하지 않는다")
                    matches.size > 1 -> throw BindException("컬럼 '${expr.name}'이 여러 테이블에 존재한다 (ambiguous)")
                }
            }
        }
        is Expression.BinaryOp -> {
            validateMultiTableExpression(scopes, expr.left)
            validateMultiTableExpression(scopes, expr.right)
        }
        is Expression.UnaryOp -> validateMultiTableExpression(scopes, expr.operand)
        is Expression.IsNull -> validateMultiTableExpression(scopes, expr.expr)
        is Expression.IsNotNull -> validateMultiTableExpression(scopes, expr.expr)
        else -> {}
    }
}

private fun validateJoinConditions(from: FromClause, scopes: Map<String, Schema>) {
    if (from is FromClause.Join) {
        validateMultiTableExpression(scopes, from.condition)
        validateJoinConditions(from.left, scopes)
        validateJoinConditions(from.right, scopes)
    }
}
```

- [ ] **Step 4: CREATE INDEX, DROP INDEX, ANALYZE 바인딩 추가**

```kotlin
fun bind(statement: Statement): Statement {
    when (statement) {
        // ... 기존 ...
        is Statement.CreateIndex -> bindCreateIndex(statement)
        is Statement.DropIndex -> { /* indexName 존재 검증은 런타임 시 처리 */ }
        is Statement.Analyze -> { requireTable(statement.tableName) }
        is Statement.Explain -> bind(statement.statement)
    }
    return statement
}

private fun bindCreateIndex(stmt: Statement.CreateIndex) {
    val schema = requireTable(stmt.tableName)
    requireColumn(schema, stmt.columnName)
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.BinderTest"`
Expected: PASS

- [ ] **Step 6: 전체 테스트 회귀 확인**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/Binder.kt \
       core/src/test/kotlin/gwanbase/sql/BinderTest.kt
git commit -m "[Phase 7] Binder: 다중 테이블 스코프, CREATE INDEX/ANALYZE 바인딩"
```

---

### Task 6: KeySerializer — 컬럼 값 ↔ B+Tree 키 직렬화

**Files:**
- Create: `core/src/main/kotlin/gwanbase/index/KeySerializer.kt`
- Create: `core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.index

import gwanbase.table.DataType
import gwanbase.table.RID
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KeySerializerTest {

    @Test
    fun `INT32 직렬화 — 정렬 순서 보존 (음수 less than 0 less than 양수)`() {
        val neg = KeySerializer.serializeKey(-10, DataType.INT32)
        val zero = KeySerializer.serializeKey(0, DataType.INT32)
        val pos = KeySerializer.serializeKey(42, DataType.INT32)
        compareUnsigned(neg, zero) shouldBeLessThan 0
        compareUnsigned(zero, pos) shouldBeLessThan 0
    }

    @Test
    fun `INT64 직렬화 — 정렬 순서 보존`() {
        val neg = KeySerializer.serializeKey(-100L, DataType.INT64)
        val zero = KeySerializer.serializeKey(0L, DataType.INT64)
        val pos = KeySerializer.serializeKey(999L, DataType.INT64)
        compareUnsigned(neg, zero) shouldBeLessThan 0
        compareUnsigned(zero, pos) shouldBeLessThan 0
    }

    @Test
    fun `VARCHAR 직렬화 — 사전순 정렬`() {
        val a = KeySerializer.serializeKey("apple", DataType.VARCHAR)
        val b = KeySerializer.serializeKey("banana", DataType.VARCHAR)
        compareUnsigned(a, b) shouldBeLessThan 0
    }

    @Test
    fun `RID 직렬화 역직렬화 왕복`() {
        val rid = RID(pageId = 42, slotId = 7)
        val bytes = KeySerializer.serializeRid(rid)
        bytes.size shouldBe 6
        val restored = KeySerializer.deserializeRid(bytes)
        restored shouldBe rid
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.KeySerializerTest"`
Expected: FAIL — KeySerializer 클래스 없음

- [ ] **Step 3: KeySerializer 구현**

```kotlin
package gwanbase.index

import gwanbase.table.DataType
import gwanbase.table.RID
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테이블 컬럼 값을 B+Tree 키로 직렬화한다.
 *
 * unsigned lexicographic 비교에서 올바른 정렬 순서가 보존되도록
 * 부호 있는 정수 타입은 부호 비트를 반전시킨다.
 */
object KeySerializer {

    /**
     * 컬럼 값을 B+Tree 키 바이트 배열로 변환한다.
     */
    fun serializeKey(value: Any, dataType: DataType): ByteArray {
        return when (dataType) {
            DataType.INT32 -> {
                val intVal = when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    else -> error("INT32 직렬화 대상이 정수가 아니다: $value")
                }
                val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(intVal xor Int.MIN_VALUE)  // 부호 비트 반전
                buf.array()
            }
            DataType.INT64, DataType.TIMESTAMP -> {
                val longVal = when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> error("INT64 직렬화 대상이 정수가 아니다: $value")
                }
                val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                buf.putLong(longVal xor Long.MIN_VALUE)  // 부호 비트 반전
                buf.array()
            }
            DataType.VARCHAR -> {
                (value as String).toByteArray(Charsets.UTF_8)
            }
            DataType.BOOLEAN -> {
                byteArrayOf(if (value as Boolean) 1 else 0)
            }
            DataType.FLOAT64 -> {
                error("FLOAT64 인덱스는 MVP에서 미지원")
            }
        }
    }

    /** RID를 6바이트로 직렬화한다. */
    fun serializeRid(rid: RID): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(rid.pageId)
        buf.putShort(rid.slotId.toShort())
        return buf.array()
    }

    /** 6바이트에서 RID를 역직렬화한다. */
    fun deserializeRid(bytes: ByteArray): RID {
        require(bytes.size == 6) { "RID 바이트 배열 크기가 6이 아니다: ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val pageId = buf.getInt()
        val slotId = buf.getShort().toInt() and 0xFFFF
        return RID(pageId, slotId)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.index.KeySerializerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/index/KeySerializer.kt \
       core/src/test/kotlin/gwanbase/index/KeySerializerTest.kt
git commit -m "[Phase 7] KeySerializer: 컬럼 값 ↔ B+Tree 키 직렬화"
```

---

### Task 7: Catalog 확장 — IndexInfo 메타데이터

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/table/Catalog.kt`
- Create: `core/src/test/kotlin/gwanbase/table/CatalogIndexTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.table

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CatalogIndexTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createCatalog(): Catalog {
        val dm = DiskManager(tempDir.resolve("test.db"))
        val bpm = BufferPoolManager(dm, 64)
        bpm.newPage()  // pageId 0 (metadata)
        return Catalog.createNew(bpm)
    }

    @Test
    fun `인덱스 등록 후 조회`() {
        val catalog = createCatalog()
        val schema = Schema(listOf(Column("id", DataType.INT32, nullable = false)))
        catalog.createTable("users", schema)
        catalog.createIndex("idx_id", "users", "id", rootPageId = 10)
        val idx = catalog.getIndex("idx_id")
        idx shouldNotBe null
        idx!!.tableName shouldBe "users"
        idx.columnName shouldBe "id"
        idx.rootPageId shouldBe 10
    }

    @Test
    fun `테이블별 인덱스 목록 조회`() {
        val catalog = createCatalog()
        val schema = Schema(listOf(
            Column("id", DataType.INT32, nullable = false),
            Column("age", DataType.INT32, nullable = true),
        ))
        catalog.createTable("users", schema)
        catalog.createIndex("idx_id", "users", "id", rootPageId = 10)
        catalog.createIndex("idx_age", "users", "age", rootPageId = 11)
        catalog.getIndexesForTable("users") shouldHaveSize 2
    }

    @Test
    fun `인덱스 삭제`() {
        val catalog = createCatalog()
        val schema = Schema(listOf(Column("id", DataType.INT32, nullable = false)))
        catalog.createTable("users", schema)
        catalog.createIndex("idx_id", "users", "id", rootPageId = 10)
        catalog.dropIndex("idx_id") shouldBe true
        catalog.getIndex("idx_id") shouldBe null
    }

    @Test
    fun `Catalog 영속화 후 재로드 시 인덱스 보존`() {
        val dm = DiskManager(tempDir.resolve("persist.db"))
        val bpm = BufferPoolManager(dm, 64)
        bpm.newPage()  // pageId 0
        val catalog = Catalog.createNew(bpm)
        val schema = Schema(listOf(Column("id", DataType.INT32, nullable = false)))
        catalog.createTable("users", schema)
        catalog.createIndex("idx_id", "users", "id", rootPageId = 10)

        // 재로드
        val catalog2 = Catalog.load(bpm, catalog.catalogPageId)
        val idx = catalog2.getIndex("idx_id")
        idx shouldNotBe null
        idx!!.rootPageId shouldBe 10
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogIndexTest"`
Expected: FAIL

- [ ] **Step 3: IndexInfo data class + Catalog 인덱스 메서드 추가**

`Catalog.kt`에 추가:

```kotlin
data class IndexInfo(
    val indexId: Int,
    val name: String,
    val tableName: String,
    val columnName: String,
    val rootPageId: Int,
)
```

Catalog 클래스에 인덱스 관련 필드와 메서드:

```kotlin
private val indexes = mutableListOf<IndexInfo>()
private var nextIndexId: Int = 1

fun createIndex(name: String, tableName: String, columnName: String, rootPageId: Int): IndexInfo {
    require(indexes.none { it.name == name }) { "인덱스 '$name'이 이미 존재한다" }
    val info = IndexInfo(nextIndexId++, name, tableName, columnName, rootPageId)
    indexes.add(info)
    flush()
    return info
}

fun getIndex(name: String): IndexInfo? = indexes.find { it.name == name }

fun getIndexesForTable(tableName: String): List<IndexInfo> =
    indexes.filter { it.tableName == tableName }

fun dropIndex(name: String): Boolean {
    val removed = indexes.removeAll { it.name == name }
    if (removed) flush()
    return removed
}
```

- [ ] **Step 4: Catalog 직렬화에 인덱스 섹션 추가**

`flush()`에 기존 테이블 직렬화 뒤에 인덱스 직렬화 추가:

```kotlin
// 인덱스 직렬화
buf.putInt(nextIndexId)
buf.putInt(indexes.size)
for (idx in indexes) {
    buf.putInt(idx.indexId)
    val nameBytes = idx.name.toByteArray(Charsets.UTF_8)
    buf.putShort(nameBytes.size.toShort())
    buf.put(nameBytes)
    val tableNameBytes = idx.tableName.toByteArray(Charsets.UTF_8)
    buf.putShort(tableNameBytes.size.toShort())
    buf.put(tableNameBytes)
    val colNameBytes = idx.columnName.toByteArray(Charsets.UTF_8)
    buf.putShort(colNameBytes.size.toShort())
    buf.put(colNameBytes)
    buf.putInt(idx.rootPageId)
}
```

`loadFromPage()`에 역직렬화 추가 (테이블 로드 뒤):

```kotlin
// 인덱스 역직렬화 (데이터가 남아 있을 때만 — 하위 호환)
if (buf.remaining() >= 8) {
    nextIndexId = buf.getInt()
    val indexCount = buf.getInt()
    indexes.clear()
    repeat(indexCount) {
        val indexId = buf.getInt()
        val nameLen = buf.getShort().toInt() and 0xFFFF
        val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)
        val tblLen = buf.getShort().toInt() and 0xFFFF
        val tblBytes = ByteArray(tblLen); buf.get(tblBytes)
        val tblName = String(tblBytes, Charsets.UTF_8)
        val colLen = buf.getShort().toInt() and 0xFFFF
        val colBytes = ByteArray(colLen); buf.get(colBytes)
        val colName = String(colBytes, Charsets.UTF_8)
        val rootPageId = buf.getInt()
        indexes.add(IndexInfo(indexId, name, tblName, colName, rootPageId))
    }
}
```

`estimateSerializedSize()`도 인덱스 크기 반영.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogIndexTest"`
Expected: PASS

- [ ] **Step 6: 전체 회귀 확인**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/table/Catalog.kt \
       core/src/test/kotlin/gwanbase/table/CatalogIndexTest.kt
git commit -m "[Phase 7] Catalog: IndexInfo 메타데이터 + 직렬화"
```

---

### Task 8: Catalog 확장 — TableStats / ColumnStats

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/table/Catalog.kt`
- Modify: `core/src/test/kotlin/gwanbase/table/CatalogIndexTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
@Test
fun `행 수 초기값 0, 증가 후 조회`() {
    val catalog = createCatalog()
    val schema = Schema(listOf(Column("id", DataType.INT32, nullable = false)))
    catalog.createTable("users", schema)
    catalog.getRowCount("users") shouldBe 0
    catalog.incrementRowCount("users")
    catalog.incrementRowCount("users")
    catalog.getRowCount("users") shouldBe 2
    catalog.decrementRowCount("users")
    catalog.getRowCount("users") shouldBe 1
}

@Test
fun `컬럼 통계 저장 및 조회`() {
    val catalog = createCatalog()
    val schema = Schema(listOf(Column("age", DataType.INT32, nullable = true)))
    catalog.createTable("users", schema)
    val stats = ColumnStats(distinctCount = 50, minValue = 1L, maxValue = 100L, nullCount = 3)
    catalog.updateColumnStats("users", "age", stats)
    val loaded = catalog.getColumnStats("users", "age")
    loaded shouldNotBe null
    loaded!!.distinctCount shouldBe 50
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogIndexTest"`
Expected: FAIL

- [ ] **Step 3: TableStats, ColumnStats data class 추가**

```kotlin
data class ColumnStats(
    val distinctCount: Long,
    val minValue: Any?,
    val maxValue: Any?,
    val nullCount: Long,
)
```

Catalog에 통계 저장소 추가:

```kotlin
private val rowCounts = mutableMapOf<String, Long>()      // tableName → rowCount
private val columnStatsMap = mutableMapOf<String, MutableMap<String, ColumnStats>>()  // tableName → (colName → stats)

fun getRowCount(tableName: String): Long = rowCounts[tableName] ?: 0
fun incrementRowCount(tableName: String) { rowCounts[tableName] = getRowCount(tableName) + 1 }
fun decrementRowCount(tableName: String) { rowCounts[tableName] = maxOf(0, getRowCount(tableName) - 1) }

fun updateColumnStats(tableName: String, columnName: String, stats: ColumnStats) {
    columnStatsMap.getOrPut(tableName) { mutableMapOf() }[columnName] = stats
    flush()
}

fun getColumnStats(tableName: String, columnName: String): ColumnStats? =
    columnStatsMap[tableName]?.get(columnName)

fun getAllColumnStats(tableName: String): Map<String, ColumnStats> =
    columnStatsMap[tableName] ?: emptyMap()
```

통계 직렬화는 별도 페이지로 분리할 수도 있지만, MVP에서는 Catalog 페이지에 함께 직렬화한다. 공간이 부족해지면 다중 Catalog 페이지로 확장.

rowCount는 메모리에서만 관리하고 flush/load 시 직렬화한다. ColumnStats의 minValue/maxValue는 Long으로 직렬화 (MVP에서는 수치 타입만 통계 수집).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.table.CatalogIndexTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/table/Catalog.kt \
       core/src/test/kotlin/gwanbase/table/CatalogIndexTest.kt
git commit -m "[Phase 7] Catalog: TableStats/ColumnStats 통계 저장"
```

---

### Task 9: IndexScanOperator

**Files:**
- Create: `core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt`
- Create: `core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.sql.Expression
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IndexScanOperatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `등가 조건으로 인덱스 스캔 시 매칭 행만 반환`() {
        val db = Database.open(tempDir.resolve("test.db"))
        db.use {
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
            db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
            db.executeSql("INSERT INTO users (id, name) VALUES (3, 'Charlie')")

            // 수동으로 B+Tree 인덱스 생성 (id 컬럼)
            val tableInfo = db.getTable("users")!!
            val tree = BPlusTree.createNew(db.bpm)
            val iter = db.scanTable("users")
            while (iter.hasNext()) {
                val (rid, tuple) = iter.next()
                val key = KeySerializer.serializeKey(
                    ExpressionEvaluator.getTupleValue(tuple, 0, DataType.INT32)!!,
                    DataType.INT32
                )
                tree.insert(key, KeySerializer.serializeRid(rid))
            }

            // IndexScan: id = 2
            val op = IndexScanOperator(
                database = db,
                tableName = "users",
                schema = tableInfo.schema,
                tree = tree,
                indexColumnIndex = 0,
                indexColumnType = DataType.INT32,
                lookupKeySupplier = { 2 },
                remainingFilter = null,
                session = null,
            )
            op.open()
            val result = op.next()
            result shouldBe notNull
            ExpressionEvaluator.getTupleValue(result!!, 1, DataType.VARCHAR) shouldBe "Bob"
            op.next() shouldBe null
            op.close()
        }
    }

    @Test
    fun `인덱스에 없는 키 조회 시 빈 결과`() {
        // id = 999 → 매칭 없음 → next() = null
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests "gwanbase.execution.IndexScanOperatorTest"`
Expected: FAIL

- [ ] **Step 3: IndexScanOperator 구현**

```kotlin
package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.sql.Expression
import gwanbase.table.*
import gwanbase.txn.DatabaseSession

/**
 * B+Tree 인덱스를 사용하여 등가 조건에 매칭하는 튜플을 스캔하는 연산자.
 *
 * lookupKeySupplier로 검색 키를 동적으로 받을 수 있어
 * Index Nested Loop Join에서 outer 튜플에 따라 키가 바뀌는 경우를 지원한다.
 */
class IndexScanOperator(
    private val database: Database,
    private val tableName: String,
    private val schema: Schema,
    private val tree: BPlusTree,
    private val indexColumnIndex: Int,
    private val indexColumnType: DataType,
    private val lookupKeySupplier: () -> Any?,
    private val remainingFilter: Expression?,
    private val session: DatabaseSession? = null,
) : Operator {

    private var matchedRids: Iterator<RID> = emptyList<RID>().iterator()

    override val outputSchema: Schema get() = schema

    override fun open() {
        val lookupValue = lookupKeySupplier() ?: run {
            matchedRids = emptyList<RID>().iterator()
            return
        }
        val keyBytes = KeySerializer.serializeKey(lookupValue, indexColumnType)
        val resultBytes = tree.search(keyBytes)
        if (resultBytes != null) {
            matchedRids = listOf(KeySerializer.deserializeRid(resultBytes)).iterator()
        } else {
            matchedRids = emptyList<RID>().iterator()
        }
    }

    override fun next(): Tuple? {
        while (matchedRids.hasNext()) {
            val rid = matchedRids.next()
            if (session != null) {
                session.acquireSharedLock(tableName, rid)
            }
            val tuple = database.getTuple(tableName, rid) ?: continue
            if (remainingFilter != null &&
                !ExpressionEvaluator.evaluateCondition(schema, tuple, remainingFilter)) {
                continue
            }
            return tuple
        }
        return null
    }

    override fun close() {
        matchedRids = emptyList<RID>().iterator()
    }
}
```

참고: MVP에서는 등가 조건만 지원하므로 `search()` 결과가 최대 1건이다. 같은 키에 여러 행이 있으면 (non-unique index) 범위 스캔이 필요하지만, MVP에서는 단일 결과로 시작한다. 이후 B+Tree `scan()`을 활용하여 복수 결과를 지원하도록 확장.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.execution.IndexScanOperatorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/execution/IndexScanOperator.kt \
       core/src/test/kotlin/gwanbase/execution/IndexScanOperatorTest.kt
git commit -m "[Phase 7] IndexScanOperator: 등가 조건 인덱스 스캔"
```

---

### Task 10: Database 인덱스 유지보수 + CREATE/DROP INDEX 실행

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/table/Database.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt` (생성)

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.sql

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase7IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `CREATE INDEX 실행 후 Catalog에 인덱스 등록`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
            db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
            val result = db.executeSql("CREATE INDEX idx_id ON users (id)")
            result.shouldBeInstanceOf<ExecuteResult.IndexCreated>()
            db.getCatalog().getIndex("idx_id") shouldBe notNull
        }
    }

    @Test
    fun `DROP INDEX 실행`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL)")
            db.executeSql("CREATE INDEX idx_id ON users (id)")
            val result = db.executeSql("DROP INDEX idx_id")
            result.shouldBeInstanceOf<ExecuteResult.IndexDropped>()
            db.getCatalog().getIndex("idx_id") shouldBe null
        }
    }

    @Test
    fun `INSERT 후 인덱스 자동 갱신`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("CREATE INDEX idx_id ON users (id)")
            db.executeSql("INSERT INTO users (id, name) VALUES (42, 'Test')")
            // 인덱스에서 직접 검색하여 확인
            val indexInfo = db.getCatalog().getIndex("idx_id")!!
            val tree = db.getIndexTree(indexInfo)
            val key = gwanbase.index.KeySerializer.serializeKey(42, gwanbase.table.DataType.INT32)
            tree.search(key) shouldBe notNull
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: ExecuteResult에 새 타입 추가**

`SqlExecutor.kt`의 `ExecuteResult`에:

```kotlin
data class IndexCreated(val indexName: String) : ExecuteResult()
data class IndexDropped(val indexName: String) : ExecuteResult()
data class Analyzed(val tableName: String, val rowCount: Long) : ExecuteResult()
data class Explained(val planText: String) : ExecuteResult()
```

- [ ] **Step 4: Database에 인덱스 유틸 메서드 추가**

```kotlin
/** 인덱스의 B+Tree를 반환한다. */
fun getIndexTree(indexInfo: IndexInfo): BPlusTree {
    return BPlusTree(bpm, indexInfo.rootPageId)
}

/** CREATE INDEX를 실행한다. 기존 데이터를 스캔하여 인덱스를 빌드한다. */
fun createIndex(indexName: String, tableName: String, columnName: String) {
    val tableInfo = getTable(tableName)
        ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
    val schema = tableInfo.schema
    val colIndex = schema.columnIndex(columnName)
    val colType = schema.column(colIndex).type

    val tree = BPlusTree.createNew(bpm)
    val iter = scanTable(tableName)
    while (iter.hasNext()) {
        val (rid, tuple) = iter.next()
        val value = ExpressionEvaluator.getTupleValue(tuple, colIndex, colType) ?: continue
        val key = KeySerializer.serializeKey(value, colType)
        tree.insert(key, KeySerializer.serializeRid(rid))
    }
    catalog.createIndex(indexName, tableName, columnName, tree.rootPageId)
}

/** 인덱스를 삭제한다. */
fun dropIndex(indexName: String) {
    catalog.dropIndex(indexName)
}
```

- [ ] **Step 5: insertTuple에 인덱스 유지보수 추가**

```kotlin
fun insertTuple(tableName: String, tuple: Tuple): RID {
    checkOpen()
    val info = catalog.getTable(tableName)
        ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
    val heapFile = HeapFile(bpm, info.heapFileFirstPageId)
    val rid = heapFile.insertTuple(tuple.serialize())
    // 인덱스 유지보수
    maintainIndexesOnInsert(tableName, info.schema, tuple, rid)
    // 행 수 갱신
    catalog.incrementRowCount(tableName)
    return rid
}

private fun maintainIndexesOnInsert(tableName: String, schema: Schema, tuple: Tuple, rid: RID) {
    for (indexInfo in catalog.getIndexesForTable(tableName)) {
        val colIndex = schema.columnIndex(indexInfo.columnName)
        val colType = schema.column(colIndex).type
        val value = ExpressionEvaluator.getTupleValue(tuple, colIndex, colType) ?: continue
        val key = KeySerializer.serializeKey(value, colType)
        val tree = BPlusTree(bpm, indexInfo.rootPageId)
        tree.insert(key, KeySerializer.serializeRid(rid))
    }
}
```

deleteTuple에도 동일하게 인덱스 엔트리 삭제 + `decrementRowCount` 추가.

- [ ] **Step 6: SqlExecutor — CREATE INDEX, DROP INDEX 실행 분기**

```kotlin
is Statement.CreateIndex -> {
    database.createIndex(stmt.indexName, stmt.tableName, stmt.columnName)
    ExecuteResult.IndexCreated(stmt.indexName)
}
is Statement.DropIndex -> {
    database.dropIndex(stmt.indexName)
    ExecuteResult.IndexDropped(stmt.indexName)
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.Phase7IntegrationTest"`
Expected: PASS

- [ ] **Step 8: 전체 회귀 확인**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/table/Database.kt \
       core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
       core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt
git commit -m "[Phase 7] CREATE/DROP INDEX 실행 + INSERT 인덱스 유지보수"
```

---

### Task 11: NestedLoopJoinOperator

**Files:**
- Create: `core/src/main/kotlin/gwanbase/execution/NestedLoopJoinOperator.kt`
- Create: `core/src/test/kotlin/gwanbase/execution/NestedLoopJoinOperatorTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.execution

import gwanbase.sql.BinaryOperator
import gwanbase.sql.Expression
import gwanbase.table.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NestedLoopJoinOperatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `INNER JOIN 기본 동작 — 매칭 행만 결합`() {
        val db = Database.open(tempDir.resolve("test.db"))
        db.use {
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            db.executeSql("CREATE TABLE orders (id INT NOT NULL, user_id INT NOT NULL, amount INT)")
            db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
            db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
            db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (10, 1, 100)")
            db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (11, 1, 200)")
            db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (12, 2, 300)")

            val outerSchema = db.getTable("users")!!.schema
            val innerSchema = db.getTable("orders")!!.schema
            val combinedSchema = Schema(outerSchema.columns + innerSchema.columns)

            // ON u.id = o.user_id
            // combinedSchema: [users.id(0), users.name(1), orders.id(2), orders.user_id(3), orders.amount(4)]
            val condition = Expression.BinaryOp(
                Expression.ColumnRef(null, "id"),       // index 0 in outer
                BinaryOperator.EQ,
                Expression.ColumnRef(null, "user_id"),  // index 3 in combined... 
                // 주의: 실제 구현에서는 outer/inner 인덱스 매핑이 필요
            )

            val outer = SeqScanOperator(db, "users")
            val inner = SeqScanOperator(db, "orders")
            val join = NestedLoopJoinOperator(outer, inner, condition, combinedSchema)

            join.open()
            val results = mutableListOf<Tuple>()
            var t = join.next()
            while (t != null) { results.add(t); t = join.next() }
            join.close()

            results.size shouldBe 3  // Alice-100, Alice-200, Bob-300
        }
    }

    @Test
    fun `한쪽 테이블이 비어 있을 때 빈 결과`() {
        // orders 테이블 비어있음 → 결과 0건
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: NestedLoopJoinOperator 구현**

```kotlin
package gwanbase.execution

import gwanbase.sql.Expression
import gwanbase.table.Schema
import gwanbase.table.Tuple

/**
 * Nested Loop Join 연산자.
 *
 * outer의 각 행에 대해 inner를 전체 스캔하며 조건을 만족하는 쌍을 결합한다.
 * inner는 outer 행이 바뀔 때마다 close() → open()으로 재시작한다.
 */
class NestedLoopJoinOperator(
    private val outer: Operator,
    private val inner: Operator,
    private val condition: Expression,
    private val combinedSchema: Schema,
) : Operator {

    private var currentOuter: Tuple? = null
    private var innerOpened = false

    override val outputSchema: Schema get() = combinedSchema

    override fun open() {
        outer.open()
        currentOuter = outer.next()
        if (currentOuter != null) {
            inner.open()
            innerOpened = true
        }
    }

    override fun next(): Tuple? {
        while (currentOuter != null) {
            while (true) {
                val innerTuple = inner.next() ?: break
                val combined = combineTuples(currentOuter!!, innerTuple)
                if (ExpressionEvaluator.evaluateCondition(combinedSchema, combined, condition)) {
                    return combined
                }
            }
            // inner 끝 → 다음 outer
            inner.close()
            currentOuter = outer.next()
            if (currentOuter != null) {
                inner.open()
            }
        }
        return null
    }

    override fun close() {
        if (innerOpened) inner.close()
        outer.close()
        innerOpened = false
    }

    /**
     * 두 튜플을 결합하여 combined 스키마의 새 튜플을 생성한다.
     */
    private fun combineTuples(outerTuple: Tuple, innerTuple: Tuple): Tuple {
        val outerSchema = outer.outputSchema
        val innerSchema = inner.outputSchema
        val values = Array<Any?>(combinedSchema.columnCount) { i ->
            if (i < outerSchema.columnCount) {
                ExpressionEvaluator.getTupleValue(
                    outerTuple, i, outerSchema.column(i).type
                )
            } else {
                val innerIdx = i - outerSchema.columnCount
                ExpressionEvaluator.getTupleValue(
                    innerTuple, innerIdx, innerSchema.column(innerIdx).type
                )
            }
        }
        return Tuple(combinedSchema, values)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.execution.NestedLoopJoinOperatorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/execution/NestedLoopJoinOperator.kt \
       core/src/test/kotlin/gwanbase/execution/NestedLoopJoinOperatorTest.kt
git commit -m "[Phase 7] NestedLoopJoinOperator 구현"
```

---

### Task 12: PlanNode 논리 계획 트리

**Files:**
- Create: `core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt`

PlanNode는 sealed class로 데이터만 담는다. 테스트가 필요한 동작이 없으므로 별도 테스트 없이 정의만 한다. CostEstimator와 PlanEnumerator 테스트에서 간접 검증된다.

- [ ] **Step 1: PlanNode sealed class 작성**

```kotlin
package gwanbase.optimizer

import gwanbase.sql.Expression
import gwanbase.sql.SelectItem

/**
 * 논리 실행 계획 트리.
 *
 * Optimizer가 생성하고, Planner가 물리 Operator로 변환한다.
 */
sealed class PlanNode {
    abstract val estimatedRows: Long
    abstract val estimatedCost: Double

    data class SeqScan(
        val tableName: String,
        val filter: Expression?,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    data class IndexScan(
        val tableName: String,
        val indexName: String,
        val indexColumnName: String,
        val lookupValue: Expression,
        val remainingFilter: Expression?,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    data class NestedLoopJoin(
        val outer: PlanNode,
        val inner: PlanNode,
        val condition: Expression,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    data class Sort(
        val child: PlanNode,
        val column: String,
        val ascending: Boolean,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    data class Limit(
        val child: PlanNode,
        val count: Int,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    data class Project(
        val child: PlanNode,
        val columns: List<SelectItem>,
        override val estimatedRows: Long,
        override val estimatedCost: Double,
    ) : PlanNode()

    /**
     * EXPLAIN용 텍스트 출력.
     */
    fun explain(indent: Int = 0): String {
        val prefix = "    ".repeat(indent)
        val line = when (this) {
            is SeqScan -> "${prefix}SeqScan(table=$tableName" +
                "${if (filter != null) ", filter=$filter" else ""})" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is IndexScan -> "${prefix}IndexScan(table=$tableName, index=$indexName, key=$lookupValue)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is NestedLoopJoin -> "${prefix}NestedLoopJoin(on=$condition)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Sort -> "${prefix}Sort(column=$column, asc=$ascending)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Limit -> "${prefix}Limit(count=$count)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
            is Project -> "${prefix}Project(columns=$columns)" +
                "  rows=$estimatedRows cost=${"%.1f".format(estimatedCost)}"
        }
        val children = when (this) {
            is SeqScan, is IndexScan -> emptyList()
            is NestedLoopJoin -> listOf(outer, inner)
            is Sort -> listOf(child)
            is Limit -> listOf(child)
            is Project -> listOf(child)
        }
        return if (children.isEmpty()) {
            line
        } else {
            line + "\n" + children.joinToString("\n") { it.explain(indent + 1) }
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/PlanNode.kt
git commit -m "[Phase 7] PlanNode 논리 계획 트리 정의"
```

---

### Task 13: StatisticsManager + ANALYZE 실행

**Files:**
- Create: `core/src/main/kotlin/gwanbase/optimizer/StatisticsManager.kt`
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
@Test
fun `ANALYZE 실행 후 통계 수집`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE users (id INT NOT NULL, age INT)")
        db.executeSql("INSERT INTO users (id, age) VALUES (1, 20)")
        db.executeSql("INSERT INTO users (id, age) VALUES (2, 30)")
        db.executeSql("INSERT INTO users (id, age) VALUES (3, 20)")
        db.executeSql("INSERT INTO users (id, age) VALUES (4, NULL)")
        val result = db.executeSql("ANALYZE users")
        result.shouldBeInstanceOf<ExecuteResult.Analyzed>()
        (result as ExecuteResult.Analyzed).rowCount shouldBe 4

        val stats = db.getCatalog().getColumnStats("users", "age")
        stats shouldBe notNull
        stats!!.distinctCount shouldBe 2  // 20, 30
        stats.nullCount shouldBe 1
        stats.minValue shouldBe 20L
        stats.maxValue shouldBe 30L
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: StatisticsManager 구현**

```kotlin
package gwanbase.optimizer

import gwanbase.execution.ExpressionEvaluator
import gwanbase.table.*

/**
 * 테이블 및 컬럼 통계를 관리한다.
 *
 * ANALYZE 실행 시 전체 스캔으로 컬럼 통계를 수집하고 Catalog에 저장한다.
 * 행 수는 DML 시 Catalog에서 자동 추적된다.
 */
class StatisticsManager(private val catalog: Catalog) {

    /**
     * 테이블을 전체 스캔하여 컬럼 통계를 수집한다.
     */
    fun analyze(database: Database, tableName: String): Long {
        val tableInfo = database.getTable(tableName)
            ?: throw IllegalArgumentException("테이블 '$tableName'이 존재하지 않는다")
        val schema = tableInfo.schema

        // 컬럼별 수집기 초기화
        val collectors = (0 until schema.columnCount).map { ColumnStatsCollector() }

        var rowCount = 0L
        val iter = database.scanTable(tableName)
        while (iter.hasNext()) {
            val (_, tuple) = iter.next()
            rowCount++
            for (i in 0 until schema.columnCount) {
                val value = ExpressionEvaluator.getTupleValue(tuple, i, schema.column(i).type)
                collectors[i].observe(value)
            }
        }

        // Catalog에 저장
        for (i in 0 until schema.columnCount) {
            val colName = schema.column(i).name
            catalog.updateColumnStats(tableName, colName, collectors[i].build())
        }

        return rowCount
    }
}

private class ColumnStatsCollector {
    private val distinctValues = mutableSetOf<Any>()
    private var minValue: Long? = null
    private var maxValue: Long? = null
    private var nullCount = 0L

    fun observe(value: Any?) {
        if (value == null) {
            nullCount++
            return
        }
        distinctValues.add(value)
        val numeric = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        if (numeric != null) {
            minValue = if (minValue == null) numeric else minOf(minValue!!, numeric)
            maxValue = if (maxValue == null) numeric else maxOf(maxValue!!, numeric)
        }
    }

    fun build(): ColumnStats = ColumnStats(
        distinctCount = distinctValues.size.toLong(),
        minValue = minValue,
        maxValue = maxValue,
        nullCount = nullCount,
    )
}
```

- [ ] **Step 4: SqlExecutor에 ANALYZE 실행 연결**

```kotlin
is Statement.Analyze -> {
    val statsManager = StatisticsManager(database.getCatalog())
    val rowCount = statsManager.analyze(database, stmt.tableName)
    ExecuteResult.Analyzed(stmt.tableName, rowCount)
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.Phase7IntegrationTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/StatisticsManager.kt \
       core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
       core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt
git commit -m "[Phase 7] StatisticsManager + ANALYZE SQL 실행"
```

---

### Task 14: CostEstimator — 비용 및 선택도 추정

**Files:**
- Create: `core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt`
- Create: `core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.optimizer

import gwanbase.table.Catalog
import gwanbase.table.ColumnStats
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CostEstimatorTest {

    @Test
    fun `등가 조건 선택도 — 통계 있을 때 1 나누기 distinctCount`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 1L, maxValue = 1000L, nullCount = 0)
        val sel = CostEstimator.equalitySelectivity(stats)
        sel shouldBe 0.01
    }

    @Test
    fun `등가 조건 선택도 — 통계 없을 때 기본값 0점1`() {
        val sel = CostEstimator.equalitySelectivity(null)
        sel shouldBe 0.1
    }

    @Test
    fun `범위 조건 선택도`() {
        val stats = ColumnStats(distinctCount = 100, minValue = 0L, maxValue = 100L, nullCount = 0)
        val sel = CostEstimator.rangeSelectivity(stats, 50L)  // col > 50 → 50/100 = 0.5
        sel shouldBe 0.5
    }

    @Test
    fun `SeqScan 비용 — 행 수에 비례`() {
        val cost = CostEstimator.seqScanCost(rowCount = 1000, pagesPerRow = 0.01)
        cost shouldBeGreaterThan 0.0
    }

    @Test
    fun `IndexScan 비용이 SeqScan보다 낮음 — 선택도 낮을 때`() {
        val seqCost = CostEstimator.seqScanCost(rowCount = 10000, pagesPerRow = 0.01)
        val idxCost = CostEstimator.indexScanCost(matchedRows = 10, treeHeight = 3)
        idxCost shouldBeLessThan seqCost
    }

    @Test
    fun `NLJ 비용 — outer 비용 + outer 행 수 곱하기 inner 비용`() {
        val cost = CostEstimator.nestedLoopJoinCost(
            outerCost = 10.0, outerRows = 100, innerCost = 5.0
        )
        cost shouldBe 510.0  // 10 + 100 * 5
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: CostEstimator 구현**

```kotlin
package gwanbase.optimizer

import gwanbase.table.ColumnStats
import kotlin.math.ln
import kotlin.math.max

/**
 * 실행 계획의 I/O 비용과 선택도를 추정한다.
 */
object CostEstimator {

    // 기본 선택도 (통계 없을 때)
    const val DEFAULT_EQUALITY_SELECTIVITY = 0.1
    const val DEFAULT_RANGE_SELECTIVITY = 0.33
    const val DEFAULT_OTHER_SELECTIVITY = 0.5

    fun equalitySelectivity(stats: ColumnStats?): Double {
        if (stats == null || stats.distinctCount <= 0) return DEFAULT_EQUALITY_SELECTIVITY
        return 1.0 / stats.distinctCount
    }

    fun rangeSelectivity(stats: ColumnStats?, threshold: Long): Double {
        if (stats == null || stats.minValue == null || stats.maxValue == null) {
            return DEFAULT_RANGE_SELECTIVITY
        }
        val min = stats.minValue as Long
        val max = stats.maxValue as Long
        if (max == min) return DEFAULT_RANGE_SELECTIVITY
        return max(0.0, (max - threshold).toDouble() / (max - min).toDouble())
    }

    fun seqScanCost(rowCount: Long, pagesPerRow: Double = 0.01): Double {
        return max(1.0, rowCount * pagesPerRow)
    }

    fun indexScanCost(matchedRows: Long, treeHeight: Int = 3): Double {
        return treeHeight.toDouble() + matchedRows.toDouble()
    }

    fun nestedLoopJoinCost(outerCost: Double, outerRows: Long, innerCost: Double): Double {
        return outerCost + outerRows * innerCost
    }

    fun sortCost(childCost: Double, rowCount: Long): Double {
        if (rowCount <= 1) return childCost
        return childCost + rowCount * ln(rowCount.toDouble())
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.CostEstimatorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/CostEstimator.kt \
       core/src/test/kotlin/gwanbase/optimizer/CostEstimatorTest.kt
git commit -m "[Phase 7] CostEstimator: 비용·선택도 추정"
```

---

### Task 15: PlanEnumerator — 접근 경로 및 조인 순서 선택

**Files:**
- Create: `core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt`
- Create: `core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.optimizer

import gwanbase.table.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlanEnumeratorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `인덱스 있는 등가 조건 — IndexScan 선택`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            repeat(100) { i ->
                db.executeSql("INSERT INTO users (id, name) VALUES ($i, 'user$i')")
            }
            db.executeSql("CREATE INDEX idx_id ON users (id)")
            db.executeSql("ANALYZE users")

            val enumerator = PlanEnumerator(db.getCatalog())
            val plan = enumerator.bestAccessPath("users",
                gwanbase.sql.Expression.BinaryOp(
                    gwanbase.sql.Expression.ColumnRef(null, "id"),
                    gwanbase.sql.BinaryOperator.EQ,
                    gwanbase.sql.Expression.IntLiteral(42),
                )
            )
            plan.shouldBeInstanceOf<PlanNode.IndexScan>()
        }
    }

    @Test
    fun `인덱스 없는 조건 — SeqScan 선택`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            repeat(100) { i ->
                db.executeSql("INSERT INTO users (id, name) VALUES ($i, 'user$i')")
            }
            db.executeSql("ANALYZE users")

            val enumerator = PlanEnumerator(db.getCatalog())
            val plan = enumerator.bestAccessPath("users",
                gwanbase.sql.Expression.BinaryOp(
                    gwanbase.sql.Expression.ColumnRef(null, "name"),
                    gwanbase.sql.BinaryOperator.EQ,
                    gwanbase.sql.Expression.StringLiteral("user42"),
                )
            )
            plan.shouldBeInstanceOf<PlanNode.SeqScan>()
        }
    }

    @Test
    fun `2테이블 조인 순서 — 작은 테이블이 outer`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE small_t (id INT NOT NULL)")
            db.executeSql("CREATE TABLE big_t (id INT NOT NULL, sid INT NOT NULL)")
            repeat(10) { db.executeSql("INSERT INTO small_t (id) VALUES ($it)") }
            repeat(1000) { db.executeSql("INSERT INTO big_t (id, sid) VALUES ($it, ${it % 10})") }
            db.executeSql("ANALYZE small_t")
            db.executeSql("ANALYZE big_t")

            val enumerator = PlanEnumerator(db.getCatalog())
            // 조인 계획 생성
            val plan = enumerator.bestJoinOrder(
                listOf("small_t", "big_t"),
                gwanbase.sql.Expression.BinaryOp(
                    gwanbase.sql.Expression.ColumnRef("small_t", "id"),
                    gwanbase.sql.BinaryOperator.EQ,
                    gwanbase.sql.Expression.ColumnRef("big_t", "sid"),
                ),
            )
            val join = plan as PlanNode.NestedLoopJoin
            // 작은 테이블이 outer
            (join.outer as PlanNode.SeqScan).tableName shouldBe "small_t"
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: PlanEnumerator 구현**

```kotlin
package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.*

/**
 * 실행 계획을 열거하고 최적을 선택한다.
 *
 * MVP 최적화 범위:
 * 1. 접근 경로 선택: SeqScan vs IndexScan
 * 2. 조인 순서: 2테이블 양방향 비교, 3+ 테이블 greedy
 * 3. 조건 push-down: 단일 테이블 조건을 스캔으로
 */
class PlanEnumerator(private val catalog: Catalog) {

    /**
     * 단일 테이블에 대한 최적 접근 경로를 선택한다.
     */
    fun bestAccessPath(tableName: String, filter: Expression?): PlanNode {
        val rowCount = catalog.getRowCount(tableName)
        val seqCost = CostEstimator.seqScanCost(rowCount)

        if (filter == null) {
            return PlanNode.SeqScan(tableName, null, rowCount, seqCost)
        }

        // 등가 조건에서 인덱스 매칭 확인
        val eqColumn = extractEqualityColumn(filter)
        if (eqColumn != null) {
            val indexes = catalog.getIndexesForTable(tableName)
            val matchingIndex = indexes.find { it.columnName == eqColumn.first }
            if (matchingIndex != null) {
                val colStats = catalog.getColumnStats(tableName, eqColumn.first)
                val selectivity = CostEstimator.equalitySelectivity(colStats)
                val matchedRows = maxOf(1, (rowCount * selectivity).toLong())
                val idxCost = CostEstimator.indexScanCost(matchedRows)
                if (idxCost < seqCost) {
                    return PlanNode.IndexScan(
                        tableName = tableName,
                        indexName = matchingIndex.name,
                        indexColumnName = matchingIndex.columnName,
                        lookupValue = eqColumn.second,
                        remainingFilter = removeCondition(filter, eqColumn.first),
                        estimatedRows = matchedRows,
                        estimatedCost = idxCost,
                    )
                }
            }
        }

        val filteredRows = estimateFilteredRows(tableName, filter, rowCount)
        return PlanNode.SeqScan(tableName, filter, filteredRows, seqCost)
    }

    /**
     * 다중 테이블 조인 순서를 결정한다.
     */
    fun bestJoinOrder(
        tables: List<String>,
        joinCondition: Expression,
    ): PlanNode {
        if (tables.size == 1) {
            return bestAccessPath(tables[0], null)
        }
        if (tables.size == 2) {
            // 양방향 비교
            val planAB = buildJoin(tables[0], tables[1], joinCondition)
            val planBA = buildJoin(tables[1], tables[0], joinCondition)
            return if (planAB.estimatedCost <= planBA.estimatedCost) planAB else planBA
        }
        // 3+ 테이블: greedy — 가장 작은 테이블부터
        val sorted = tables.sortedBy { catalog.getRowCount(it) }
        var plan = bestAccessPath(sorted[0], null)
        for (i in 1 until sorted.size) {
            val inner = bestAccessPath(sorted[i], null)
            val cost = CostEstimator.nestedLoopJoinCost(
                plan.estimatedCost, plan.estimatedRows, inner.estimatedCost
            )
            val rows = plan.estimatedRows * inner.estimatedRows / maxOf(1, inner.estimatedRows)
            plan = PlanNode.NestedLoopJoin(plan, inner, joinCondition, rows, cost)
        }
        return plan
    }

    private fun buildJoin(outerTable: String, innerTable: String, condition: Expression): PlanNode.NestedLoopJoin {
        val outer = bestAccessPath(outerTable, null)
        val inner = bestAccessPath(innerTable, null)
        val cost = CostEstimator.nestedLoopJoinCost(
            outer.estimatedCost, outer.estimatedRows, inner.estimatedCost
        )
        val rows = outer.estimatedRows * inner.estimatedRows / maxOf(1, maxOf(outer.estimatedRows, inner.estimatedRows))
        return PlanNode.NestedLoopJoin(outer, inner, condition, rows, cost)
    }

    /**
     * 등가 조건 (col = literal)에서 컬럼명과 리터럴 표현식을 추출한다.
     */
    private fun extractEqualityColumn(expr: Expression): Pair<String, Expression>? {
        if (expr !is Expression.BinaryOp || expr.op != BinaryOperator.EQ) return null
        // col = val
        if (expr.left is Expression.ColumnRef && expr.right !is Expression.ColumnRef) {
            return (expr.left as Expression.ColumnRef).name to expr.right
        }
        // val = col
        if (expr.right is Expression.ColumnRef && expr.left !is Expression.ColumnRef) {
            return (expr.right as Expression.ColumnRef).name to expr.left
        }
        return null
    }

    /**
     * AND 조건에서 특정 컬럼의 조건을 제거하고 나머지를 반환한다.
     */
    private fun removeCondition(expr: Expression, columnName: String): Expression? {
        // 단일 등가 조건이면 나머지 없음
        val eq = extractEqualityColumn(expr)
        if (eq != null && eq.first == columnName) return null
        // AND 조건이면 해당 컬럼 조건만 제거
        if (expr is Expression.BinaryOp && expr.op == BinaryOperator.AND) {
            val leftEq = extractEqualityColumn(expr.left)
            val rightEq = extractEqualityColumn(expr.right)
            if (leftEq != null && leftEq.first == columnName) return expr.right
            if (rightEq != null && rightEq.first == columnName) return expr.left
        }
        return expr
    }

    private fun estimateFilteredRows(tableName: String, filter: Expression, totalRows: Long): Long {
        val eq = extractEqualityColumn(filter)
        if (eq != null) {
            val stats = catalog.getColumnStats(tableName, eq.first)
            val sel = CostEstimator.equalitySelectivity(stats)
            return maxOf(1, (totalRows * sel).toLong())
        }
        return maxOf(1, (totalRows * CostEstimator.DEFAULT_OTHER_SELECTIVITY).toLong())
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.PlanEnumeratorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/PlanEnumerator.kt \
       core/src/test/kotlin/gwanbase/optimizer/PlanEnumeratorTest.kt
git commit -m "[Phase 7] PlanEnumerator: 접근 경로·조인 순서 선택"
```

---

### Task 16: Optimizer 진입점 + Planner 리팩토링

**Files:**
- Create: `core/src/main/kotlin/gwanbase/optimizer/Optimizer.kt`
- Modify: `core/src/main/kotlin/gwanbase/execution/Planner.kt`
- Create: `core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.Database
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OptimizerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `단일 테이블 SELECT — SeqScan 계획 생성`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            val optimizer = Optimizer(db.getCatalog())
            val stmt = parseSelect("SELECT * FROM users")
            val plan = optimizer.optimize(stmt)
            // Project → SeqScan
            plan.shouldBeInstanceOf<PlanNode.Project>()
            (plan as PlanNode.Project).child.shouldBeInstanceOf<PlanNode.SeqScan>()
        }
    }

    @Test
    fun `인덱스 있는 WHERE — IndexScan 계획 생성`() {
        Database.open(tempDir.resolve("test.db")).use { db ->
            db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
            repeat(100) { db.executeSql("INSERT INTO users (id, name) VALUES ($it, 'u$it')") }
            db.executeSql("CREATE INDEX idx_id ON users (id)")
            db.executeSql("ANALYZE users")

            val optimizer = Optimizer(db.getCatalog())
            val stmt = parseSelect("SELECT * FROM users WHERE id = 42")
            val plan = optimizer.optimize(stmt)
            // plan 트리 내에 IndexScan이 있어야 함
            findNode<PlanNode.IndexScan>(plan) shouldBe true
        }
    }

    private fun parseSelect(sql: String): Statement.Select {
        val tokens = Lexer(sql).tokenize()
        return Parser(tokens).parse() as Statement.Select
    }

    private inline fun <reified T : PlanNode> findNode(plan: PlanNode): Boolean {
        if (plan is T) return true
        return when (plan) {
            is PlanNode.Project -> findNode<T>(plan.child)
            is PlanNode.Sort -> findNode<T>(plan.child)
            is PlanNode.Limit -> findNode<T>(plan.child)
            is PlanNode.NestedLoopJoin -> findNode<T>(plan.outer) || findNode<T>(plan.inner)
            else -> false
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: Optimizer 구현**

```kotlin
package gwanbase.optimizer

import gwanbase.sql.*
import gwanbase.table.Catalog

/**
 * AST를 받아 최적의 논리 실행 계획(PlanNode)을 반환한다.
 *
 * SELECT 문에 대해:
 * 1. WHERE 조건을 분석하여 push-down 가능한 조건 분리
 * 2. 각 테이블에 대해 최적 접근 경로 선택 (SeqScan vs IndexScan)
 * 3. JOIN이 있으면 최적 조인 순서 결정
 * 4. Sort, Limit, Project 추가
 */
class Optimizer(private val catalog: Catalog) {

    private val enumerator = PlanEnumerator(catalog)

    fun optimize(stmt: Statement.Select): PlanNode {
        // FROM 절에서 테이블 수집
        val tables = collectTables(stmt.from)

        var plan: PlanNode = if (tables.size == 1) {
            val tableName = tables[0].first  // 실제 테이블명
            enumerator.bestAccessPath(tableName, stmt.where)
        } else {
            // 다중 테이블: 단일 테이블 조건 push-down + 조인
            val joinCondition = extractJoinCondition(stmt.from)
            val tableNames = tables.map { it.first }
            enumerator.bestJoinOrder(tableNames, joinCondition ?: stmt.where!!)
        }

        // ORDER BY
        if (stmt.orderBy != null) {
            val sortCost = CostEstimator.sortCost(plan.estimatedCost, plan.estimatedRows)
            plan = PlanNode.Sort(plan, stmt.orderBy.column, stmt.orderBy.ascending, plan.estimatedRows, sortCost)
        }

        // LIMIT
        if (stmt.limit != null) {
            val limitRows = minOf(plan.estimatedRows, stmt.limit.toLong())
            plan = PlanNode.Limit(plan, stmt.limit, limitRows, plan.estimatedCost)
        }

        // Project (항상 마지막)
        plan = PlanNode.Project(plan, stmt.columns, plan.estimatedRows, plan.estimatedCost)

        return plan
    }

    private fun collectTables(from: FromClause): List<Pair<String, String?>> {
        return when (from) {
            is FromClause.Table -> listOf(from.tableName to from.alias)
            is FromClause.Join -> collectTables(from.left) + collectTables(from.right)
        }
    }

    private fun extractJoinCondition(from: FromClause): Expression? {
        return when (from) {
            is FromClause.Table -> null
            is FromClause.Join -> from.condition
        }
    }
}
```

- [ ] **Step 4: Planner 리팩토링 — PlanNode → Operator 변환**

```kotlin
package gwanbase.execution

import gwanbase.index.BPlusTree
import gwanbase.index.KeySerializer
import gwanbase.optimizer.PlanNode
import gwanbase.sql.*
import gwanbase.table.Database
import gwanbase.table.Schema
import gwanbase.txn.DatabaseSession

/**
 * PlanNode(논리 계획)를 Operator(물리 연산자)로 변환한다.
 */
class Planner(
    private val database: Database,
    private val session: DatabaseSession? = null,
) {

    /**
     * PlanNode를 재귀적으로 Operator 트리로 변환한다.
     */
    fun toOperator(plan: PlanNode): Operator {
        return when (plan) {
            is PlanNode.SeqScan -> {
                val scan = SeqScanOperator(database, plan.tableName, session)
                if (plan.filter != null) {
                    FilterOperator(scan, plan.filter)
                } else {
                    scan
                }
            }
            is PlanNode.IndexScan -> {
                val tableInfo = database.getTable(plan.tableName)!!
                val indexInfo = database.getCatalog().getIndex(plan.indexName)!!
                val tree = BPlusTree(database.bpm, indexInfo.rootPageId)
                val schema = tableInfo.schema
                val colIndex = schema.columnIndex(plan.indexColumnName)
                val colType = schema.column(colIndex).type

                val lookupSupplier: () -> Any? = {
                    evaluateLiteralExpression(plan.lookupValue)
                }

                val scan = IndexScanOperator(
                    database, plan.tableName, schema, tree,
                    colIndex, colType, lookupSupplier,
                    plan.remainingFilter, session,
                )
                scan
            }
            is PlanNode.NestedLoopJoin -> {
                val outer = toOperator(plan.outer)
                val inner = toOperator(plan.inner)
                val combinedSchema = Schema(
                    outer.outputSchema.columns + inner.outputSchema.columns
                )
                NestedLoopJoinOperator(outer, inner, plan.condition, combinedSchema)
            }
            is PlanNode.Sort -> SortOperator(
                toOperator(plan.child), plan.column, plan.ascending
            )
            is PlanNode.Limit -> LimitOperator(
                toOperator(plan.child), plan.count
            )
            is PlanNode.Project -> ProjectOperator(
                toOperator(plan.child), plan.columns
            )
        }
    }

    /**
     * 기존 호환용: AST → Operator 직접 변환 (Optimizer 미사용 경로).
     */
    fun planSelect(stmt: Statement.Select): Operator {
        val tableName = (stmt.from as FromClause.Table).tableName
        var op: Operator = SeqScanOperator(database, tableName, session)
        if (stmt.where != null) op = FilterOperator(op, stmt.where)
        if (stmt.orderBy != null) op = SortOperator(op, stmt.orderBy.column, stmt.orderBy.ascending)
        if (stmt.limit != null) op = LimitOperator(op, stmt.limit)
        op = ProjectOperator(op, stmt.columns)
        return op
    }

    private fun evaluateLiteralExpression(expr: Expression): Any? {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.NullLiteral -> null
            else -> error("인덱스 lookup 키로 사용할 수 없는 표현식: $expr")
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.optimizer.OptimizerIntegrationTest"`
Expected: PASS

- [ ] **Step 6: 전체 회귀 확인**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/optimizer/Optimizer.kt \
       core/src/main/kotlin/gwanbase/execution/Planner.kt \
       core/src/test/kotlin/gwanbase/optimizer/OptimizerIntegrationTest.kt
git commit -m "[Phase 7] Optimizer 진입점 + Planner PlanNode→Operator 변환"
```

---

### Task 17: SqlExecutor 통합 + EXPLAIN

**Files:**
- Modify: `core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt`
- Modify: `core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
@Test
fun `EXPLAIN SELECT — 실행 계획 텍스트 반환`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
        val result = db.executeSql("EXPLAIN SELECT * FROM users")
        result.shouldBeInstanceOf<ExecuteResult.Explained>()
        val text = (result as ExecuteResult.Explained).planText
        text shouldContain "SeqScan"
    }
}

@Test
fun `EXPLAIN — 인덱스 있을 때 IndexScan 표시`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
        repeat(100) { db.executeSql("INSERT INTO users (id, name) VALUES ($it, 'u$it')") }
        db.executeSql("CREATE INDEX idx_id ON users (id)")
        db.executeSql("ANALYZE users")
        val result = db.executeSql("EXPLAIN SELECT * FROM users WHERE id = 42")
        (result as ExecuteResult.Explained).planText shouldContain "IndexScan"
    }
}

@Test
fun `Optimizer 경유 SELECT 실행 — 기존 결과와 동일`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
        db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
        db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
        val result = db.executeSql("SELECT name FROM users WHERE id = 1") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe "Alice"
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: SqlExecutor에 Optimizer 통합**

`SqlExecutor.kt`의 `executeSelect()`를 Optimizer 경유로 변경:

```kotlin
private fun executeSelect(stmt: Statement.Select): ExecuteResult.Selected {
    val optimizer = Optimizer(database.getCatalog())
    val plan = optimizer.optimize(stmt)
    val op = planner.toOperator(plan)

    op.open()
    try {
        val outputSchema = op.outputSchema
        val columns = (0 until outputSchema.columnCount).map { outputSchema.column(it).name }
        val rows = mutableListOf<List<Any?>>()
        var current = op.next()
        while (current != null) {
            val t = current
            val row = (0 until outputSchema.columnCount).map { i ->
                ExpressionEvaluator.getTupleValue(t, i, outputSchema.column(i).type)
            }
            rows.add(row)
            current = op.next()
        }
        return ExecuteResult.Selected(columns, rows)
    } finally {
        op.close()
    }
}
```

- [ ] **Step 4: EXPLAIN 실행 구현**

```kotlin
is Statement.Explain -> {
    val inner = stmt.statement
    if (inner !is Statement.Select) {
        throw BindException("EXPLAIN은 SELECT 문만 지원한다")
    }
    val optimizer = Optimizer(database.getCatalog())
    val plan = optimizer.optimize(inner)
    ExecuteResult.Explained(plan.explain())
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "gwanbase.sql.Phase7IntegrationTest"`
Expected: PASS

- [ ] **Step 6: 전체 회귀 확인**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/kotlin/gwanbase/sql/SqlExecutor.kt \
       core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt
git commit -m "[Phase 7] SqlExecutor Optimizer 통합 + EXPLAIN 실행"
```

---

### Task 18: E2E 통합 테스트

**Files:**
- Modify: `core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt`

이전 태스크에서 기본 테스트를 작성했지만, 여기서 복합적인 E2E 시나리오를 추가한다.

- [ ] **Step 1: JOIN E2E 테스트 작성**

```kotlin
@Test
fun `두 테이블 INNER JOIN SQL 실행`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))")
        db.executeSql("CREATE TABLE orders (id INT NOT NULL, user_id INT NOT NULL, amount INT NOT NULL)")
        db.executeSql("INSERT INTO users (id, name) VALUES (1, 'Alice')")
        db.executeSql("INSERT INTO users (id, name) VALUES (2, 'Bob')")
        db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (10, 1, 100)")
        db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (11, 1, 200)")
        db.executeSql("INSERT INTO orders (id, user_id, amount) VALUES (12, 2, 300)")

        val result = db.executeSql(
            "SELECT * FROM users JOIN orders ON users.id = orders.user_id"
        ) as ExecuteResult.Selected
        result.rows.size shouldBe 3
    }
}

@Test
fun `인덱스 유지보수 — INSERT 후 인덱스 정합성`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE t (id INT NOT NULL, val VARCHAR(50))")
        db.executeSql("CREATE INDEX idx_id ON t (id)")
        repeat(50) { db.executeSql("INSERT INTO t (id, val) VALUES ($it, 'v$it')") }

        // 인덱스 경유 조회
        db.executeSql("ANALYZE t")
        val result = db.executeSql("SELECT val FROM t WHERE id = 25") as ExecuteResult.Selected
        result.rows.size shouldBe 1
        result.rows[0][0] shouldBe "v25"
    }
}

@Test
fun `DELETE 후 인덱스 정합성`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE t (id INT NOT NULL, val VARCHAR(50))")
        db.executeSql("CREATE INDEX idx_id ON t (id)")
        db.executeSql("INSERT INTO t (id, val) VALUES (1, 'a')")
        db.executeSql("INSERT INTO t (id, val) VALUES (2, 'b')")
        db.executeSql("DELETE FROM t WHERE id = 1")

        db.executeSql("ANALYZE t")
        val result = db.executeSql("SELECT val FROM t WHERE id = 1") as ExecuteResult.Selected
        result.rows.size shouldBe 0
    }
}

@Test
fun `ANALYZE 후 옵티마이저 계획 변경 확인`() {
    Database.open(tempDir.resolve("test.db")).use { db ->
        db.executeSql("CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
        repeat(100) { db.executeSql("INSERT INTO t (id, name) VALUES ($it, 'n$it')") }
        db.executeSql("CREATE INDEX idx_id ON t (id)")

        // ANALYZE 전 — 통계 없음
        val before = db.executeSql("EXPLAIN SELECT * FROM t WHERE id = 50") as ExecuteResult.Explained

        // ANALYZE 후
        db.executeSql("ANALYZE t")
        val after = db.executeSql("EXPLAIN SELECT * FROM t WHERE id = 50") as ExecuteResult.Explained

        after.planText shouldContain "IndexScan"
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `./gradlew :core:test --tests "gwanbase.sql.Phase7IntegrationTest"`
Expected: PASS

- [ ] **Step 3: 전체 테스트 최종 확인**

Run: `./gradlew :core:test`
Expected: 모든 테스트 PASS (Phase 1–7)

- [ ] **Step 4: 커밋**

```bash
git add core/src/test/kotlin/gwanbase/sql/Phase7IntegrationTest.kt
git commit -m "[Phase 7] E2E 통합 테스트: JOIN, 인덱스 유지보수, ANALYZE, EXPLAIN"
```

---

### Task 19: CLAUDE.md 업데이트 + Phase 7 태그

**Files:**
- Modify: `CLAUDE.md`
- Modify: `HANDOFF.md`

- [ ] **Step 1: CLAUDE.md 로드맵 및 컴포넌트 테이블 업데이트**

로드맵에서 Phase 7을 완료로 표시, Phase 7 컴포넌트 테이블 추가, Phase 7 설계 가이드 추가.

- [ ] **Step 2: HANDOFF.md Phase 7 완료로 갱신**

- [ ] **Step 3: 커밋 및 태그**

```bash
git add CLAUDE.md HANDOFF.md
git commit -m "[Phase 7] CLAUDE.md 로드맵·컴포넌트·설계 가이드 업데이트"
git tag v0.7-optimizer
```
