# Phase 7: Query Optimizer

## 목표

SQL 실행 경로에 쿼리 옵티마이저를 도입하여, 실행 계획을 자동으로 최적화한다.
인덱스 스캔, 조인 순서 최적화, 비용 기반 계획 선택을 통해 데이터베이스 옵티마이저의
핵심 원리를 학습한다.

### MVP 범위

- **인덱스 연동**: CREATE/DROP INDEX SQL, Secondary Index(B+Tree → RID), IndexScanOperator
- **JOIN 지원**: INNER JOIN 파싱/실행, Nested Loop Join 연산자
- **최적화**: RBO 기반 + 간단한 통계 활용 (행 수 자동 추적, ANALYZE로 컬럼 통계 수집)
- **EXPLAIN**: 실행 계획 텍스트 출력

### 비기능 요구사항

- 기존 단일 테이블 쿼리의 회귀 없음 (모든 Phase 1–6 테스트 통과)
- 인덱스 유지보수 오버헤드로 DML 성능이 크게 저하되지 않아야 함
- EXPLAIN 출력으로 옵티마이저 판단을 검증 가능해야 함

## 인터페이스 설계 (public API)

### 새로운 SQL 구문

```sql
-- 인덱스
CREATE INDEX idx_name ON table_name (column_name)
DROP INDEX idx_name

-- 조인
SELECT t1.col, t2.col FROM t1 JOIN t2 ON t1.id = t2.t1_id WHERE ...

-- 통계 수집
ANALYZE table_name

-- 실행 계획 확인
EXPLAIN SELECT ...
```

### 새로운 ExecuteResult 타입

```kotlin
ExecuteResult.IndexCreated(indexName: String)
ExecuteResult.IndexDropped(indexName: String)
ExecuteResult.Analyzed(tableName: String, rowCount: Long)
ExecuteResult.Explained(planText: String)
```

## 내부 설계

### 1. Parser/AST 확장

#### 새로운 Statement 타입

```kotlin
Statement.CreateIndex(indexName: String, tableName: String, columnName: String)
Statement.DropIndex(indexName: String)
Statement.Analyze(tableName: String)
Statement.Explain(statement: Statement)
```

#### FROM 절 확장

현재 `Statement.Select.tableName: String`을 `FromClause`로 교체한다.

```kotlin
sealed class FromClause {
    data class Table(val tableName: String, val alias: String?) : FromClause()
    data class Join(
        val left: FromClause,
        val right: FromClause,
        val condition: Expression,
    ) : FromClause()
}

// Select 변경
data class Select(
    val columns: List<SelectItem>,
    val from: FromClause,          // 기존 tableName 대체
    val where: Expression?,
    val orderBy: OrderByClause?,
    val limit: Int?,
)
```

#### ColumnRef 확장

```kotlin
// 기존: ColumnRef(name: String)
// 변경: ColumnRef(table: String?, name: String)
// table이 null이면 단일 테이블에서 해석 (하위 호환)
```

#### 테이블 별칭

`FROM users u JOIN orders o ON u.id = o.user_id` 형태를 지원한다.
Binder가 별칭 → 실제 테이블명 매핑을 관리한다.

### 2. Binder 확장

다중 테이블 스코프를 지원하도록 변경한다.

- FROM 절의 모든 테이블을 수집하여 스코프 구성
- 테이블 한정 컬럼 참조(`t1.col`)와 비한정 참조(`col`) 모두 해석
- 비한정 참조가 여러 테이블에 존재하면 `BindException` (ambiguous column)
- 별칭이 있으면 별칭으로만 참조 가능

### 3. Catalog 확장

#### 인덱스 메타데이터

```kotlin
data class IndexInfo(
    val indexId: Int,
    val name: String,           // 인덱스 이름
    val tableName: String,      // 대상 테이블
    val columnName: String,     // 인덱스 컬럼 (MVP: 단일 컬럼)
    val rootPageId: Int,        // B+Tree 루트 페이지 ID
)
```

Catalog 직렬화에 인덱스 섹션을 추가한다. 기존 테이블 목록 뒤에 인덱스 목록을
직렬화한다.

#### 테이블 통계

```kotlin
data class TableStats(
    val tableName: String,
    val rowCount: Long,
    val columnStats: Map<String, ColumnStats>,
)

data class ColumnStats(
    val distinctCount: Long,
    val minValue: Any?,
    val maxValue: Any?,
    val nullCount: Long,
)
```

