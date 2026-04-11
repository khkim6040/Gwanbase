# Phase 2: Table Storage Engine

스펙 문서를 `docs/specs/phase-2-table-storage.md`에 작성 후 구현.
- `Schema`: 컬럼 정의 (이름, 타입, nullable)
- `Tuple`: 행 직렬화/역직렬화 + Null bitmap
- `HeapFile`: 순서 없는 레코드 저장 + 빈 공간 관리
- `Catalog`: 테이블/인덱스 메타데이터 영속 저장
- 지원 타입: `INT32`, `INT64`, `FLOAT64`, `VARCHAR(n)`, `BOOLEAN`, `TIMESTAMP`
