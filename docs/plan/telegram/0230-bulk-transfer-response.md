<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->

# BULK_TRANSFER_RESPONSE (0230)

- 설명: 다건 지급이체 응답
- 전문구분코드: `0230`
- 개정: 1 (시행일 2026-01-01)
- 길이: **가변** — 선두 40바이트 + 48바이트 × 건수(`ACCEPT_CNT`, 최대 100건)
- 최대 길이: 4840바이트

## 필드

| offset | 필드 | 길이 | 타입 | 비고 |
|---:|---|---:|---|---|
| 0 | MSG_TYPE | 4 | AN | — |
| 4 | TELEGRAM_NO | 12 | N | — |
| 16 | TRANS_DT | 14 | N | — |
| 30 | RESP_CODE | 4 | AN | — |
| 34 | TOTAL_CNT | 3 | N | — |
| 37 | ACCEPT_CNT | 3 | N | — |
| 40 | **DETAIL** (반복) | 48 × n | — | 가변 — 건수 `ACCEPT_CNT`, 최대 100건 |
| +0 | DETAIL_n_SEQ | 3 | N | — |
| +3 | DETAIL_n_REF_ID | 20 | AN | — |
| +23 | DETAIL_n_RESULT | 1 | AN | — |
| +24 | DETAIL_n_TXN_ID | 20 | AN | — |
| +44 | DETAIL_n_ERROR_CODE | 4 | AN | — |
