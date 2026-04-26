# Phase 4: Query Execution Engine

## 목표

Phase 3의 Simple Interpreter(전체 스캔 → 인메모리 처리)를 **Volcano (Iterator) 모델**로
교체한다. 각 연산자가 `open() → next() → close()` 프로토콜을 따르며, 연산자 트리를
조합하여 질의를 실행한다.

Phase 3의 Lexer/Parser/Binder는 그대로 재사용하고, **실행부만 교체**한다.

## 비기능 요구사항

- 연산자 인터페이스가 확장 가능해야 한다 (Phase 7에서 JOIN, 집계 연산자 추가 예정)
- 한 번에 한 튜플씩 처리(pull 모델)하여 메모리 사용을 제한한다
- 기존 Phase 3 통합 테스트가 Phase 4 구현에서도 전부 통과해야 한다

## 아키텍처 개요

```
SQL 텍스트
  │
  ▼
Lexer → Parser → Binder          (Phase 3, 변경 없음)
  │
  ▼
Planner                           (Phase 4 신규)
  │  AST → 연산자 트리 변환
  ▼
Operator Tree                     (Phase 4 신규)
  │  open() → next() 반복 → close()
  ▼
ExecuteResult                     (기존 타입 재사용)
```

### Volcano (Iterator) 모델

Pull 기반 실행 모델. 루트 연산자의 `next()`를 호출하면 자식 연산자에게 재귀적으로
`next()`를 전파하여 한 번에 한 튜플씩 꺼내온다.

**왜 Volcano인가?**
- 교육용 DB에서 가장 널리 쓰이는 실행 모델 (bustub, SimpleDB, toydb 모두 사용)
- 연산자 합성이 직관적 — 새 연산자를 추가할 때 인터페이스만 구현하면 됨
- 메모리 효율 — 전체 결과를 리스트에 적재하지 않고 한 튜플씩 처리
- 대안: Materialization 모델(한 연산자가 전체 결과를 생성 후 다음 연산자에 전달)은
  구현이 단순하지만, 중간 결과가 커지면 메모리 문제 발생. Vectorized 모델(배치 처리)은
  SIMD 최적화에 유리하지만 학습 단계에서는 과도한 복잡도

## 인터페이스 설계 (public API)

### 패키지: `gwanbase.execution`

#### Operator 인터페이스

```kotlin
/**
 * Volcano 모델의 연산자 인터페이스.
 *
 * 사용 프로토콜: open() → next() 반복 (null이면 종료) → close()
 */
interface Operator {
    /** 연산자를 초기화한다. 자식 연산자의 open()도 호출해야 한다. */
    fun open()

    /** 다음 튜플을 반환한다. 더 이상 튜플이 없으면 null. */
    fun next(): Tuple?

    /** 연산자를 정리한다. 자식 연산자의 close()도 호출해야 한다. */
    fun close()

    /** 이 연산자의 출력 스키마. */
    val outputSchema: Schema
}
```

#### 연산자 목록

| 연산자 | 입력 | 역할 |
|---|---|---|
| `SeqScanOperator` | HeapFile | 테이블 전체를 순차 스캔 |
| `FilterOperator` | child + predicate | WHERE 조건으로 튜플 필터링 |
| `ProjectOperator` | child + projections | 컬럼 선택 및 표현식 계산 |
| `SortOperator` | child + sort key | ORDER BY 정렬 (in-memory) |
| `LimitOperator` | child + limit | 상위 N개 튜플만 반환 |

#### ExpressionEvaluator

```kotlin
/**
 * 튜플 컨텍스트에서 AST Expression을 평가한다.
 *
 * SqlExecutor에 있던 표현식 평가 로직을 분리하여
 * Filter, Project 등 여러 연산자에서 재사용한다.
 */
object ExpressionEvaluator {
    /** 표현식을 평가하여 Kotlin 값(Any?)으로 반환한다. */
    fun evaluate(schema: Schema, tuple: Tuple, expr: Expression): Any?

    /** 조건 표현식을 Boolean으로 평가한다. NULL은 false로 취급 (SQL 3-value logic). */
    fun evaluateCondition(schema: Schema, tuple: Tuple, expr: Expression): Boolean
}
```

