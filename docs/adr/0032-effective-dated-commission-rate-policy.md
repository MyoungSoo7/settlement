# ADR 0032 — 수수료율 유효기간 정책 (effective-dated rate + scope 우선순위)

- 상태: **Proposed** (결정 대기 — 오너 확정 필요 항목 4건)
- 일자: 2026-08-06
- 관련: ADR 0014(등급별 요율 — 본 ADR 이 **확장**하는 대상), ADR 0004(DONE 정산 불변),
  ADR 0020(프로젝션 CQRS — CATEGORY scope 제약의 원인), ADR 0031(셀러 등급 라이프사이클),
  `settlement-domain-rules`·`money-safety` 스킬
- 배경: ADR 0014 는 "등급 추가 시 정책 동기화 필요"를 **트레이드오프로 이미 기록**했다. 본 ADR 이
  그 유지 비용을 코드 배포에서 데이터 운영으로 옮긴다.

## 컨텍스트

현재 수수료율의 단일 출처는 **enum 상수**다.

```java
// SellerTier.java:11-15
NORMAL   ("0.0350", T_PLUS_7, "0.30", 30),
VIP      ("0.0250", T_PLUS_3, "0.10", 14),
STRATEGIC("0.0200", T_PLUS_1, "0",     0);
```

정산 생성 시 이 값을 해석해(`CreateSettlementFromPaymentService.java:84`) `settlements.commission_rate` 에
스냅샷한다(ADR 0014 §4). **스냅샷 원칙 자체는 옳고 본 ADR 도 유지한다.** 문제는 그 스냅샷에 넣을
"현재 요율"을 결정하는 방식이다.

### 한계 4가지

| 요구 | 현재 가능? |
|---|---|
| "2026-09-01 부터 VIP 2.5%→2.3%" | ✗ — **코드 수정 + 배포**. 배포 시점과 발효 시점이 어긋난다 |
| "이 셀러는 계약상 1.8%" | ✗ — 표현 자체가 불가. 등급을 억지로 올리는 우회가 유일한 수단이고, 그러면 정산주기·홀드백까지 함께 바뀐다 |
| "블프 2주 한시 인하" | ✗ — 배포로 올리고 배포로 되돌려야 한다 |
| "이 요율은 누가 언제 왜 정했나" | ✗ — git blame 이 유일한 근거 |

세 번째 우회(등급 남용)가 특히 나쁘다. ADR 0014 가 등급 하나로 3축을 묶어둔 설계라, **요율만 조정하고 싶을 때
쓸 수 있는 손잡이가 없어서** 등급이 오용된다. ADR 0031 이 등급을 자동 산정으로 바꾸면 이 우회는 아예
불가능해진다 — 두 ADR 이 짝인 이유다.

> 사례 조사: 동종 커머스 코드베이스(`ofDentis`)는 `tb_grade_product_rate` 를 PK
> `(category, product_id, grade_id, is_promotion)` + `open_date`/`close_date` + rate 로 운영하고 있었다.
> 요율을 **기간이 있는 데이터**로 다루는 구조는 그대로 참고할 만하다. 단 그 구현은 요율에 `Float` 를 써서
> 우리 금액 가드레일과 정면 충돌한다 — 구조만 취하고 타입은 `BigDecimal` 로 간다.

## 결정 포인트 (오너 확정 필요)

### 1. 정책을 어느 서비스가 소유하는가?
→ **settlement-service 권장.** 요율을 실제로 적용해 금액을 만드는 주체이고, 자기 DB 안에서 해석이 끝난다.
order 에 두면 정산이 요율을 조회하려 경계를 넘어야 한다.

### 2. scope 우선순위는?
→ **SELLER > TIER**, 미매칭 시 `SellerTier.rate()` 폴백. (CATEGORY 는 아래 4번 참조.)
   가장 구체적인 계약이 이긴다는 상식적 규칙이며, 해석 결과가 항상 유일해진다.

### 3. 같은 scope 안에서 기간이 겹치면?
→ **DB 제약으로 금지.** 우선순위로 푸는 방식(예: 나중에 등록한 것이 이김)은 조회 시점마다 결과가 달라져
   "왜 이 요율이 적용됐나"를 설명할 수 없게 만든다. PostgreSQL `EXCLUDE` 제약으로 **입력 시점에 차단**한다.

