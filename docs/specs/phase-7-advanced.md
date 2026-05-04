# Phase 7: Query Optimizer — 고도화 로드맵

MVP 이후 PostgreSQL internals를 참고하여 단계적으로 고도화할 항목을 정리한다.
각 항목은 독립적으로 추가 가능하며, 우선순위는 학습 가치 순으로 배치했다.

## 1. 조인 알고리즘 확장

### Hash Join
- 등가 조인에서 대량 데이터에 효과적. 빌드(build) 단계에서 작은 테이블을
  해시 테이블로 구성, 프로브(probe) 단계에서 큰 테이블을 스캔.
- PostgreSQL: `src/backend/executor/nodeHashjoin.c`
- 학습 포인트: 메모리 제한 시 Grace Hash Join (파티션 후 디스크 스필)

### Sort-Merge Join
- 양쪽 입력이 조인 키로 정렬되어 있거나, 인덱스가 정렬 순서를 제공할 때 유리.
- 이미 SortOperator가 있으므로 재활용 가능.
- PostgreSQL: `src/backend/executor/nodeMergejoin.c`
- 학습 포인트: 정렬 비용과 merge 비용의 트레이드오프

### 옵티마이저 통합
- CostEstimator에 Hash Join / Merge Join 비용 모델 추가
- PlanEnumerator가 세 알고리즘 중 최적을 선택

## 2. JOIN 종류 확장

### LEFT OUTER JOIN
- NULL 패딩 처리 학습. 매칭 없는 outer 행에 inner 측 NULL 채움.
- 옵티마이저 제약: LEFT JOIN은 조인 순서 교환에 제한 (outer를 바꿀 수 없음)

### RIGHT / FULL OUTER JOIN
- LEFT JOIN 구현 후 자연스럽게 확장 가능

### CROSS JOIN
- 카테시안 곱. `FROM t1, t2 WHERE ...` 암시적 조인 구문도 지원

### SEMI / ANTI JOIN
- EXISTS, NOT EXISTS 서브쿼리 최적화에 필요
- PostgreSQL: semi join은 첫 매칭 발견 시 즉시 종료 (성능 이점)

## 3. 본격 Cost-Based Optimization (CBO)

### 히스토그램 기반 선택도 추정
- MVP의 균등 분포 가정(1/distinctCount) 대신, 값 분포 히스토그램 사용
- PostgreSQL: `pg_statistic.stavalues`, `stanumbers` — MCV(Most Common Values) +
  등폭/등깊이 히스토그램
- 학습 포인트: 데이터 스큐(skew)가 쿼리 성능에 미치는 영향

### 동적 프로그래밍(DP) 기반 계획 열거
- MVP의 greedy 방식 대신, 모든 조인 순서 부분 집합을 DP로 열거
- PostgreSQL: `src/backend/optimizer/path/joinrels.c` — System R 스타일 bottom-up DP
- 테이블 수가 많아지면(12+) GEQO(유전 알고리즘)로 전환
- 학습 포인트: 최적 부분 구조, 계획 공간 폭발

### 상관 서브쿼리 최적화
- 서브쿼리를 조인으로 변환 (de-correlation)
- PostgreSQL: `pull_up_subqueries()` — 서브쿼리를 가능한 한 조인으로 풀어냄

## 4. 인덱스 고도화

### 범위 스캔 (Range Scan)
- `WHERE age > 20 AND age < 30` → B+Tree의 `scan(startKey, endKey)` 활용
- MVP에서는 등가 조건만 인덱스 스캔 대상

### 복합 인덱스 (Composite Index)
- `CREATE INDEX idx ON t (col1, col2)` — 다중 컬럼 키 직렬화
- Prefix 매칭: `WHERE col1 = ? AND col2 = ?` (full), `WHERE col1 = ?` (prefix)
- 학습 포인트: 컬럼 순서가 인덱스 활용도에 미치는 영향

### Index-Only Scan
- 인덱스만으로 결과를 반환 (heap 접근 불필요)
- PostgreSQL: visibility map 확인 후 heap 접근 스킵
- 학습 포인트: covering index 개념

### Bitmap Index Scan
- 여러 인덱스 조건을 비트맵으로 결합 후 한꺼번에 heap 접근
- PostgreSQL: `BitmapAnd`, `BitmapOr` 노드
- 학습 포인트: 랜덤 I/O를 순차 I/O로 변환

## 5. 통계 자동화

### autovacuum 스타일 자동 ANALYZE
- DML 누적 변경량이 임계치를 넘으면 자동으로 ANALYZE 실행
- PostgreSQL: `autovacuum_analyze_threshold` + `autovacuum_analyze_scale_factor`

### 확장된 통계
- 다중 컬럼 상관관계 통계 (PostgreSQL 10+: `CREATE STATISTICS`)
- 함수 종속성(functional dependency) 통계

## 6. 추가 SQL 기능 (Phase 7 범위 밖, 별도 Phase 후보)

### GROUP BY / 집계 함수
- HashAggregate, SortAggregate 연산자
- COUNT, SUM, AVG, MIN, MAX
- HAVING 절

### 서브쿼리
- 스칼라 서브쿼리, EXISTS, IN
- 상관/비상관 서브쿼리

### Window 함수
- ROW_NUMBER, RANK, DENSE_RANK
- OVER (PARTITION BY ... ORDER BY ...)

## 참고 자료

- PostgreSQL 소스: `src/backend/optimizer/`, `src/backend/executor/`
- *The Internals of PostgreSQL* (Hironobu Suzuki) — 무료 온라인, 옵티마이저 장
- CMU 15-721 (Andy Pavlo) — 고급 주제: 비용 모델, 적응형 실행
- *Access Path Selection in a Relational Database Management System* (Selinger et al., 1979) — System R 옵티마이저 원논문
- *How PostgreSQL Plans Queries* — EXPLAIN ANALYZE 심층 분석