#### Planner

```kotlin
/**
 * AST Statement를 연산자 트리로 변환한다.
 */
class Planner(private val database: Database) {
    /** SELECT 문을 연산자 트리로 변환한다. */
    fun planSelect(stmt: Statement.Select): Operator

    /** UPDATE/DELETE의 스캔+필터 부분을 연산자 트리로 변환한다. */
    fun planScan(tableName: String, where: Expression?): Operator
}
```

### SqlExecutor 변경

```kotlin
class SqlExecutor(private val database: Database) {
    fun execute(sql: String): ExecuteResult  // 시그니처 변경 없음
}
```

내부 구현만 변경:
- `executeSelect()`: Planner로 연산자 트리 생성 → 루트에서 next() 반복 → ExecuteResult.Selected 생성
- `executeUpdate()`: Planner.planScan()으로 대상 행 식별 → 업데이트 수행
- `executeDelete()`: Planner.planScan()으로 대상 행 식별 → 삭제 수행
- `executeCreateTable()`, `executeDropTable()`, `executeInsert()`: 기존 로직 유지 (DDL/INSERT는 연산자 트리 불필요)

## 내부 설계

### SeqScanOperator

```kotlin
class SeqScanOperator(
    private val database: Database,
    private val tableName: String,
) : Operator
```

- `open()`: `database.scanTable(tableName)` 호출하여 iterator 획득
- `next()`: iterator에서 다음 `(RID, Tuple)` 꺼내서 Tuple 반환
- `close()`: iterator 참조 해제
- `outputSchema`: 테이블의 Schema

**설계 결정**: SeqScan이 Database를 직접 참조하는 이유 — HeapFile iterator가
BufferPoolManager의 페이지 핀/언핀 생명주기와 결합되어 있으므로, Database를 통해
접근하는 것이 가장 안전하다. 향후 Phase 6(동시성 제어)에서 트랜잭션 컨텍스트를
추가할 때도 Database 레벨에서 관리하는 것이 자연스럽다.

### FilterOperator

```kotlin
class FilterOperator(
    private val child: Operator,
    private val predicate: Expression,
) : Operator
```

- `next()`: child.next()를 반복 호출하며 predicate 통과하는 튜플만 반환
- `outputSchema`: child.outputSchema (스키마 변경 없음)

### ProjectOperator

```kotlin
class ProjectOperator(
    private val child: Operator,
    private val projections: List<SelectItem>,
) : Operator
```

- `next()`: child.next()에서 받은 튜플을 projections에 따라 새 Tuple 생성
- `outputSchema`: 프로젝션 결과에 맞는 새 Schema
- `*`(Star)인 경우: child의 전체 스키마 그대로 전달

**프로젝션 시 새 Tuple 생성**: 입력 Tuple에서 필요한 컬럼 값만 추출하여
새 Schema + 새 values 배열로 Tuple을 생성한다. 표현식(예: `a + b`)의 경우
ExpressionEvaluator로 계산 후 결과값을 포함한다.

### SortOperator

```kotlin
class SortOperator(
    private val child: Operator,
    private val sortColumn: String,
    private val ascending: Boolean,
) : Operator
```

- `open()`: child의 모든 튜플을 메모리에 수집(materialization) 후 정렬
- `next()`: 정렬된 리스트에서 순서대로 반환
- `outputSchema`: child.outputSchema (스키마 변경 없음)

**설계 결정**: in-memory 정렬만 구현한다. external merge sort는 데이터가
메모리를 초과하는 시나리오에서 필요하지만, 학습용 프로젝트에서는 in-memory로
충분하다. 필요 시 Phase 7(Query Optimizer)에서 추가한다.

**왜 Sort는 materialization이 필요한가**: Volcano의 pull 모델에서 정렬은
모든 입력을 봐야 최솟값/최댓값을 결정할 수 있으므로, 불가피하게 전체
materialization이 발생한다. 이를 **blocking operator**라고 부른다. 반면
Filter, Project, Limit는 한 튜플씩 처리 가능한 **pipeline operator**다.