- `rowCount`: INSERT 시 +1, DELETE 시 -1 자동 추적. Catalog에 영속.
- `columnStats`: ANALYZE 실행 시 전체 스캔으로 수집. Catalog에 영속.

### 4. B+Tree 키 직렬화 (KeySerializer)

테이블 컬럼 값을 B+Tree 키로 변환한다. unsigned lexicographic 비교에서
올바른 정렬 순서가 보존되어야 한다.

```
INT32   → 4바이트 big-endian, 부호 비트 XOR 0x80 (음수 < 0 < 양수 보존)
INT64   → 8바이트 big-endian, 부호 비트 XOR 0x80
VARCHAR → UTF-8 바이트 그대로 (사전순 = lexicographic)
```

value에는 RID를 직렬화한다: `[pageId: 4바이트][slotId: 2바이트]` = 6바이트 고정.

### 5. 인덱스 유지보수

CREATE INDEX 실행 시:
1. 기존 HeapFile을 풀 스캔하여 `(컬럼값 → RID)` 쌍 수집
2. B+Tree를 새로 생성하고 모든 엔트리 삽입
3. Catalog에 IndexInfo 등록

DML 시 자동 유지보수:
- INSERT → 해당 테이블의 모든 인덱스에 엔트리 추가
- DELETE → 해당 인덱스에서 엔트리 제거
- UPDATE → 인덱스 컬럼이 변경된 경우 삭제 후 재삽입

### 6. Optimizer (신규 gwanbase.optimizer 패키지)

#### 전체 파이프라인

```
AST → Optimizer → PlanNode → Planner → Operator 트리
```

Optimizer는 AST를 받아 최적의 PlanNode(논리 계획)를 반환한다.
Planner는 PlanNode를 Operator로 변환하는 단순 변환기가 된다.

#### PlanNode (논리 계획 트리)

```kotlin
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
        val lookupKey: Expression,       // 등가 조건의 우변 (정적 또는 outer 참조)
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
}
```

#### CostEstimator (비용 추정)

간단한 I/O 비용 모델:

```
SeqScan 비용     = 테이블 전체 페이지 수
IndexScan 비용   = 트리 높이 + 매칭 행 수 (각 행마다 heap 랜덤 I/O 1회)
NLJoin 비용      = outer 비용 + outer 행 수 × inner 비용
Sort 비용        = child 비용 + N log N  (N = 행 수)
```

#### 선택도(Selectivity) 추정

```
등가 (col = val)     → 1 / distinctCount
범위 (col > val)     → (max - val) / (max - min)   수치 타입일 때
AND                  → selectivity(left) × selectivity(right)
OR                   → sel(left) + sel(right) - sel(left) × sel(right)
통계 없을 때 기본값   → 0.1 (등가), 0.33 (범위), 0.5 (기타)
```

PostgreSQL도 통계가 없으면 유사한 매직 넘버를 사용한다.

#### PlanEnumerator (계획 열거)

MVP 최적화 범위:

1. **접근 경로 선택**: 각 테이블에 대해 SeqScan vs IndexScan 중 비용이 낮은 것 선택
2. **조인 순서**: 2개 테이블이면 A⋈B vs B⋈A 비교, 3개 이상이면 left-deep tree에서
   탐욕적(greedy) 선택 — 가장 작은 결과를 내는 조인부터
3. **조건 Push-down**: WHERE 조건 중 단일 테이블에 해당하는 조건은 스캔 레벨로 내림

### 7. Planner 변경

기존: AST → Operator 직접 변환
변경: PlanNode → Operator 변환 (단순 매핑)

```kotlin
class Planner(private val database: Database, private val session: DatabaseSession?) {
    fun toOperator(plan: PlanNode): Operator = when (plan) {
        is PlanNode.SeqScan     -> SeqScanOperator(...)
        is PlanNode.IndexScan   -> IndexScanOperator(...)
        is PlanNode.NestedLoopJoin -> NestedLoopJoinOperator(
            outer = toOperator(plan.outer),
            inner = toOperator(plan.inner), ...
        )
        is PlanNode.Sort    -> SortOperator(toOperator(plan.child), ...)
        is PlanNode.Limit   -> LimitOperator(toOperator(plan.child), ...)
        is PlanNode.Project -> ProjectOperator(toOperator(plan.child), ...)
    }
}
```

### 8. 신규 연산자

#### IndexScanOperator

