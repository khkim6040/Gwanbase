# Phase 3: SQL Frontend

스펙 문서를 `docs/specs/phase-3-sql-frontend.md`에 작성 후 구현.
- `Lexer`: SQL → 토큰 스트림
- `Parser`: Recursive descent + Pratt parsing (표현식)
- `AST`: sealed class 기반 트리
- `Binder`: AST ↔ Catalog 대조 검증
- 최소 지원: CREATE TABLE, INSERT, SELECT (WHERE, ORDER BY, LIMIT), UPDATE, DELETE