### LimitOperator

```kotlin
class LimitOperator(
    private val child: Operator,
    private val limit: Int,
) : Operator
```

- `next()`: limit 횟수만큼만 child.next() 전달, 이후 null 반환
- `outputSchema`: child.outputSchema (스키마 변경 없음)

### Planner — 연산자 트리 조합

SELECT 문에 대한 연산자 트리 구성 순서:

```
SeqScan(table)
  └─→ Filter(where)          -- WHERE가 있을 때만
        └─→ Sort(orderBy)    -- ORDER BY가 있을 때만
              └─→ Limit(n)   -- LIMIT가 있을 때만
                    └─→ Project(columns)
```

**Project의 위치**: Project는 가장 마지막에 적용한다. Sort가 정렬 키 컬럼에
접근해야 하므로 Sort 이전에 Project를 적용하면 정렬 키가 사라질 수 있다.

**UPDATE/DELETE**: `planScan(tableName, where)` → SeqScan + Filter(선택적)
연산자 트리를 생성하고, 호출자(SqlExecutor)가 next()로 대상 행을 식별한 뒤
직접 변경/삭제 수행한다.

### ExpressionEvaluator 추출

SqlExecutor의 다음 메서드들을 `ExpressionEvaluator`로 이동:
- `evaluateExpression()` → `ExpressionEvaluator.evaluate()`
- `evaluateCondition()` → `ExpressionEvaluator.evaluateCondition()`
- `evaluateBinaryOp()` → 내부 private 메서드
- `numericOp()` → 내부 private 메서드
- `compareValues()` → 내부 private 메서드
- `getTupleValue()` → `ExpressionEvaluator.getTupleValue()` (Project에서도 사용)

SqlExecutor의 INSERT 관련 메서드는 그대로 유지:
- `evaluateLiteral()` — INSERT VALUES 전용 (튜플 컨텍스트 불필요)
- `coerceValue()` — 타입 변환
- `toDataType()` — SQL 타입 → 스토리지 타입

## 제약 사항 및 트레이드오프

| 결정 | 이유 | 대안 |
|---|---|---|
| Volcano pull 모델 | 교육 목적에 가장 적합, 연산자 합성 직관적 | Vectorized: 배치 처리로 성능 우수하나 구현 복잡 |
| Tuple 재사용 (별도 Row 타입 없음) | 기존 Tuple이 schema+values를 이미 보유, 새 타입 도입 불필요 | 별도 Row: 실행 레이어 전용 타입이지만 변환 비용 발생 |
| in-memory Sort | 학습용 프로젝트에서 충분, external sort는 Phase 7에서 필요 시 추가 | External merge sort: 대용량에서 필수이나 현 단계에서는 과도 |
| DDL/INSERT는 연산자 트리 미사용 | 스캔이 불필요한 단순 명령, 연산자 트리 도입이 오히려 복잡 | DML 전체를 연산자화: 학습 가치는 있으나 Phase 4 범위 초과 |
| Project를 트리 최상단에 배치 | Sort가 정렬 키에 접근해야 하므로 Project를 먼저 적용하면 깨짐 | Early projection: 옵티마이저가 불필요 컬럼을 일찍 제거 (Phase 7) |
| SeqScan이 Database 참조 | BufferPool 생명주기 관리의 안전성, Phase 6 트랜잭션 확장 용이 | HeapFile 직접 참조: 더 가볍지만 추상화 계층 우회 |

### Phase 4 범위 밖 (향후 Phase에서 구현)

- **IndexScan**: 테이블 레벨 인덱스 인프라 미구현 (Phase 1의 B+Tree는 KVStore 전용)
- **JOIN 연산자**: Parser가 JOIN 문법 미지원 (Phase 3에서 명시적으로 제외)
- **집계 연산자**: GROUP BY, HAVING, 집계 함수 파서 미지원
- **External merge sort**: 메모리 초과 시나리오 미대응

