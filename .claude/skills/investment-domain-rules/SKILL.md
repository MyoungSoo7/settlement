---
name: investment-domain-rules
description: 투자 도메인 핵심 규칙 — 투자점수 3축 구간, AAA~CCC 등급·투자적격, 투자주문 상태머신, 재원(정산금−집행) 검증, executed Outbox 발행. investment-service 로직을 작성·수정·리뷰할 때 로드.
---

# 투자 도메인 규칙 (investment-service)

CEO 투자하기 — 상장사 회계자료로 **투자점수**를 내고, 셀러의 **확정 정산금 재원** 한도 안에서 투자주문을
집행한다. 점수 정책은 결정적 순수 함수(구간 매핑)라 경계값 전수 단위테스트로 회귀를 차단한다.

## 투자점수 정책 (`domain/InvestmentScorePolicy.java`)

총점(0~100) = **수익성 35 + 안정성 35 + 성장성 30** (최신 회계연도 + 직전 연도):

| 축 | 지표 | 구간 |
|---|---|---|
| 수익성 35 | 영업이익률(%) 0~20 | ≥20→20, ≥15→16, ≥10→12, ≥5→8, ≥0→4, 음수/null→0 |
| 수익성 35 | ROA(%) 0~15 | ≥15→15, ≥10→12, ≥7→9, ≥4→6, ≥0→3, 음수/null→0 |
| 안정성 35 | 부채비율(%) 0~20, **낮을수록↑** | ≤50→20, ≤100→16, ≤150→12, ≤200→8, ≤300→4, else→0, null→0 |
| 안정성 35 | 자기자본비율(%) 0~15, **높을수록↑** | ≥60→15, ≥50→12, ≥40→9, ≥30→6, ≥20→3, else→0, null→0 |
| 성장성 30 | 매출 YoY(%) 0~15 | ≥20→15, ≥10→12, ≥5→9, ≥0→6, ≥-10→3, else→0, null→0 |
| 성장성 30 | 순이익 YoY(%) 0~15 | (매출과 동일 구간) |

- YoY = `(당기−전기)/전기 × 100` (`MathContext.DECIMAL64`). **전기 결측 또는 전기≤0 → null → 해당 지표 0점**(보수적).
- **직전 연도 전체 부재 → 성장성 중립 50%(=15)**, 근거 지표 null. (성장성만; 수익성·안정성은 최신연도로 정상 산정.)
- null 지표는 언제나 **0점**(계정과목 미제공에 관대하지 않음). 이 보수성을 완화하는 변경은 반려하라.

## 등급·투자적격 (`domain/InvestmentGrade.java`)

- **≥90 AAA, ≥80 AA, ≥70 A, ≥60 BBB, ≥50 BB, ≥40 B, <40 CCC**.
- **투자 적격 = 총점 ≥60 (BBB 이상)**. `INVESTABLE_THRESHOLD=60`. 부적격 종목 주문 → `NotInvestableException`(→422).

## 투자주문 상태머신 (`domain/InvestmentOrder.java` — 도메인이 전이 강제)

```
REQUESTED → APPROVED → EXECUTED
          ↘ REJECTED
REQUESTED/APPROVED → CANCELED
```

- 전이는 도메인 메서드로만: `approve()`(REQUESTED만), `execute()`(APPROVED만), `reject()`(REQUESTED만),
  `cancel()`(REQUESTED/APPROVED만). 위반 → `InvalidInvestmentOrderStateException`(ErrorCode `INVALID_STATE`, 400).
  setter 로 status 바꾸는 코드 반려.
- 생성 검증: `stockCode` 는 `\d{6}`, `amount` 양수.
- **스냅샷**: 신청 시점 `scoreAtOrder`/`gradeAtOrder` 보존 — 이후 재무제표 갱신으로 점수가 바뀌어도
  주문 이력은 신청 당시 근거 유지 (정산 commission_rate 스냅샷과 동일 철학, settlement-domain-rules 참조).

## 재원 규칙 (`seller_funding_view` 프로젝션)

- **가용 재원 = 확정 정산금 합계 − 집행완료(EXECUTED) 투자금 합계**
  (`sumConfirmedBySeller − sumExecutedAmountBySeller`). 미집행 주문은 차감 대상 아님.
- 신청(`PlaceInvestmentOrderService`): 적격 검증 → 재원≥신청액 검증 → `REQUESTED` 저장.
- **집행(`ExecuteInvestmentOrderService`) 은 재원을 재검증한다** — 신청 이후 재원이 줄었을 수 있으므로.
  1. **소유권 검증 먼저**(타 셀러 주문 집행 불가, `AccessDeniedException`) — 재원 검증보다 앞.
  2. 재원 부족 → `order.reject()` 저장 + `InsufficientFundingException`(→422).
  3. `approve()`→`execute()`→save→**Outbox `InvestmentExecuted` 발행**.
- 신청 시 재원 검증만 믿고 집행에서 재검증을 생략하는 변경은 반려하라 (재원 초과집행 유발).

## 이벤트·외부조회 (idempotency-and-events 참조)

- 수신: `settlement.confirmed`(consumer group `lemuel-investment`) → `seller_funding_view` 멱등 UPSERT
  (`processed_events` + `seller_funding_view.settlement_id UNIQUE`).
- 발행: `lemuel.investment.executed` — **반드시 Outbox 경유**(집행과 같은 트랜잭션).
- 회계자료는 **financial 공개 API** 를 `adapter/out/external` 로 조회(캐시 10분, `InvestmentCacheConfig`).
  financial DB 직접 조인·import 금지 — 서비스 간 코드·DB 의존 0.

## 안티패턴 (발견 시 지적)

- status setter 직접 변경 / 전이 메서드 우회.
- 집행 단계 재원 재검증 생략, 또는 소유권 검증을 재원 검증 뒤로 미룸.
- null 지표를 0점 아닌 중립/평균으로 처리 (점수 인플레).
- 투자점수/등급을 정책 클래스 밖에서 인라인 계산.
- executed 를 `kafkaTemplate.send()` 직접 발행.
