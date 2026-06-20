# ADR 0014 — SellerTier 기반 T+N 영업일 정산 주기

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

기존 `SettlementCycle` 은 DAILY/WEEKLY_MON/MONTHLY_LAST 만 있어 한국 이커머스의 표준
정산 주기 (T+1, T+3, T+7) 를 표현 불가. 또한 토/일/공휴일을 건너뛰지 않아 5/2 토요일 같은
비영업일에 정산일이 잡히는 비현실적 동작.

## 결정

### T+N 영업일 cycle 추가

```java
T_PLUS_1  // STRATEGIC default — 대형 전략 파트너 빠른 자금 회전
T_PLUS_3  // VIP default
T_PLUS_7  // NORMAL default — 환불·분쟁 흡수 시간 확보
```

각 cycle 의 `resolveSettlementDate` 가 `BusinessDayCalculator.addBusinessDays` 를 사용해
**주말 + 한국 고정 공휴일 8개** 를 자동 건너뜀.

### SellerTier ↔ Cycle 매핑

```java
SellerTier.STRATEGIC.defaultCycle() == T_PLUS_1
SellerTier.VIP.defaultCycle()       == T_PLUS_3
SellerTier.NORMAL.defaultCycle()    == T_PLUS_7
```

`users.settlement_cycle` 컬럼이 명시적으로 다른 값을 가지면 그것이 우선 — 셀러별 커스터마이징
가능 (예: 신규 셀러는 T+14 등).

### 한국 공휴일 처리

`BusinessDayCalculator.KOREAN_FIXED_HOLIDAYS` 에 양력 고정 공휴일 8 개 (신정/삼일절/
어린이날/현충일/광복절/개천절/한글날/성탄절). 음력 명절 (설/추석) 은 외부 캘린더 서비스
연동 단계에서 보강.

## 결과

- 셀러 등급별 자금 회전 차등 — 이커머스 솔루션 회사 핵심 기능
- 영업일 계산이 도메인 순수 로직 (Spring/JPA 의존 0) — 테스트 용이
- 단위 테스트 13건 (BusinessDayCalculator 7 + SettlementCycle/Tier 6)

## 대안

- **외부 라이브러리** (예: jollyday): 의존성 추가 부담. 한국 캘린더 정확도가 떨어지는 경우 많음
- **DB 캘린더 테이블**: 운영 편의성 좋지만 도메인 순수성 깨짐 — 외부 캘린더 서비스 연동 시점에 교체
- **DAILY 만 유지**: 솔루션 회사 면접에서 가장 자주 묻는 질문이 "주말 정산 어떻게 처리?" 임

## 참조

- [BusinessDayCalculator.java](../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/BusinessDayCalculator.java)
- [SettlementCycle.java](../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/SettlementCycle.java)
- [SellerTier.java](../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/SellerTier.java)
