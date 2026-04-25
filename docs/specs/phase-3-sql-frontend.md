# Phase 3: SQL Frontend

## 목표

SQL 텍스트를 받아 파싱하고, Catalog과 대조하여 검증한 뒤, Phase 2의 Database API를
호출하여 결과를 반환하는 SQL 프론트엔드를 구현한다.

Phase 4(Volcano Model)가 아직 없으므로 간단한 인터프리터 방식으로 직접 실행한다.
Phase 4에서 Volcano 모델을 도입하면 실행부만 교체하고, Lexer/Parser/Binder는
그대로 재사용한다.

## 비기능 요구사항

- SQL 문법 오류 시 행 번호·위치 정보를 포함한 에러 메시지
- 존재하지 않는 테이블/컬럼 참조 시 Binder 단계에서 명확한 에러
- 파싱과 실행이 분리되어 Phase 4 전환 시 실행부만 교체 가능
- 한 개의 SQL 문(statement)을 처리하는 단위로 동작 (멀티 스테이트먼트는 Phase 3 범위 밖)

## 지원 SQL 문법

### DDL
```sql
CREATE TABLE table_name (
    column_name data_type [NOT NULL],
    ...
);
DROP TABLE table_name;
```

### DML
```sql
INSERT INTO table_name (col1, col2, ...) VALUES (val1, val2, ...);

SELECT col1, col2 | * FROM table_name
    [WHERE condition]
    [ORDER BY col [ASC|DESC]]
    [LIMIT n];

UPDATE table_name SET col1 = val1, col2 = val2, ...
    [WHERE condition];

DELETE FROM table_name [WHERE condition];
```

### 지원 데이터 타입 (SQL → DataType 매핑)
| SQL 키워드 | DataType |
|---|---|
| `BOOLEAN` | BOOLEAN |
| `INT`, `INTEGER` | INT32 |
| `BIGINT` | INT64 |
| `DOUBLE`, `FLOAT` | FLOAT64 |
| `TIMESTAMP` | TIMESTAMP |
| `VARCHAR(n)` | VARCHAR(maxLength=n) |

### 지원 표현식
- 리터럴: 정수, 실수, 문자열(`'...'`), `TRUE`, `FALSE`, `NULL`
- 컬럼 참조: `column_name`
- 비교: `=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`
- 논리: `AND`, `OR`, `NOT`
- 산술: `+`, `-`, `*`, `/` (SELECT 내 표현식, WHERE 조건)
- NULL 검사: `IS NULL`, `IS NOT NULL`

### 미지원 (Phase 3 범위 밖)
- JOIN, 서브쿼리, GROUP BY, HAVING, 집계 함수
- ALTER TABLE, 인덱스 DDL
- 트랜잭션 (BEGIN, COMMIT, ROLLBACK)
- 타입 캐스팅, CASE 표현식

## 아키텍처 개요

```
SQL 텍스트
  │
  ▼
Lexer  ─────→  Token 스트림
  │
  ▼
Parser ─────→  AST (unresolved)
  │
  ▼
Binder ─────→  AST 검증 (Catalog 대조, 타입 검사)
  │
  ▼
Executor ───→  Database API 호출 → 결과 반환
```

## 인터페이스 설계 (public API)

### 패키지: `gwanbase.sql`

#### Token & Lexer

```kotlin
enum class TokenType {
    // 키워드
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, DROP, TABLE, ORDER, BY, ASC, DESC, LIMIT,
    AND, OR, NOT, NULL, TRUE, FALSE,
    IS, INT, INTEGER, BIGINT, BOOLEAN, DOUBLE, FLOAT, VARCHAR, TIMESTAMP,

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

data class Token(
    val type: TokenType,
    val literal: String,   // 원본 텍스트
    val position: Int,     // SQL 텍스트 내 시작 위치
)

class Lexer(private val source: String) {
    fun tokenize(): List<Token>
}
```

#### AST

```kotlin
/** SQL 문 */
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

data class ColumnDef(
    val name: String,
    val dataType: SqlDataType,
    val nullable: Boolean = true,  // 기본은 nullable
)

sealed class SqlDataType {
    data object BooleanType : SqlDataType()
    data object IntType : SqlDataType()
    data object BigIntType : SqlDataType()
    data object DoubleType : SqlDataType()
    data object TimestampType : SqlDataType()
    data class VarcharType(val maxLength: Int) : SqlDataType()
}

sealed class SelectItem {
    data object Star : SelectItem()
    data class ExprItem(val expr: Expression) : SelectItem()
}

data class OrderByClause(val column: String, val ascending: Boolean = true)

data class Assignment(val column: String, val value: Expression)

/** 표현식 */
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

enum class BinaryOperator {
    ADD, SUB, MUL, DIV,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR,
}

enum class UnaryOperator {
    NEGATE, NOT,
}
```

