---
name: insurance-domain-rules
description: GA 보험대리점 도메인 핵심 규칙 — 설계→청약→계약→수수료 상태머신, 완전판매 게이트 2단(교부+서류 MATCHED, 전이 前 검사), 방카 25%룰(부문별 풀·초과만 위반·fail-closed), 환수 24개월 창구·12회 분할 잔여 가산, fcId JWT 단일 파생(IDOR). insurance-service 로직을 작성·수정·리뷰할 때 로드.
---

# 보험 도메인 규칙 (insurance-service)

GA(보험대리점) 플랫폼 — 가입설계·청약·계약·수수료정산·일반지급(해약환급/만기). 방카슈랑스 채널 포함.
상담·유지변경은 **DB 테이블만 있고 코드 상태머신이 없다** — 이 두 흐름에 규칙이 강제된다고 가정하지 말 것.

## 상태머신 (전부 `canTransitionTo` 전이표 + DB CHECK 1:1)

| enum | 상태 | 허용 전이 |
| --- | --- | --- |
| `ProposalStatus` | QUOTED·CONVERTED·EXPIRED | QUOTED→{CONVERTED, EXPIRED} **2개뿐** — 재산출 전이 없음(조건 변경 = 새 설계 INSERT-only 스냅샷) |
| `ApplicationStatus` | SUBMITTED·UNDER_REVIEW·APPROVED·REJECTED | SUBMITTED→UNDER_REVIEW, UNDER_REVIEW→{APPROVED, REJECTED} 3개뿐 |
| `PolicyStatus` | ACTIVE·LAPSED·SURRENDERED·EXPIRED·CANCELLED | 정확히 7개: ACTIVE→{LAPSED,SURRENDERED,EXPIRED,CANCELLED}, LAPSED→{ACTIVE,EXPIRED,CANCELLED} |
| `CommissionStatus` | SCHEDULED·PAID·CLAWBACK_PENDING·CLAWED_BACK·CANCELLED | SCHEDULED→{PAID,CANCELLED}, PAID→CLAWBACK_PENDING, CLAWBACK_PENDING→CLAWED_BACK |
| `GeneralPayoutStatus` | REQUESTED·PAID | 1개(REQUESTED→PAID) — payout 은 이미 확정된 terminal 전이에서만 태어나므로 거절·취소 상태가 없다 |
| `ApplicationDocumentStatus` | EXTRACTED·MATCHED·MISMATCHED·NEEDS_REVIEW | EXTRACTED→3종, NEEDS_REVIEW→{MATCHED,MISMATCHED}. 판정 번복은 **새 서류 첨부**로만 |

전이는 애그리거트 메서드로만: `Policy.reinstate/surrender/expire/cancel/recordPremiumSuccess`,
`CommissionSchedule.markPaid/cancelRemaining/flagClawbackPending/confirmClawedBack`, `ProposalQuote.quote/convert/expire`.

## 완전판매 게이트 2단 — 상태 전이 **前** 검사 (핵심)

`ApplicationUnderwritingService.approve()` 는 APPROVED 전이 **전에** 두 게이트를 통과해야 한다.
실패 시 청약은 **UNDER_REVIEW 로 남는다** — 게이트를 전이 뒤로 옮기면 미완전판매 계약이 성립한다.

1. **상품설명서 교부**: `existsForApplication` 실패 → `DisclosureNotDeliveredException`
2. **청약서류 대사**: 최신 서류가 `MATCHED` 아니면 → `ApplicationDocumentNotMatchedException`
   (미첨부 거절은 `app.insurance.application-ocr.required=true` 일 때만)

교부 문서의 `document_sha256` 은 **서버가 방금 렌더한 PDF 바이트에서 계산**한다 — 클라이언트 입력 신뢰 금지.

## 방카 25%룰 (판매집중도 — 탐지·통보, 차단 아님)

`BancaRuleEvaluator` — 고치기 전에 세 가지 방향을 확인하라(전부 실수하기 쉬운 방향으로만 틀린다):

- `CONCENTRATION_LIMIT = new BigDecimal("0.25")` 단일 선언. **초과만 위반** — `>=` 로 바꾸지 말 것(정확히 0.25 는 허용).
- 분모는 **(은행 × 부문 생보/손보) 풀** — 은행 전체 합산으로 바꾸면 부문 집중이 희석돼 룰이 무력화된다.
- 자산 2조 이상만 대상이되 **자산 미등록 은행은 대상으로 본다(fail-closed)** — `isSubjectToRule(null)==true` 를 면제로 바꾸지 말 것.
- 위반은 `banca_rule_violated` 이벤트 발행뿐(월 1회 `insurance-banca-rule-monitor` 배치). 청약을 막지 않는다.

## 돈 불변식 (상수 단일 선언 — 리터럴 재기재 금지)

- `CommissionConstants`: `CLAWBACK_WINDOW_MONTHS=24` · `INSTALLMENT_COUNT=12` · `AMOUNT_SCALE=2`.
  `Policy.REINSTATEMENT_WINDOW_MONTHS` 는 이 상수를 **재참조**한다 — 24 를 다시 적으면 가드/규율 위반.
