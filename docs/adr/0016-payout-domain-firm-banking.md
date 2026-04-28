# ADR 0016 — Payout (출금) 도메인 + 펌뱅킹 어댑터

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

기존 정산 사이클은 *반쪽* — 정산금 계산까지만 있고 **셀러 통장에 실제 돈이 들어가는** 단계가 없었다.
면접 질문 *"정산하고 끝인가요? 셀러는 돈을 어떻게 받죠?"* 에 답할 수 없는 상태.

또한 한국의 펌뱅킹 / 송금 도메인은 **이중 송금**이 가장 위험한 사고. 정산을 두 번 송금하면
운영사가 셀러로부터 회수해야 하는데 회수 실패율이 높다.

## 결정

### 도메인 모델

```
Payout
  ├── settlement_id (FK, UNIQUE) — 1:1, 멱등성 보장
  ├── seller_id, amount
  ├── SellerBankAccount (스냅샷, 마스킹 노출)
  ├── status: REQUESTED → SENDING → COMPLETED
  │                                → FAILED → (운영자 retry) → REQUESTED
  │                                → CANCELED (운영자 영구 취소)
  ├── firm_banking_transaction_id (COMPLETED 시 필수)
  ├── retry_count, operator_id
  └── requested_at / sent_at / completed_at / failed_at
```

### 핵심 정책

1. **이중 송금 차단**: `settlement_id` 위에 partial UNIQUE 인덱스 (NULL 제외).
   같은 정산이 두 번 Payout 생성되는 것을 DB 레벨에서 차단.

2. **펌뱅킹 거래 ID 필수**: `markCompleted(txnId)` 호출 시 txnId 가 비어있으면 도메인이
   거부. 사후 추적·환수의 근거가 사라지면 안 됨.

3. **상태 전이 강제**: retry 는 FAILED 에서만, cancel 은 FAILED/REQUESTED 에서만.
   COMPLETED 후엔 cancel/retry 불가 (환수는 별도 도메인).

4. **개별 트랜잭션 격리**: `executeSingle` 이 `REQUIRES_NEW` — 한 건 펌뱅킹 실패가
   다른 건 처리에 영향 없음.

5. **일/셀러 한도**: `PayoutLimitChecker` 로 시스템 일 한도 (10억) + 셀러 일 한도 (1억)
   검증. 초과 시 다음 영업일로 skip.

### 시연용 mock 어댑터

`MockFirmBankingAdapter` 가 `app.firmbanking.failure-rate` 설정값에 따라 무작위 실패
시뮬레이션. 0.1 (10%) 로 두면 운영자 retry 콘솔 워크플로를 시연 영상에 자연스럽게
포함시킬 수 있다.

### 운영자 콘솔

```
GET  /admin/payouts/failed       — FAILED 목록
GET  /admin/payouts/pending      — REQUESTED 목록 (배치 대기)
GET  /admin/payouts/{id}         — 상세 (마스킹된 계좌)
POST /admin/payouts/{id}/retry   — FAILED → REQUESTED
POST /admin/payouts/{id}/cancel  — FAILED → CANCELED (사유 필수)
POST /admin/payouts/execute-now  — 정기 배치 외 즉시 실행
```

## 결과

정산 사이클이 **닫힌 형태** 가 됨:

```
결제 CAPTURED
  → Settlement DONE (T+N 영업일)
  → Holdback Released (30/14/0 일 후)
  → Payout REQUESTED
  → 펌뱅킹 송금 (SENDING → COMPLETED)
  → 셀러 통장 입금
```

면접 답변 가능해진 질문:
- *"정산 후 셀러 입금은?"* → Payout 도메인 + 펌뱅킹 어댑터
- *"이중 송금 어떻게 막나요?"* → settlement_id UNIQUE + 도메인 멱등 검증
- *"송금 실패 시?"* → FAILED 상태 + 운영자 retry 콘솔 + 사유 영구 보존
- *"일 송금 한도?"* → PayoutLimitChecker (시스템·셀러 차등)

## 대안

- **Payout 없이 Settlement.status=PAYOUT_DONE**: 한 도메인에 결제 / 정산 / 송금이
  뒤섞임. 트랜잭션 경계 / 권한 / 한도 검증 모두 지저분
- **Payout 1:N**: N 정산 묶어서 1 송금. 운영 효율은 좋지만 멱등성 / 환수 추적 어려움.
  포트폴리오는 1:1 단순화 (운영 시 N:1 으로 진화 가능)
- **즉시 송금 (정산 즉시 펌뱅킹)**: Holdback 정책 / 한도 검증을 우회하게 됨

## 참조

- [V43](../../order-service/src/main/resources/db/migration/V43__payouts.sql)
- [Payout.java](../../settlement-service/src/main/java/github/lms/lemuel/payout/domain/Payout.java)
- [PayoutService.java](../../settlement-service/src/main/java/github/lms/lemuel/payout/application/service/PayoutService.java)
- [PayoutTest.java](../../settlement-service/src/test/java/github/lms/lemuel/payout/domain/PayoutTest.java)