#### Parser

```kotlin
class Parser(private val tokens: List<Token>) {
    fun parse(): Statement
}
```

파싱 오류 시 `ParseException` (위치 정보 포함).

#### Binder

```kotlin
class Binder(private val catalog: Catalog) {
    fun bind(statement: Statement): Statement  // 검증만 수행, AST는 그대로 반환
}
```

바인딩 오류 시 `BindException`.

#### Executor

```kotlin
class SqlExecutor(private val database: Database) {
    fun execute(sql: String): ExecuteResult
}

sealed class ExecuteResult {
    data class Created(val tableName: String) : ExecuteResult()
    data class Dropped(val tableName: String) : ExecuteResult()
    data class Inserted(val rid: RID) : ExecuteResult()
    data class Selected(val columns: List<String>, val rows: List<List<Any?>>) : ExecuteResult()
    data class Updated(val count: Int) : ExecuteResult()
    data class Deleted(val count: Int) : ExecuteResult()
}
```

## 내부 설계

### Lexer

단일 패스 문자 스캔. `source` 문자열을 position으로 순회하며 토큰을 생성한다.

- 공백·줄바꿈은 스킵
- `--` 뒤는 줄 끝까지 주석으로 스킵
- 키워드 vs 식별자: 알파벳 시작 토큰을 읽은 뒤 키워드 테이블에서 대소문자 무시 매칭
- 문자열 리터럴: 작은따옴표(`'`) 감싸기, 이스케이프는 `''` (SQL 표준)
- 숫자: 정수 우선, `.`이 있으면 실수
- `<>` → NEQ, `!=` → NEQ, `<=` → LTE, `>=` → GTE

### Parser

Recursive descent. 각 `parseXxx()` 메서드가 하나의 문법 규칙 담당.

표현식 파싱은 **Pratt parsing** (precedence climbing):

| 우선순위 | 연산자 |
|---|---|
| 1 (lowest) | OR |
| 2 | AND |
| 3 | NOT (단항) |
| 4 | =, !=, <>, <, >, <=, >=, IS NULL, IS NOT NULL |
| 5 | +, - |
| 6 | *, / |
| 7 | - (단항 부정) |

### Binder

Catalog과 대조하여 다음을 검증한다:

1. **테이블 존재**: SELECT/INSERT/UPDATE/DELETE에서 참조하는 테이블이 Catalog에 있는지
2. **컬럼 존재**: WHERE, SELECT 목록, ORDER BY, SET, INSERT 컬럼 목록이 스키마에 있는지
3. **타입 호환**: INSERT VALUES의 리터럴 타입이 대상 컬럼 타입과 호환되는지
4. **NOT NULL 검증**: NOT NULL 컬럼에 NULL 리터럴 삽입 시 에러
5. **CREATE TABLE 중복**: 이미 존재하는 테이블명 에러
6. **DROP TABLE 존재**: 존재하지 않는 테이블 삭제 시 에러

### Executor (Simple Interpreter)

Phase 4 이전의 임시 실행기. Database API를 직접 호출한다.

- **CREATE TABLE**: `ColumnDef` → `Column`/`Schema` 변환 후 `database.createTable()`
- **DROP TABLE**: `database.dropTable()` (Database에 메서드 추가 필요)
- **INSERT**: 리터럴 평가 → `Tuple` 생성 → `database.insertTuple()`
- **SELECT**: `database.scanTable()` → WHERE 필터링 → 컬럼 프로젝션 → ORDER BY 정렬 → LIMIT 적용 → 결과 반환
- **UPDATE**: `database.scanTable()` → WHERE 필터링 → 값 변경 → `database.updateTuple()` (추가 필요)
- **DELETE**: `database.scanTable()` → WHERE 필터링 → `database.deleteTuple()`

## Database API 확장

Phase 3를 위해 `Database` 클래스에 추가할 메서드:

