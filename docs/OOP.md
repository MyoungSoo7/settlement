# 객체지향 설계 평가 (OOP Score)

> **종합 9.7 / 10** — 전 축 중앙값 ≥ 9.5, PASS
>
> 평가일: 2026-07-15 · 방법론: 독립 리뷰어 3인 패널 × 5축 × 축별 중앙값 (`oo-score` 스킬 정본 프로토콜)
> 대상: settlement / order / loan / investment / account 5개 서비스의 `domain/` + `application/` 패키지

## 평가 방법

점수는 단일 LLM 의 인상 채점이 아니라 **2계층 체계**로 산출했다.

1. **결정적 게이트 (기계 강제)** — `scripts/harness/test/oo-gate.test.mjs` 트리 전수 검사:
   도메인 public setter 0 · `@Setter`/`@Data` 0 · 금융 5서비스 도메인 generic IAE 0 · 코어 애그리거트
   팩토리 봉인 · 상태 전이표 존재. **6/6 GREEN** (2026-07-15 실행).
2. **LLM 패널 판정** — 서로 다른 계열의 읽기 전용 리뷰어 3인(architect / critic / hexagonal-arch-reviewer)을
   상호 비공지로 병렬 투입, 공통 루브릭(실측 없는 감점 금지 · MSA 필연 설계 비감점 · 명문화된 설계 판단
   비감점 · 캘리브레이션 조항)으로 채점 후 **축별 중앙값** 집계. 감점은 파일:라인 실측이 있는 것만 인정.

## 점수표

| 축 | 리뷰어 A | 리뷰어 B | 리뷰어 C | **중앙값** |
|---|:---:|:---:|:---:|:---:|
| ① 도메인 모델 풍부함 | 9.8 | 9.8 | 9.7 | **9.8** |
| ② 캡슐화/불변성 | 9.8 | 9.7 | 9.7 | **9.7** |
| ③ SOLID | 9.7 | 9.6 | 9.6 | **9.6** |
| ④ 응집도/결합도 | 9.7 | 9.8 | 9.5 | **9.7** |
| ⑤ 예외 설계 | 9.8 | 9.8 | 9.7 | **9.8** |
| **종합 (중앙값 평균)** | | | | **9.7** |

## 축별 근거

### ① 도메인 모델 풍부함 — 9.8

anemic model 없이 규칙이 도메인 객체 안에 산다.

- **상태 전이 단일 출처**: 모든 애그리거트가 enum 의 `canTransitionTo` 한 곳에 전이 규칙을 두고
  전이 메서드가 이를 위임한다 — `SettlementStatus.java:33-48` ← `Settlement.java:188-260`
  (startProcessing/complete/fail/retry/cancel), `OrderStatus.java:39-61` 은
  `EnumMap<OrderStatus, EnumSet<OrderStatus>>` 데이터 테이블(if/else 사슬 아님),
  `CorporateLoan.java:126-130` · `InvestmentOrder.java:86-91` 의 `requireTransition()`.
- **불변식의 구성적 강제**: `LedgerEntry.balancedPairForSettlement`(LedgerEntry.java:116-166)는
  payment = net + commission 회계 균형을 Money VO 로 검증한 뒤에만 전표를 생성 — 반쪽 전표가
  타입상 만들어질 수 없다. `AccountEntry` 정적 팩토리 6종(AccountEntry.java:83-126)이 이벤트→계정과목
  매핑을 도메인에 봉인.
- **파생값 봉인**: `Order.createMultiItem`(Order.java:89-117)은 `amount = subtotal - discount` 를
  팩토리 내부에서 계산해 외부가 금액을 임의 지정할 수 없다.
- **종료 상태 보호**: DONE 정산은 금액 직접 변경이 차단되고 `SettlementAdjustment` 를 통한
  보정만 허용(Settlement.java:273-278, 325-329). 홀드백 적용/해제/환불소진까지 애그리거트 내부 캡슐화.

### ② 캡슐화/불변성 — 9.7

- 5개 서비스 domain 전체 grep 실측: **public setter 0건, `@Setter`/`@Data` 0건**
  (lombok 은 read-only `@Getter` 만 사용).
- 핵심 애그리거트 전부 **private 생성자 + 정적 팩토리**(create/request/rehydrate/reconstitute) —
  `Settlement.java:69,98,107,130` · `Order.java:40,52,71` · `CorporateLoan.java:37,54,79` ·
  `InvestmentOrder.java:28,42,60` · `AccountEntry.java:49,76`.
- 식별·스냅샷 필드는 `final`(정산 시점 `commissionRate`, 심사 시점 `creditScore` 등 이력 보존 필드 포함).
  `id` 만 non-final 이며 15개 애그리거트에서 동일한 **write-once `assignId()` 가드**(1회 초과 시
  IllegalStateException)로 봉인.