### 4. CATEGORY scope 를 1단계에 넣는가?
→ **넣지 않는다(2단계로 연기).** 이유가 구조적이다: settlement 은 결제 정보를 프로젝션
   (`settlement_payment_view`)으로만 본다(ADR 0020). 그 뷰에는 `seller_id`·`seller_tier`·`settlement_cycle`
   은 있어도 **카테고리가 없다**(`SettlementPaymentViewJpaEntity.java:29-64` 확인). CATEGORY 요율을 하려면
   `PaymentCaptured` payload 확장 → 계약 스키마 개정(ADR 0024) → 뷰 컬럼 추가 → 백필까지 필요하다.
   1단계 가치(요율 발효일 + 셀러 예외)의 대부분은 CATEGORY 없이 얻어지므로 분리한다.

### 5. 과거 소급 정책 등록을 허용하는가?
→ **차단 권장.** `effective_from < today` 인 정책 등록은 거부한다. 이미 생성된 정산은 스냅샷이라 재계산되지
   않으므로(ADR 0004·0014), 소급 등록은 **정산 결과를 바꾸지 못한 채 장부와 정책만 어긋나게** 만든다.
   진짜 소급 보정이 필요하면 정식 경로는 `SettlementAdjustment`(ADR 0004)다.

## 설계

### 1. 스키마 (settlement_db)

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- EXCLUDE 에 equality 열 포함 위해 필요

