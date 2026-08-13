# 투자-대출 통합 플랫폼 설계 (2026-07-13)

## 목적

이 설계의 목적은 기존 레포의 `investment-service`, `loan-service`, `market-service`를 각각 독립 MSA로 강화하되, 사용자는 하나의 금융 플랫폼처럼 느끼도록 만드는 것이다.

핵심 원칙은 다음과 같다.

- `공통 금융 프로필`을 먼저 만들고, 투자와 대출은 각자 독립 서비스로 운영한다.
- 대출 MVP는 `상품 비교 -> 사전심사 -> 제휴 금융사 송객` 흐름으로 시작한다.
- 투자 MVP는 KOSPI/KOSDAQ 종목을 비교하고 투자 점검을 돕는 프로토타입으로 시작한다.
- 두 서비스는 데이터와 실행 경계를 공유하지 않고, 공통 프로필과 이벤트만 공유한다.

## 범위

### 포함

- 사용자 공통 금융 프로필
- 신용점수 수기 입력
- 외부 신용평가사/마이데이터 기반 공식 점수 연동
- 개인 신용대출 사전심사
- 제휴 금융사 송객형 대출 오리진레이션
- 투자 상품 비교 프로토타입
- KOSPI/KOSDAQ 종목 조회 및 비교
- 투자 성향/초보자 점검
- 서비스 간 이벤트 기반 연계

### 제외

- 플랫폼 직접 대출 실행
- 플랫폼 직접 투자집행/주문 대행
- P2P 자금 매칭
- 법인/사업자 대출의 1차 MVP
- 레버리지, 신용융자, 파생상품

## 사용자 경험

### 첫 화면

첫 화면은 `상품 비교` 중심이다.

- 대출: 조건이 비슷한 제휴 금융사 상품을 비교한다.
- 투자: KOSPI/KOSDAQ 종목을 비교한다.

이유는 사용자가 먼저 “무엇이 있는지”를 보고, 그 다음에 자신의 프로필과 맞는 선택지를 좁히는 흐름이 이해하기 쉽기 때문이다.

### 대출 흐름

1. 사용자가 공통 금융 프로필을 만든다.
2. 신용점수를 직접 입력하거나, 공식 점수 조회를 연결한다.
3. 소득, 재직, 부채 정보를 입력한다.
4. 플랫폼이 자동 사전심사를 수행한다.
5. 고위험 또는 경계 사례만 수동 검토로 넘긴다.
6. 통과한 건은 제휴 금융사 상품 목록으로 송객한다.

### 투자 흐름

1. 사용자가 공통 금융 프로필을 만든다.
2. KOSPI/KOSDAQ 종목과 시장 데이터를 조회한다.
3. 관심 종목을 비교하고, 투자 적합성 체크리스트를 본다.
4. 투자 실행은 별도 투자 서비스에서 관리한다.

## 아키텍처

### 서비스 경계

```
gateway-service
  -> profile-service
  -> loan-service
  -> investment-service
  -> market-service
  -> notification-service(선택)
```

### 서비스 역할

- `profile-service`
  - 사용자 기본 정보, 신용점수, 소득, 재직, 부채, 투자성향을 보관한다.
  - 다른 서비스가 읽을 수 있는 표준 프로필 뷰를 제공한다.
  - 원본 증빙은 저장하되, 심사 판단은 서비스별로 따로 한다.

- `loan-service`
  - 신용점수 + 소득/재직 중심의 사전심사를 수행한다.
  - 승인/보류/거절 결정을 만들고, 제휴 금융사로 송객한다.
  - 최종 대출 실행은 파트너 금융사 책임으로 둔다.

- `investment-service`
  - 투자 비교, 초보자 점검, 종목/시장 요약을 제공한다.
  - KOSPI/KOSDAQ 종목을 기반으로 투자 프로토타입을 제공한다.
  - 실제 주문 실행은 별도 경계로 유지한다.

- `market-service`
  - KOSPI/KOSDAQ 종목, 시세, 기본 메타데이터를 제공한다.
  - 투자 서비스는 시장 데이터를 여기서 읽는다.

### 데이터 소유권

- `profile-service`만 공통 금융 프로필의 원장 역할을 가진다.
- `loan-service`는 대출 심사 결과와 송객 이력을 소유한다.
- `investment-service`는 투자 비교 상태와 투자 점검 결과를 소유한다.
- `market-service`는 시장 데이터의 읽기 전용 소스를 소유한다.

각 서비스는 자기 DB를 가진다. 서비스 간 조인은 하지 않는다.

## 데이터 모델

### 공통 금융 프로필

프로필은 사용자 단위의 읽기 모델로 둔다.

- userId
- identityStatus
- incomeRange
- employmentStatus
- debtSummary
- creditScoreManual
- creditScoreOfficial
- investmentProfile
- updatedAt

신용점수는 두 값을 모두 둘 수 있다.

- `creditScoreManual`: 사용자가 입력한 값
- `creditScoreOfficial`: 외부 기관에서 받은 공식 값

심사에서는 공식 값이 있으면 우선하고, 없으면 수기 입력값을 보조 신호로 사용한다.

### 대출 도메인

- LoanApplication
- PreApprovalDecision
- PartnerLoanProduct
- ReferralRecord
- ManualReviewCase