- 잔여 편차 1건: `InvestmentOrder.id` 가 write-once 가드 관례에서 미세하게 느슨(노출 mutator 는
  전무해 실질 불변) — 리뷰어 B 지적, 실동작 영향 없음.

### ③ SOLID — 9.6

- **god class 없음** (전수 라인 측정): domain 최대 `Settlement.java` 481줄(정산+홀드백+환불+대사
  clawback 을 가진 가장 복잡한 애그리거트로서 합리적), application 최대 237줄, 유스케이스 단위 서비스 분리.
- **정책의 데이터화**: `CorporateCreditPolicy.java:52-78` 은 `Band<V>` 레코드 + `bandValue()` 단일
  조회로 임계값 테이블을 표현(if/else 사슬 회피), `InvestmentScorePolicy` · `TradePlanPolicy` 동형.
  `CreditPolicy` 는 LTV·일할이율·평판 haircut 을 설정 주입(app.loan.*)으로 외부화.
- **ISP/DIP**: 유스케이스별 세분화된 in/out 포트(예: order-service 90여 개 — `CreateOrderUseCase` /
  `ChangeOrderStatusUseCase` 분리), 도메인의 프레임워크 import 0 (grep 실측), 포트 우회 0 (ArchUnit 강제).
- 세 리뷰어 모두 구체 위반을 찾지 못했으나, 서비스 간 포트 세분화 granularity 편차(유스케이스 다양성
  차이로 비감점)를 관찰 — 5축 중 가장 보수적인 9.6.

### ④ 응집도/결합도 — 9.7

- **Money VO 경계 일관**: shared-common 승격 후 금융 4서비스 전역 동일 규칙(scale 2 · HALF_UP) —
  `Settlement.calculateCommissionAndNetAmount()`(148-152), `CorporateLoan.disburse/repay`(99, 115-118),
  `AccountEntry`(57-59, `Money.isPositive()` 로 양수 불변식 단일화). 저장 경계에서 `toBigDecimal` 환원,
  계산만 VO 통과.
- **명문화된 예외 경로**: `Order.createMultiItem` 은 의도적으로 Money 를 쓰지 않는데 이유가 주석으로
  명문화(Order.java:97-102 — 정수 KRW 정확 합산, Money 통과 시 scale 2 변환이 MSA 프로젝션 금액
  비교 drift 유발) — 공정성 조항상 비감점.
- 서비스별 status enum·VO 중복은 MSA 코드 경계상 필연 — 비감점.
- **실측 감점 1건** (리뷰어 C): `CorporateLoan.request()`(56-73)의 유효성 검사와
  `CorporateCreditPolicy.creditLimit/fee`(135-141, 149-157)가 raw `BigDecimal` + 수동
  `setScale(2, HALF_UP)` 로 Money 의 반올림 정책을 재현 — 같은 애그리거트 안에서 검증/정책 계산(raw)과
  상태 변경(Money)의 VO 경계가 주석 없이 갈리는 유일한 사례. 결과값은 정확(반올림 규칙 일치).

### ⑤ 예외 설계 — 9.8

- **계층 일관**: 단일 최상위 `BusinessException`(shared-common) → 서비스별 추상
  `{X}DomainException`(9개 BC) → 구체 leaf. 도메인 내 generic `IllegalArgumentException` **0건**
  (grep 전수 — CLAUDE.md 가드레일 준수), `IllegalStateException` 15건은 전부 write-once 가드의
  정당 용법.
- **컨텍스트 구조화 보존**: 메시지 문자열이 아니라 필드로 —
  `LoanInvariantViolationException`(19-40) 의 requested/limit,
  `CorporateLoanRejectedException.getRequested()/getLimit()`,
  `InvalidSettlementStateException(status, target)` 의 전이 컨텍스트.
- **HTTP 격리**: 도메인은 Spring/HTTP 무의존, 매핑은 어댑터 계층 예외 핸들러 + `ErrorCode` 카탈로그
  경유. loan 의 2개 예외가 `RuntimeException` 직접 상속인 것은 "도메인 순수성: JDK RuntimeException 만
  상속" 주석으로 명문화된 설계 판단 — 비감점.

## 남은 개선 여지 (감점 근거였던 항목)

1. `CorporateLoan` 애그리거트 내 raw BigDecimal ↔ Money VO 혼재 구간에 경계 주석 또는 Money 일원화
   (④ 축 0.2 감점의 유일한 실측 근거).
2. `InvestmentOrder.id` 의 write-once 관례를 타 애그리거트와 동일하게 정렬 (② 축 미세 편차).

## 판정

- 결정적 게이트 6/6 GREEN + 전 축 중앙값 ≥ 9.5 → **PASS (9.7/10)**.
- 이 점수의 회귀는 `guard.mjs` OO-* 규칙(실시간·pre-commit·CI)과 `oo-gate.test.mjs`(CI 전수)가
  기계적으로 차단하며, 재채점은 `/oo-score` 로 수행한다.
