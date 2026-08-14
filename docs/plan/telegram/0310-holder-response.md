<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->

# HOLDER_RESPONSE (0310)

- 설명: 예금주 성명조회 응답
- 전문구분코드: `0310`
- 개정: 1 (시행일 2026-01-01)
- 길이: 81바이트 (고정)

## 필드

| offset | 필드 | 길이 | 타입 | 비고 |
|---:|---|---:|---|---|
| 0 | MSG_TYPE | 4 | AN | — |
| 4 | TELEGRAM_NO | 12 | N | — |
| 16 | TRANS_DT | 14 | N | — |
| 30 | RESP_CODE | 4 | AN | — |
| 34 | BANK_CODE | 10 | AN | — |
| 44 | ACCOUNT_NO | 16 | AN | — |
| 60 | HOLDER_NAME | 20 | AN | — |
| 80 | RESULT | 1 | AN | — |
