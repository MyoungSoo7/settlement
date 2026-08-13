<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->

# 펌뱅킹 전문 설계서

스펙 단일 출처: `settlement-service/src/main/resources/telegram/firmbanking/*.yaml`
재생성: `./gradlew :settlement-service:generateTelegramSources`

| 코드 | 전문 | 개정 | 시행일 | 길이 |
|---|---|---:|---|---|
| `0100` | [BALANCE_REQUEST](0100-balance-request.md) | 1 | 2026-01-01 | 60바이트 |
| `0110` | [BALANCE_RESPONSE](0110-balance-response.md) | 1 | 2026-01-01 | 95바이트 |
| `0110` | [BALANCE_RESPONSE](0110-balance-response-v2.md) | 2 | 2026-07-01 | 103바이트 |
| `0200` | [TRANSFER_REQUEST](0200-transfer-request.md) | 1 | 2026-01-01 | 113바이트 |
| `0210` | [TRANSFER_RESPONSE](0210-transfer-response.md) | 1 | 2026-01-01 | 133바이트 |
| `0220` | [BULK_TRANSFER_REQUEST](0220-bulk-transfer-request.md) | 1 | 2026-01-01 | 가변 |
| `0230` | [BULK_TRANSFER_RESPONSE](0230-bulk-transfer-response.md) | 1 | 2026-01-01 | 가변 |
| `0300` | [HOLDER_REQUEST](0300-holder-request.md) | 1 | 2026-01-01 | 60바이트 |
| `0310` | [HOLDER_RESPONSE](0310-holder-response.md) | 1 | 2026-01-01 | 81바이트 |
| `0400` | [INQUIRY_REQUEST](0400-inquiry-request.md) | 1 | 2026-01-01 | 66바이트 |
| `0410` | [INQUIRY_RESPONSE](0410-inquiry-response.md) | 1 | 2026-01-01 | 91바이트 |
