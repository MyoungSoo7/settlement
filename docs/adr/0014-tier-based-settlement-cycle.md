# ADR 0014 — 셀러 등급별 T+N 정산 주기

- 상태: Accepted
- 일자: 2026-02-23

## 컨텍스트

모든 셀러를 동일한 정산 주기·수수료율로 처리하면 두 요구를 충족할 수 없다.

- **차등 정산 속도**: 대형 전략 파트너는 빠른 현금 회전(T+1)을 원하고, 일반 셀러는 환불 리스크를
  흡수할 시간 버퍼(T+7)가 필요하다.
- **차등 수수료**: 거래액·신뢰도가 높은 셀러에게 낮은 수수료를 적용해야 경쟁력이 있다. 또한
  정산 시점에 적용된 요율은 추후 정책이 바뀌어도 **이력으로 보존**되어야 한다(재계산 금지).

정산일은 단순 캘린더 더하기로는 부정확하다. T+N 은 **영업일** 기준이며 주말·한국 공휴일을 건너뛰어야
셀러에게 약속한 지급일이 맞는다.

## 결정

셀러 등급(`SellerTier`)을 기준으로 정산 주기와 수수료율을 차등 적용하고, 적용값을 정산 시점에
스냅샷 보존한다.

### 1. 등급별 주기·요율 (`SellerTier`)

| 등급 | 수수료율 | 기본 정산 주기 |
|---|---|---|
| NORMAL | 3.5% (`0.0350`) | T+7 영업일 (`SettlementCycle.T_PLUS_7`) |
| VIP | 2.5% (`0.0250`) | T+3 영업일 (`SettlementCycle.T_PLUS_3`) |
| STRATEGIC | 2.0% (`0.0200`) | T+1 영업일 (`SettlementCycle.T_PLUS_1`) |

`SellerTier.rate()` 와 `SellerTier.defaultCycle()` 이 단일 출처. 레거시 기본 3% 는
`Settlement.COMMISSION_RATE` 상수로만 보존하며 운영 요율은 등급 기준을 쓴다.

### 2. `users.settlement_cycle` 우선

`users.settlement_cycle` 컬럼이 명시값을 가지면 그것이 등급 default 보다 우선이다
(`SellerTier.defaultCycle()` 은 미지정 시 fallback). 운영팀이 특정 셀러의 주기를 개별 조정 가능.

### 3. 영업일 기준 정산일 계산 (`SettlementCycle` + `BusinessDayCalculator`)

`SettlementCycle.resolveSettlementDate(paymentDate)` 가 결제일로부터 정산일을 산출한다.
T+N 값들은 `BusinessDayCalculator.addBusinessDays(...)` 로 토·일 + 한국 고정 공휴일
(`KOREAN_FIXED_HOLIDAYS`)을 건너뛴다. 공휴일 정밀화는 외부 캘린더 주입으로 확장한다.

### 4. 수수료율 스냅샷 영구 보존 (V32 `commission_rate`)

정산 생성 시 적용 요율을 `Settlement.commissionRate` 에 저장(V32 `commission_rate` 컬럼).
`createFromPayment(..., commissionRate)` 팩토리가 등급 rate 를 받아 `commission`/`netAmount` 를
계산하고 그 요율을 영구 보존한다. 추후 요율 정책이 바뀌어도 과거 정산은 영향받지 않는다(이력 불변).

## 결과

### 좋아지는 점
- 셀러 가치에 따른 차등 정산 속도·수수료로 파트너십 경쟁력 확보
- 정산 시점 요율 스냅샷으로 과거 정산 재계산 리스크 제거
- 영업일 계산으로 약속 지급일 정확성 확보

### 트레이드오프 / 리스크
- 등급·주기·요율 매트릭스 유지 비용(등급 추가 시 정책 동기화 필요)
- 한국 공휴일이 코드 상수라 음력 명절은 외부 캘린더 주입 전까지 부정확
- `users.settlement_cycle` 개별 override 가 등급 정책과 어긋날 수 있어 운영 규율 필요

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 전 셀러 단일 주기·요율 | ✗ | 차등 정산·수수료 요구 미충족 |
| 셀러별 자유 입력(등급 없음) | ✗ | 정책 일관성 없음, 운영 부담 폭증 |
| 단순 캘린더 T+N (영업일 무시) | ✗ | 주말·공휴일에 약속 지급일 어긋남 |
| **등급 매트릭스 + 요율 스냅샷 + 영업일 (본 결정)** | ✓ | 차등 정책 + 이력 불변 + 지급일 정확 |

## 참조

- [0002 — 정산 상태 머신](0002-settlement-state-machine.md)
- [0015 — 정산 홀드백 정책](0015-settlement-holdback-policy.md)
