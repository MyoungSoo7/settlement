# ADR 0016 — 지급(Payout) 도메인 + 펌뱅킹

- 상태: Accepted
- 일자: 2026-03-12

## 컨텍스트

정산이 확정(`Settlement.status=DONE`)되면 셀러에게 실제로 돈을 송금해야 한다. 송금은 정산
*계산*과 성격이 다르다:

- **외부 의존**: 은행/PG 펌뱅킹 API 호출 — 네트워크 실패·부분 성공·지연이 일상
- **사후 추적 필수**: "이 정산금이 언제, 어떤 거래 ID 로, 어느 계좌에 나갔나"를 영구 보존해야 환수 가능
- **한도 규제**: 일별 시스템 한도·셀러별 한도를 넘어선 대량 송금은 차단/이연해야 함
- **재시도 워크플로**: 송금 실패는 운영자 개입(재시도/취소)이 필요한 명시적 상태여야 함

이를 정산 도메인 안에 섞으면 정산 계산 로직이 외부 I/O·재시도·한도 정책으로 오염된다. 따라서
**지급(Payout)을 별도 도메인**으로 분리한다.

## 결정

### 1. Payout 도메인 + 상태 머신

`payout` 도메인에 `Payout` POJO 와 `PayoutStatus` 상태 머신을 둔다:

```
REQUESTED → SENDING → COMPLETED
                    → FAILED → (운영자 retry) → REQUESTED
                    → CANCELED (운영자 취소 — 종결)
```

전이는 도메인 메서드로만 가능하다(`startSending`/`markCompleted`/`markFailed`/`retry`/`cancel`).
핵심 불변식(`Payout.java`):

- `COMPLETED` 는 `firmBankingTransactionId` 가 반드시 존재 — 사후 추적·환수 근거
- `retry()` 는 `FAILED` 에서만 가능 — REQUESTED/SENDING 재요청 차단
- `amount > 0` (도메인 + DB 제약 이중 방어)
- `cancel()` 은 사유 필수(감사 추적), `FAILED`/`REQUESTED` 에서만 가능
- 1 payout = 1 settlement 단순화(`settlementId`, 수동 송금은 null)

송금 계좌는 `SellerBankAccount` 레코드로 정산 시점 스냅샷을 Payout 에 영구 저장하며,
`maskedAccountNumber()` 로 로그·운영자 콘솔에는 마지막 4자리만 노출한다(운영은 KMS column-level
암호화 책임).

### 2. 펌뱅킹 포트 + Mock 어댑터

송금 실행은 아웃바운드 포트 `FirmBankingPort.send(account, amount, referenceId)` 뒤로 숨긴다.
구현체 `MockFirmBankingAdapter` 는 `app.firmbanking.failure-rate` 비율로 무작위 실패를
시뮬레이션해 운영자 FAILED 처리 흐름을 검증할 수 있게 한다(시연·테스트). 운영에서는 KB/신한/NH
펌뱅킹 또는 토스페이먼츠 송금 API 어댑터(+ Resilience4j 서킷)로 교체한다 — 포트만 같으면 도메인은
불변이다.

### 3. 일일 한도

`PayoutLimitChecker` 가 송금 전 **시스템 일 한도**(`app.payout.system-daily-limit`)와 **셀러 일
한도**(`app.payout.seller-daily-limit`)를 검사한다. 당일 COMPLETED 누적 + 요청액이 한도를 넘으면
거부하고, 스케줄러(`PayoutScheduler`, cron `0 0 4 * * *`)가 다음 영업일로 이연한다.

## 결과

### 좋아지는 점

- 정산 *계산* 과 *송금* 의 관심사 분리 — 정산 도메인이 외부 I/O·재시도로 오염되지 않음
- 송금 실패가 명시적 FAILED 상태 + 운영자 재시도 워크플로로 가시화(유실 없음)
- `firmBankingTransactionId` 강제로 환수·분쟁 시 추적 근거 확보
- 펌뱅킹을 포트로 추상화 — Mock↔실 펌뱅킹 교체가 도메인 무변경

### 트레이드오프 / 리스크

- COMPLETED 후 환수(ReversePayout)는 본 ADR 범위 밖 — 별도 도메인/단계로 분리
- Mock 어댑터는 실제 송금 정합성(잔액·영업일·은행 점검시간)을 검증하지 못함
- 한도 정책이 외부 설정 2개로 단순화 — 등급별/시간대별 차등은 추후 확장

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **Settlement 도메인에 송금 로직 포함** | ✗ | 계산/외부 I/O 관심사 혼재, 재시도·한도가 정산을 오염 |
| **상태 없는 fire-and-forget 송금** | ✗ | 실패 추적·재시도 불가 — 금융 송금에 부적합 |
| **별도 Payout 도메인 + 펌뱅킹 포트 (본 결정)** | ✓ | 관심사 분리 + 명시적 상태 머신 + 어댑터 교체 가능 |

## 참조

- [0002 — Settlement 상태 머신](0002-settlement-state-machine.md)
