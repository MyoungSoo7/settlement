<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->

# INQUIRY_REQUEST (0400)

- 설명: 이체결과 조회 요청
- 전문구분코드: `0400`
- 개정: 1 (시행일 2026-01-01)
- 길이: 66바이트 (고정)

## 필드

| offset | 필드 | 길이 | 타입 | 비고 |
|---:|---|---:|---|---|
| 0 | MSG_TYPE | 4 | AN | — |
| 4 | TELEGRAM_NO | 12 | N | — |
| 16 | TRANS_DT | 14 | N | — |
| 30 | RESP_CODE | 4 | AN | — |
| 34 | ORIG_TELEGRAM_NO | 12 | N | — |
| 46 | REF_ID | 20 | AN | — |