```kotlin
class IndexScanOperator(
    database: Database,
    tableName: String,
    indexName: String,
    lookupKeySupplier: () -> Any?,   // 검색 키 공급자 (동적 키 지원)
    remainingFilter: Expression?,
    session: DatabaseSession?,
) : Operator
```

`lookupKeySupplier`를 함수로 받는 이유: Index Nested Loop Join에서 outer 튜플이
바뀔 때마다 inner IndexScan의 검색 키가 달라져야 하기 때문이다.

- **단독 사용** (`WHERE col = 5`): `{ 5 }` 상수 람다
- **NLJ inner**: `{ outerTuple.getValue("id") }` — outer 행에서 동적으로 키 추출

동작:
1. `open()`: lookupKeySupplier()로 키를 얻고, B+Tree에서 검색 → 매칭 RID 목록 수집
2. `next()`: RID로 HeapFile 조회 → remainingFilter 적용 → 통과 시 반환
3. `close()`: 리소스 정리

MVP에서는 등가 조건(`col = val`)만 인덱스 스캔 대상이다.

#### NestedLoopJoinOperator

```kotlin
class NestedLoopJoinOperator(
    private val outer: Operator,
    private val inner: Operator,
    private val condition: Expression,
    private val combinedSchema: Schema,
) : Operator
```

동작:
1. `open()`: outer.open()
2. `next()`: outer 행 유지, inner 순회. inner 끝나면 inner 재시작 + outer.next()
3. condition 평가 시 두 튜플을 병합한 결합 튜플 사용

### 9. EXPLAIN 실행

EXPLAIN은 Optimizer까지만 실행하고 PlanNode 트리를 텍스트로 출력한다.
Operator를 생성하거나 데이터를 읽지 않는다.

출력 형식:

```
EXPLAIN SELECT * FROM users WHERE age = 25

→ IndexScan(table=users, index=idx_users_age, key=25)  rows=10 cost=4.0
```

```
EXPLAIN SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id

→ Project(columns=[u.name])  rows=100 cost=520.0
    └─ NestedLoopJoin(on: u.id = o.user_id)  rows=100 cost=520.0
        ├─ SeqScan(table=users)  rows=20 cost=5.0
        └─ IndexScan(table=orders, index=idx_orders_user_id)  rows=5 cost=3.0
```

## 제약 사항 및 트레이드오프

| 결정 | 선택 | 이유 | PostgreSQL과의 차이 |
|---|---|---|---|
| 최적화 방식 | RBO + 간단한 통계 | MVP에서 학습 가치와 구현 비용의 균형 | PostgreSQL은 본격 CBO (히스토그램, DP 계획 열거) |
| JOIN 종류 | INNER JOIN만 | commutativity 자유, 옵티마이저 학습에 충분 | PostgreSQL은 OUTER/CROSS/SEMI/ANTI JOIN 모두 지원 |
| 조인 알고리즘 | Nested Loop Join만 | 인덱스 활용 시 Index NLJ 학습 가능 | PostgreSQL은 Hash Join, Merge Join도 지원 |
| 인덱스 타입 | Secondary Index (B+Tree → RID) | 기존 HeapFile 유지, PostgreSQL 방식 | MySQL/InnoDB는 Clustered Index |
| 인덱스 컬럼 | 단일 컬럼 | MVP 단순화 | PostgreSQL은 복합 인덱스 지원 |
| 인덱스 스캔 | 등가 조건만 | 범위 스캔은 고도화 단계 | PostgreSQL은 범위/LIKE/IS NULL 등 다양한 스캔 |
| 조인 순서 열거 | 탐욕적 (greedy) | 테이블 수가 적을 때 충분 | PostgreSQL은 DP + GEQO (12테이블 이상) |
| 통계 | 행 수 자동 + ANALYZE | 가벼운 혼합 방식 | PostgreSQL은 autovacuum 자동 ANALYZE, 히스토그램 |

## 테스트 시나리오

### Parser/AST 확장
- JOIN 구문 파싱 (테이블 별칭 포함)
- ANALYZE, EXPLAIN, CREATE INDEX, DROP INDEX 파싱
- 테이블 한정 컬럼 참조 (`t1.col`) 파싱
- 기존 단일 테이블 SELECT 회귀 테스트

### Catalog 인덱스 관리
- CREATE INDEX → IndexInfo 등록 확인
- DROP INDEX → 제거 확인
- Catalog 영속화 후 재로드 시 인덱스 메타데이터 보존