## 테스트 시나리오

### ExpressionEvaluator
- 각 리터럴 타입 평가 (Int, Float, String, Bool, Null)
- 컬럼 참조 평가
- 이항 연산: 산술(+, -, *, /), 비교(=, !=, <, >, <=, >=), 논리(AND, OR)
- 단항 연산: NEGATE, NOT
- IS NULL / IS NOT NULL
- NULL 3-value logic (NULL AND TRUE → NULL, NULL OR TRUE → TRUE 등)
- evaluateCondition: NULL → false 변환

### SeqScanOperator
- 빈 테이블 스캔 → next()가 즉시 null
- 단일 행 스캔
- 다중 행 스캔 → 모든 행 반환 확인
- open() 전 next() 호출 시 에러 (또는 빈 결과)
- close() 후 상태 정리 확인

### FilterOperator
- 조건 일치하는 행만 통과
- 전부 필터링 → 빈 결과
- 전부 통과 → 입력과 동일
- NULL 값 필터링 (IS NULL, IS NOT NULL)
- 복합 조건 (AND, OR)

### ProjectOperator
- SELECT * → 전체 컬럼 그대로
- 특정 컬럼 선택 → 해당 컬럼만 포함
- 표현식 프로젝션 (예: `a + 1`)
- 출력 스키마가 입력과 다른지 확인

### SortOperator
- 오름차순 정렬
- 내림차순 정렬
- NULL 값 정렬 (nulls last)
- 빈 입력 → 빈 결과
- 이미 정렬된 입력

### LimitOperator
- LIMIT 적용 시 상위 N개만 반환
- LIMIT > 전체 행 수 → 전체 반환
- LIMIT 0 → 빈 결과
- 빈 입력 + LIMIT → 빈 결과

### Planner
- SELECT * FROM t → SeqScan + Project
- SELECT ... WHERE ... → SeqScan + Filter + Project
- SELECT ... ORDER BY ... → SeqScan + Sort + Project
- SELECT ... WHERE ... ORDER BY ... LIMIT N → SeqScan + Filter + Sort + Limit + Project
- planScan(table, where) → SeqScan + Filter

### 통합 테스트 (기존 Phase 3 테스트 호환)
- CREATE TABLE → INSERT → SELECT 전체 흐름
- SELECT WHERE 필터링
- SELECT ORDER BY (ASC, DESC)
- SELECT LIMIT
- UPDATE + 검증 SELECT
- DELETE + 검증 SELECT
- 모든 DataType 라운드트립

## 구현 순서 (TDD)

1. **ExpressionEvaluator** — SqlExecutor에서 표현식 평가 로직 추출 + 테스트
2. **Operator 인터페이스** — sealed interface 정의 (테스트 없음, 인터페이스)
3. **SeqScanOperator** — 테이블 스캔 + 테스트
4. **FilterOperator** — 조건 필터링 + 테스트
5. **ProjectOperator** — 컬럼 프로젝션 + 테스트
6. **SortOperator** — 정렬 + 테스트
7. **LimitOperator** — 제한 + 테스트
8. **Planner** — AST → 연산자 트리 + 테스트
9. **SqlExecutor 리팩토링** — Planner 사용으로 교체
10. **통합 테스트** — 기존 Phase 3 테스트 전체 통과 확인

## 벤치마크 목표

- Phase 3 Simple Interpreter와 Phase 4 Volcano 모델의 SELECT 성능 비교
- 대량 행(10K+)에서 Filter + Limit 조합 시 early termination 효과 측정
  (Phase 3은 전체 스캔 후 필터, Phase 4는 Limit 도달 시 즉시 종료)

## 참고 자료

- CMU 15-445 Lecture 11: Query Execution I — Volcano 모델 설명
- *Database Internals* Ch.7 — Query Processing
- bustub (C++) — `AbstractExecutor`, `SeqScanExecutor` 구현 참고
- SimpleDB (Java) — `Scan` 인터페이스 참고
- Graefe, "Volcano — An Extensible and Parallel Query Evaluation System" (1994) — 원논문
