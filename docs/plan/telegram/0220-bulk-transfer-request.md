<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->

# BULK_TRANSFER_REQUEST (0220)

- 설명: 다건 지급이체 요청
- 전문구분코드: `0220`
- 개정: 1 (시행일 2026-01-01)
- 길이: **가변** — 선두 52바이트 + 82바이트 × 건수(`TOTAL_CNT`, 최대 100건)
- 최대 길이: 8252바이트

## 필드

| offset | 필드 | 길이 | 타입 | 비고 |
|---:|---|---:|---|---|
| 0 | MSG_TYPE | 4 | AN | — |
| 4 | TELEGRAM_NO | 12 | N | — |
| 16 | TRANS_DT | 14 | N | — |
| 30 | RESP_CODE | 4 | AN | — |
| 34 | TOTAL_CNT | 3 | N | — |
| 37 | TOTAL_AMOUNT | 15 | N | 금액 (scale 0, BigDecimal) |
| 52 | **DETAIL** (반복) | 82 × n | — | 가변 — 건수 `TOTAL_CNT`, 최대 100건 |
| +0 | DETAIL_n_SEQ | 3 | N | — |
| +3 | DETAIL_n_BANK_CODE | 10 | AN | — |
| +13 | DETAIL_n_ACCOUNT_NO | 16 | AN | — |
| +29 | DETAIL_n_AMOUNT | 13 | N | 금액 (scale 0, BigDecimal) |
| +42 | DETAIL_n_HOLDER_NAME | 20 | AN | — |
| +62 | DETAIL_n_REF_ID | 20 | AN | — |
