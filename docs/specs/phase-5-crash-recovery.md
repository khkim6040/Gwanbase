# Phase 5: Crash Recovery (WAL)

- ARIES 프로토콜: Analysis → Redo → Undo
- LogRecord 타입: Begin, Commit, Abort, Update, CLR
- Fuzzy checkpoint
- **Crash test가 핵심**: 랜덤 시점 kill → 재시작 → 무결성 검증