CREATE TABLE commission_rate_policy (
    id             BIGSERIAL    PRIMARY KEY,
    scope          VARCHAR(16)  NOT NULL,
    scope_key      VARCHAR(64)  NOT NULL,      -- SELLER:'12345' | TIER:'VIP'
    rate           NUMERIC(6,5) NOT NULL,      -- BigDecimal. 0.02500 = 2.5%
    effective_from DATE         NOT NULL,
    effective_to   DATE,                       -- NULL = 무기한
    reason         VARCHAR(255) NOT NULL,      -- 필수: 왜 이 요율인가
    created_by     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,                -- 조기 종료 시각 (행 UPDATE 대신 close)
    CONSTRAINT chk_crp_scope CHECK (scope IN ('SELLER','TIER')),
    CONSTRAINT chk_crp_rate  CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT chk_crp_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_crp_no_overlap EXCLUDE USING gist (
        scope     WITH =,
        scope_key WITH =,
        daterange(effective_from, COALESCE(effective_to, DATE '9999-12-31'), '[)') WITH &&
    )
);
```

**행 UPDATE 금지.** 요율을 바꾸려면 기존 행을 `effective_to` 로 닫고 새 행을 넣는다 — 원장 `POSTED` 불변
(ADR 0007 계열)과 같은 규율이다. 이력이 곧 테이블이므로 별도 이력 테이블이 필요 없다.

### 2. 도메인

```
settlement-service/.../settlement/domain/
├── CommissionRatePolicy.java     # 값 객체: scope, scopeKey, rate, 유효기간, isEffectiveOn(date)
├── ResolvedRate.java             # record(BigDecimal rate, RateSource source)
└── RateSource.java               # SELLER | TIER | DEFAULT_ENUM
```

해석은 도메인의 순수 함수로:

```java
// 후보들을 받아 우선순위로 하나를 고른다. DB·시계 접근 없음.
ResolvedRate resolve(List<CommissionRatePolicy> candidates, SellerTier tier, LocalDate at);
```

- 후보 조회는 `LoadCommissionRatePolicyPort`(out) 담당.
- **폴백이 항상 존재한다** — 매칭 0건이면 `SellerTier.rate()`(현행 enum). 즉 **정책 테이블이 비어 있으면
  오늘과 100% 동일하게 동작한다.** 무행동 착지가 가능하다는 뜻이고, 이것이 이 설계의 안전판이다.

### 3. 적용 지점 (단 한 곳)

`CreateSettlementFromPaymentService.java:84` 의 요율 해석만 교체한다.
`Settlement.createFromPayment(..., commissionRate)` 시그니처·스냅샷 동작은 **변경 없음**.

### 4. 적용 근거의 추적 (`settlement-explain` 연계)

`settlements` 에 `commission_rate_source VARCHAR(32)` 컬럼을 추가한다 (예: `SELLER:12345`, `TIER:VIP`,
`DEFAULT`). 셀러 문의 시 "왜 3.5% 인가"에 정확히 답하기 위한 최소 정보이며, `settlement-explain` 스킬의
설명 품질이 이 컬럼에 직접 의존한다. 기존 행은 NULL → `DEFAULT` 로 해석한다.

### 5. 불변식 (테스트로 못박을 것)

1. 요율은 `BigDecimal`. `double`/`float` 금지 — 사례 코드베이스가 `Float` 로 한 바로 그 지점.
2. 기간 중첩은 **DB 가 거부**한다(EXCLUDE). 애플리케이션 검증은 보조일 뿐.
3. 정책 행 UPDATE 금지 — close + new row.
4. 해석 결과는 **항상 존재**한다(폴백 enum). null 반환 경로 없음.
5. `effective_from < today` 등록 거부(결정 포인트 5).
6. 정산 생성 후 정책이 바뀌어도 **기존 정산은 불변**(ADR 0014 §4 회귀 테스트로 고정).

### 6. 운영 API

```
GET    /admin/commission-rates?scope=&scopeKey=&at=      # 현재/특정일 유효 정책 조회
POST   /admin/commission-rates                            # 신규 (reason 필수)
POST   /admin/commission-rates/{id}/close                 # 조기 종료
GET    /admin/commission-rates/simulate?sellerId=&at=     # 해석 결과 + source 미리보기
```

`simulate` 는 `fee-audit` 스킬의 교차검증 대상으로 쓴다.

## 결과

### 좋아지는 점
- 요율 변경이 **배포 없이, 발효일과 함께** 가능해진다.
- 셀러별 계약 요율이 정식으로 표현된다 → 등급 오용 경로 제거(ADR 0031 과 짝).
- 요율의 근거(reason·created_by·기간)가 데이터로 남는다.
- 적용 근거(`commission_rate_source`)가 정산 문의 대응에 직접 쓰인다.

### 트레이드오프 / 리스크
- `btree_gist` extension 의존 추가 — settlement_db 한정. Testcontainers 이미지에서 사용 가능 여부 **선확인 필요**.
- 정책 오등록의 영향이 즉각적이다(다음 정산부터 반영). → `simulate` + dryRun 을 운영 절차로 강제.
- 요율 해석 쿼리가 정산 생성 경로에 1건 추가된다. 캐시는 **넣지 않는다**(발효일 경계에서 stale 위험 > 이득).
- CATEGORY scope 미지원이 1단계 한계로 남는다(결정 포인트 4).

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 현행 enum 유지 | ✗ | 요율 변경에 배포 필요 · 셀러 예외 불가 · 감사 불가 |
| enum + 셀러별 override 컬럼(`users.commission_rate`) | ✗ | 기간 개념이 없어 예약 변경·한시 인하 불가. 이력도 안 남음 |
| **effective-dated 정책 테이블 + enum 폴백(본 결정)** | ✓ | 무행동 착지 가능 · 기간/예외/감사 동시 해결 |
| 정책을 order-service 소유로 | ✗ | 정산이 요율 조회로 경계를 넘어야 함 |
| 기간 중첩을 우선순위로 해소 | ✗ | 적용 근거 설명 불가 |

## 구현 체크리스트

- [ ] `btree_gist` 가용성 확인 (로컬 · Testcontainers · 운영 PG17)
- [ ] Flyway `V{timestamp}__commission_rate_policy.sql` (+ `settlements.commission_rate_source`)
- [ ] `resolve()` 순수 함수 단위 테스트 — 우선순위·경계일(`[from, to)`)·폴백·빈 테이블
- [ ] EXCLUDE 제약 통합 테스트 — 중첩 INSERT 가 **DB 레벨에서** 거부되는지 (Testcontainers)
- [ ] 회귀 테스트: 정책 테이블이 비면 기존 정산 금액과 **완전 동일**
- [ ] 회귀 테스트: 정산 생성 후 정책 변경 → 기존 정산 불변 (ADR 0014 §4)
- [ ] `fee-audit` 스킬에 `simulate` 교차검증 추가
- [ ] `./gradlew :settlement-service:test :settlement-service:jacocoTestCoverageVerification` 통과