- **환수(Clawback)**: `ClawbackCalculator` — 경과 m≥24 → 0, m<24 → `paidTotal × (24−m)/24` `RoundingMode.DOWN`,
  CANCELLED 는 전액. 트리거 상태는 SURRENDERED/CANCELLED/EXPIRED 뿐. 위반 입력은 타입 예외
  (`InvalidClawbackInputException`/`InvalidClawbackStateException`).
- **12회 분할**: `CommissionScheduleFactory` — 회차 = `firstYearTotal/12` DOWN 절사, **잔여는 마지막 회차에 가산**해
  Σ회차 = 원금 정확 일치. 이 잔여 처리를 건드리면 합계가 깨진다.
- **보험료 산출 폴백 금지**: `PremiumRater` — 요율 없으면 `RateNotFoundException`, 산출 0원이면
  `InvalidProposalException`. 임의 최소값·기본 요율로 메꾸지 않는다.
- 해약환급률표 `GeneralPayoutConstants.SURRENDER_REFUND_RATE_TIERS`(NavigableMap floor-entry, 0/12/36/60/84개월 구간).

## 멱등·이벤트 (idempotency-and-events 참조)

- 발행은 전부 Outbox(`InsurancePolicyEventPublisherAdapter`, aggregateType `"Insurance"`), 금액은 `toPlainString()`.
  토픽 9종(`policy_issued`·`policy_status_changed`·`commission_confirmed/paid/clawback_triggered`·
  `banca_rule_violated`·`general_payout_requested/paid`·`commission_monthly_closed`) — 정본은 topic-catalog.json.
- 컨슈머: `CarrierPolicyStatusConsumer`(그룹 `lemuel-insurance`) — `IdempotentEventConsumer`(L2) + 격리 테이블
  `quarantined_events UNIQUE(consumer_group, topic, partition, offset)`.
- L3 도메인 UNIQUE: `uq_policy_number`(자연키) · `uq_commission_schedule(policy_id, recipient_type, fc_id, installment_no)`
  **단일 통일 제약** · `uq_general_payout_policy_type(policy_id, payout_type)` · `uq_commission_closing_fc_month`(append-only).
- 조건부 CHECK 가 상태·컬럼 짝을 강제: `(CONVERTED)=(converted_application_id NOT NULL)`, `(PAID)=(paid_on NOT NULL)`,
  `(BANCA)=(partner_bank_code NOT NULL)` — 한쪽만 바꾸는 마이그레이션 금지.
- PII 는 `enc:v1:` 접두 암호문만(DB CHECK — 평문 발견 시 마이그레이션 자체가 RAISE EXCEPTION 중단).

## 권한 (IDOR — fcId 단일 파생)

- **fcId 를 요청 본문·파라미터로 절대 받지 않는다** — `FcIdentity.currentFcId()` 가 JWT 주체에서만 파생.
  본문으로 받으면 남의 fcId 로 수수료 수령인을 가로채거나 타인 계약을 해지시킬 수 있다. 실패는 403.
- **검사 순서**: 소유권 403 을 내용 불일치 400 **보다 먼저** — 뒤집으면 남의 리소스 존재·내용을 추측할 수 있다.
  판매종료 상품은 404 동형(`ProductNotFoundException`)으로 존재 은닉.
- 설계→청약 전환(`POST /proposals/{id}/convert`)은 **금액 파라미터 없음** — 보험료는 설계 스냅샷에서 서버 주입.
- 심사 API(review/approve/reject)·서류함은 ADMIN/MANAGER 전용(SecurityConfig).

## 스케줄러 7종 (전부 ShedLock + KST)

락 이름: `insurance-proposal-expiry`·`insurance-policy-expiry`·`insurance-clawback-sweep`·`insurance-commission-payout`·
`insurance-general-payout`·`insurance-monthly-closing`·`insurance-banca-rule-monitor` (이름 재사용 금지 —
scheduler-lock-gate 가 전수 차단). cron zone 과 본문 `now(clock)` **둘 다 KST** — 어긋나면 만기 판정이 하루 밀린다.

## 안티패턴 (발견 시 지적)

- 완전판매 게이트를 APPROVED 전이 **뒤**로 이동 (실패해도 계약이 성립해 버림).
- 25%룰 분모를 은행 전체로 / `>=` 판정으로 / 자산 미등록 은행 면제로 변경 (세 방향 모두 룰 무력화).
- 24·12·0.25·2조 리터럴 재기재 (단일 선언 상수 재참조가 정답).
- 12회 분할의 마지막 회차 잔여 가산 제거 (Σ회차 ≠ 원금).
- 요율 부재·산출 0원에 기본값 폴백 (보험료를 추정으로 만드는 것).
- fcId·금액을 클라이언트 입력에서 수용 / 소유권 검사를 내용 검사 뒤로 배치.
- `document_sha256` 을 클라이언트에서 수신.
- Outbox 우회 `kafkaTemplate.send` / 토픽명 문자열 하드코딩 (aggregateType+eventType 파생이 정답).
- 도메인에서 generic `IllegalArgumentException` throw (guard `OO-DOMAIN-GENERIC-IAE` 차단 — 타입 예외 사용).
- 상담·유지변경 흐름에 상태머신이 있다고 가정.