### B+Tree 키 직렬화 (KeySerializer)
- INT32/INT64 부호 비트 반전 후 정렬 순서 보존 (음수, 0, 양수)
- VARCHAR UTF-8 사전순 정렬
- RID 직렬화/역직렬화 왕복

### IndexScanOperator
- 등가 조건으로 정확한 결과 반환
- 인덱스에 없는 키 조회 시 빈 결과
- remainingFilter와 조합 시 올바른 필터링

### NestedLoopJoinOperator
- 두 테이블 INNER JOIN 기본 동작
- ON 조건에 맞지 않는 행 제외
- 한쪽 테이블이 비어 있을 때 빈 결과
- 양쪽 모두 다수 행일 때 올바른 결합

### StatisticsManager
- INSERT/DELETE 시 rowCount 자동 증감
- ANALYZE 후 columnStats 정확성 (distinctCount, min, max, nullCount)
- 통계 영속화 후 재로드

### CostEstimator
- SeqScan vs IndexScan 비용 비교: 선택도 낮을 때 IndexScan이 저비용
- 선택도 추정: 등가, 범위, AND/OR 조합
- 통계 없을 때 기본값 적용

### PlanEnumerator
- 인덱스 있는 컬럼의 등가 조건 → IndexScan 선택
- 인덱스 없거나 선택도 높으면 → SeqScan 선택
- 2테이블 조인 순서: 작은 테이블이 outer
- WHERE 조건 push-down

### EXPLAIN
- 단일 테이블 EXPLAIN 출력 형식
- JOIN EXPLAIN 출력에 예상 비용/행 수 포함

### 통합(E2E) 테스트
- CREATE TABLE → INSERT → CREATE INDEX → SELECT WHERE → 인덱스 활용 확인
- 두 테이블 JOIN 올바른 결과
- 인덱스 유지보수: INSERT/DELETE 후 인덱스 정합성 유지
- ANALYZE → 옵티마이저가 통계 기반으로 계획 변경 확인

## 컴포넌트 목록

| 컴포넌트 | 패키지 | 신규/변경 | 파일 |
|---|---|---|---|
| FromClause | `sql` | 신규 | `Ast.kt` |
| Statement.CreateIndex/DropIndex/Analyze/Explain | `sql` | 신규 | `Ast.kt` |
| ColumnRef.table | `sql` | 변경 | `Ast.kt` |
| Lexer | `sql` | 변경 | `Lexer.kt` |
| Parser | `sql` | 변경 | `Parser.kt` |
| Binder | `sql` | 변경 | `Binder.kt` |
| SqlExecutor | `sql` | 변경 | `SqlExecutor.kt` |
| IndexInfo | `table` | 신규 | `Catalog.kt` |
| TableStats, ColumnStats | `table` | 신규 | `Catalog.kt` |
| Catalog | `table` | 변경 | `Catalog.kt` |
| Database | `table` | 변경 | `Database.kt` |
| KeySerializer | `index` | 신규 | `KeySerializer.kt` |
| PlanNode | `optimizer` | 신규 | `PlanNode.kt` |
| StatisticsManager | `optimizer` | 신규 | `StatisticsManager.kt` |
| CostEstimator | `optimizer` | 신규 | `CostEstimator.kt` |
| PlanEnumerator | `optimizer` | 신규 | `PlanEnumerator.kt` |
| Optimizer | `optimizer` | 신규 | `Optimizer.kt` |
| IndexScanOperator | `execution` | 신규 | `IndexScanOperator.kt` |
| NestedLoopJoinOperator | `execution` | 신규 | `NestedLoopJoinOperator.kt` |
| Planner | `execution` | 변경 | `Planner.kt` |

## 벤치마크 목표

- SeqScan vs IndexScan: 10,000행 테이블에서 등가 조건 SELECT 시 IndexScan이
  SeqScan 대비 유의미하게 빠른 것을 확인
- NLJ vs Index NLJ: 조인 내측에 인덱스 유무에 따른 성능 차이 측정

## 참고 자료

- *Database Internals* (Alex Petrov) — Ch.7 B-Tree Variants, Index 설계
- CMU 15-445 Lecture 12–14 — Query Processing, Optimization
- PostgreSQL 소스 `src/backend/optimizer/` — 계획 열거, 비용 모델
- *Architecture of a Database System* §5 — Query Processor 개요
- PostgreSQL EXPLAIN 문서 — 출력 형식 참고