대출 상태는 `draft -> preapproved|review_required|rejected -> referred -> closed` 흐름으로 둔다.

### 투자 도메인

- InvestmentWatchlist
- MarketComparison
- StockSnapshot
- InvestmentReadinessCheck
- PortfolioHint

투자 상태는 `watch -> compare -> check -> proceed_or_stop` 흐름으로 둔다.

## 이벤트와 연계

MSA 간 연계는 이벤트와 조회 API만 쓴다.

### 이벤트

- `profile.updated`
- `credit-score.official-updated`
- `loan.preapproval.completed`
- `loan.referral.created`
- `investment.readiness.evaluated`
- `market.snapshot.updated`

### 연계 원칙

- 프로필 업데이트는 outbox로 발행한다.
- 대출 사전심사는 프로필 이벤트를 받아 갱신한다.
- 투자 비교는 시장 스냅샷 이벤트를 받아 갱신한다.
- 서비스 간 직접 DB 접근은 금지한다.

## 심사 규칙

### 대출 MVP

자동 사전심사 기준은 다음으로 고정한다.

- 신용점수
- 소득
- 재직 여부

판정은 점수화하되, 규칙 기반 거절 사유를 먼저 남긴다.

고위험 조건 예시는 다음과 같다.

- 신용점수 미제공
- 소득 미검증
- 재직 불안정
- 기존 부채가 과도함

수동 검토는 고위험만 대상으로 한다. 나머지는 자동 처리한다.

### 투자 MVP

투자 쪽은 수익 예측이 아니라 비교와 경고에 집중한다.

- KOSPI/KOSDAQ 분류
- 가격/변동성/뉴스 위험 신호
- 초보자 회피 조건
- 분산 여부와 종목 편중 점검

투자 추천은 단정형으로 내지 않고, 기준 충족 여부를 보여주는 방식으로만 표현한다.

## API 초안

### profile-service

- `GET /api/profile/me`
- `PUT /api/profile/me`
- `POST /api/profile/me/credit-score/manual`
- `POST /api/profile/me/credit-score/official/refresh`

### loan-service

- `GET /api/loan/products`
- `POST /api/loan/applications`
- `POST /api/loan/applications/{id}/preapprove`
- `POST /api/loan/applications/{id}/review`
- `POST /api/loan/applications/{id}/refer`

### investment-service

- `GET /api/investment/markets/kospi`
- `GET /api/investment/markets/kosdaq`
- `GET /api/investment/stocks/{stockCode}`
- `POST /api/investment/watchlists`
- `POST /api/investment/readiness/check`

## 오류 처리

- 공식 신용점수 조회 실패 시, 수기 입력값으로만 진행하지 않고 `official-score-unavailable` 상태를 남긴다.
- 소득 또는 재직 정보가 불충분하면 대출은 `review_required`로 보낸다.
- 시장 데이터가 없으면 투자 비교는 `partial` 상태로 응답한다.
- 외부 연동 실패는 재시도 가능 오류와 사용자가 고칠 수 있는 입력 오류로 분리한다.
- 제휴 금융사 연동 실패는 송객 미완료로 기록하고 재시도 큐에 넣는다.

## 보안과 컴플라이언스

- 투자 출력은 수익 확정, 원금 보전, 무위험 같은 확정형 표현을 쓰지 않는다.
- 대출 출력은 승인 단정을 피하고, 사전심사 결과와 제휴사 최종심사를 분리해서 표시한다.
- 민감정보는 마스킹하고, 원본 증빙은 최소 권한으로 제한한다.
- 사용자 입력과 외부 점수는 출처를 남긴다.

투자/대출 관련 안내문과 매수·매도 판단형 출력은 다음 고지문으로 끝낸다.

> 본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다.
> 투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.

## 테스트 전략

### 공통

- 서비스 경계 ArchUnit 테스트
- 이벤트 발행/소비 멱등성 테스트
- outbox 적재 및 재처리 테스트

### 대출

- 신용점수 + 소득/재직 규칙 테스트
- 수동 검토 분기 테스트
- 제휴사 송객 실패 복구 테스트

### 투자

- KOSPI/KOSDAQ 비교 테스트
- 초보자 점검 테스트
- 시장 데이터 누락 시 partial 응답 테스트

### 통합

- 프로필 업데이트가 대출/투자 읽기 모델에 반영되는지 검증
- 공통 금융 프로필이 두 서비스에서 일관되게 읽히는지 검증

## 단계적 실행

### Phase 1

- `profile-service`의 공통 금융 프로필
- `loan-service`의 사전심사와 송객
- 신용점수 수기 입력 + 공식 조회 병행

### Phase 2

- `market-service`의 KOSPI/KOSDAQ 데이터 노출 강화
- `investment-service`의 비교형 프로토타입
- 투자 초보자 점검과 경고

### Phase 3

- 추천 정교화
- 상품 비교 경험 통합
- 파트너 금융사 및 투자 파트너 확장

## 비목표

- 플랫폼이 직접 돈을 빌려주거나 자산을 매수하지 않는다.
- 신용점수만으로 자동 승인하지 않는다.
- 투자 수익률을 약속하지 않는다.
- 대출과 투자 데이터를 한 DB에서 섞지 않는다.