```kotlin
// 테이블 삭제
fun dropTable(name: String): Boolean

// 튜플 업데이트 (삭제 후 재삽입)
fun updateTuple(tableName: String, rid: RID, tuple: Tuple): RID

// Catalog 접근 (Binder용)
fun getCatalog(): Catalog
```

## 제약 사항 및 트레이드오프

| 결정 | 이유 | 대안 |
|---|---|---|
| Pratt parsing | 연산자 우선순위를 깔끔하게 처리. 확장 시 우선순위 테이블만 수정 | Shunting-yard: 후위 변환이 목적이라 AST에는 Pratt이 적합 |
| AST에 별도 Bound 타입 없음 | 지원 문법이 작아 검증만으로 충분. Phase 4에서 필요 시 도입 | Bound AST: 컬럼을 인덱스로 치환한 별도 트리 |
| Executor가 전체 스캔 후 인메모리 처리 | Phase 4 Volcano 모델 전까지의 임시 구현 | 불필요 — Phase 4에서 교체 |
| SELECT 결과를 List에 전부 적재 | 메모리 비효율이지만 Phase 3 범위에서 충분 | Iterator 기반 반환은 Phase 4에서 |
| 단일 문장 처리 | 세미콜론 구분 멀티 스테이트먼트는 복잡도 대비 이점 적음 | 추후 필요 시 루프 추가 |

## 테스트 시나리오

### Lexer
- 각 토큰 타입별 단독 토큰화
- 키워드 대소문자 무시 (SELECT, select, Select 모두 동일)
- 문자열 리터럴 이스케이프 (`'it''s'` → `it's`)
- 숫자 리터럴 (정수, 실수, 음수는 MINUS + 정수)
- 연산자 (`<>`, `!=`, `<=`, `>=`)
- 주석 스킵 (`-- comment`)
- 위치 추적 (position이 정확한지)
- 잘못된 문자 에러 (예: `@`, `#`)
- 빈 입력 → EOF만 반환

### Parser
- CREATE TABLE: 단일 컬럼, 다중 컬럼, NOT NULL, VARCHAR(n)
- DROP TABLE
- INSERT: 모든 타입 리터럴, NULL
- SELECT: *, 컬럼 목록, WHERE, ORDER BY, LIMIT, 조합
- UPDATE: 단일/다중 SET, WHERE
- DELETE: WHERE 있음/없음
- 표현식 우선순위: `a + b * c` → `a + (b * c)`
- 괄호 표현식: `(a + b) * c`
- 복합 WHERE: `a > 1 AND b = 'x' OR c IS NULL`
- 에러: 불완전한 문장, 잘못된 토큰 위치

### Binder
- 존재하지 않는 테이블 참조 에러
- 존재하지 않는 컬럼 참조 에러
- INSERT 타입 불일치 에러
- NOT NULL 컬럼에 NULL 삽입 에러
- CREATE TABLE 중복 테이블명 에러
- DROP TABLE 존재하지 않는 테이블 에러
- 정상 바인딩 통과 케이스

### Executor (통합 테스트)
- CREATE TABLE → INSERT → SELECT 전체 흐름
- SELECT WHERE 필터링 (비교, AND, OR, IS NULL)
- SELECT ORDER BY (ASC, DESC)
- SELECT LIMIT
- UPDATE + 검증 SELECT
- DELETE + 검증 SELECT
- DROP TABLE 후 SELECT 에러
- 빈 테이블 SELECT → 빈 결과
- 다양한 DataType 라운드트립 (INSERT → SELECT)

## 구현 순서 (TDD)

1. **Token, TokenType** — 데이터 클래스 정의
2. **Lexer** — 토큰화 + 테스트
3. **AST** — sealed class 정의 (테스트 없음, 데이터 클래스)
4. **Parser** — 문장별 파싱 + 테스트
5. **Database API 확장** — `dropTable`, `updateTuple`, `getCatalog` 추가 + 테스트
6. **Binder** — Catalog 검증 + 테스트
7. **Executor** — 통합 실행 + 테스트

## 참고 자료

- *Crafting Interpreters* (Robert Nystrom) — Pratt parsing 설명이 가장 명쾌함
- SQLite tokenizer (`tokenize.c`) — 단일 패스 토크나이저 참고
- PostgreSQL parser (`scan.l`, `gram.y`) — 문법 규칙 참고 (규모는 훨씬 큼)
- Pratt Parsing 원논문: Vaughan Pratt, "Top Down Operator Precedence" (1973)
