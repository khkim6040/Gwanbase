# Phase 4: Query Execution Engine

- Volcano (Iterator) 모델: `open()`, `next(): Tuple?`, `close()`
- 연산자: SeqScan, IndexScan, Filter, Project, Sort (external merge sort), NestedLoopJoin, HashJoin
- Planner: AST → 연산자 트리
- **이 Phase 완료 = 동작하는 SQL DB**
