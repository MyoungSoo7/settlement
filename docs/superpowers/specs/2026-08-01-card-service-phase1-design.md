# card-service 1단계 설계 — 셀러 법인카드 심사·발급

## 1. 목적과 범위

셀러(입점 판매자)를 대상으로 하는 법인카드 서비스 `card-service`를 신설한다. 법인 단위로 카드 한도를 심사해 부여하고, 그 한도 안에서 조직 소유자가 임직원에게 카드를 발급하고 개별 한도를 배분하는 데까지를 1단계로 정의한다.

한도의 근거는 외부 신용조회가 아니라 이 플랫폼이 이미 보유한 셀러의 정산 데이터다. 셀러에게 지급이 확정되었으나 아직 나가지 않은 정산금이 카드 한도의 재원이 된다.

### 1.1 1단계에 포함하는 것

- 법인 카드계정 개설과 한도 심사
- 임직원 카드 발급과 서브한도 배분
- 카드 정지·해지, 조직 이탈자 카드 자동 정지
- 일 1회 한도 재산정
- 2단계에서 사용할 승인·매입 이벤트의 JSON Schema 확정

### 1.2 1단계에 포함하지 않는 것

- 카드 승인(authorization)과 매입(capture) 처리 — 2단계
- 이용대금 청구 사이클, 결제일 회수, 연체 — 3단계
- 지출관리(영수증 제출, 부서별 결산, ERP 연동) — 4단계
- 실제 카드사 연동. 1단계는 Mock 어댑터로 대체한다.

### 1.3 설계 결정 요약

| 결정         | 선택                               | 근거                                                                |
| ------------ | ---------------------------------- | ------------------------------------------------------------------- |
| 발급 대상    | 셀러(입점 판매자)                  | 정산 데이터가 실재하므로 심사가 mock 이 아닌 실데이터로 동작한다    |
| 재원 정의    | 확정·미지급 정산금 + 홀드백 유보분 | 선정산 대출(미확정분)·투자(확정 가용분)와 재원 성격이 겹치지 않는다 |
| 임직원 단위  | 1단계에 포함                       | 이것이 없으면 loan-service 의 여신과 구별되지 않는다                |
| 재원 조회    | account-service 동기 조회          | 회계 잔액과 카드 한도가 구조적으로 어긋날 수 없다                   |
| 1단계 종료선 | 발급까지                           | 승인은 2단계. 단 이벤트 계약은 1단계에서 확정한다                   |

## 2. 재원의 정의

`settlement.confirmed` 이벤트의 `amount` 는 수수료를 차감한 `netAmount` 이며 홀드백을 포함한다. 이 금액이 셀러에게 도달하기까지 빠져나가는 경로는 네 가지다.

| 경로      | 이벤트                                  | 성격             |
| --------- | --------------------------------------- | ---------------- |
| 실지급    | `lemuel.payout.completed`               | 현금 유출        |
| 유보 소진 | `lemuel.settlement.holdback_consumed`   | 조정으로 소멸    |
| 채권 상계 | `lemuel.seller_recovery.offset`         | 기존 채권과 상계 |
| 원천징수  | `lemuel.settlement.withholding_accrued` | 세액 공제        |

`lemuel.settlement.holdback_released` 는 유보금을 지급 가능 상태로 재분류할 뿐 총액을 바꾸지 않는다.

따라서 카드 재원은 다음과 같다.

```
F = Σ confirmed − Σ payout − Σ holdback_consumed − Σ recovery_offset − Σ withholding
```

이 값은 account-service 가 이미 유지하고 있다. account-service 는 위 이벤트를 모두 소비해 셀러별 `SELLER_PAYABLE` 과 `HOLDBACK_PAYABLE` 계정 잔액을 복식부기로 관리하고, `account_balances` 실체화 테이블과 `BalanceReconScheduler` 정합 배치를 운영한다. `F` 는 이 두 계정 잔액의 합과 같다.

card-service 는 이 계산을 재구현하지 않고 account-service 에 조회한다. 동일한 계산을 두 서비스가 각자 수행하면 한쪽이 이벤트를 놓쳤을 때 카드 한도와 회계 장부가 조용히 어긋난다. 조회 방식에서는 그런 이탈이 구조적으로 발생하지 않으며, 특정 셀러의 한도가 왜 그 금액인지를 시산표로 설명할 수 있다.

### 2.1 재원 정의의 한계

`F` 는 곧 셀러에게 지급될 돈이다. 카드 한도로 잡아두더라도 정산 지급 자체를 막지는 않으므로, 셀러가 카드를 쓴 뒤 정산금을 전액 수령하면 같은 재원이 두 번 사용된다. 이를 실제로 막으려면 payout 산정 시 카드 이용액만큼 유보하거나 상계해야 하며, 그것은 3단계 청구 사이클의 범위다.

1단계에서는 인정비율 `R = 0.70` 이 이 위험을 흡수하는 완충 역할을 한다. 이 값은 위험 흡수를 위한 설정값이며, 3단계에서 상계가 구현되면 재조정 대상이다.

## 3. 서비스 경계

### 3.1 배치

| 항목            | 값                                   |
| --------------- | ------------------------------------ |
| 모듈            | `card-service`                       |
| 패키지          | `github.lms.lemuel.card`             |
| 포트            | 8106                                 |
| DB              | `lemuel_card`                        |
| 게이트웨이 경로 | `/api/cards/**` (`CARD_SERVICE_URI`) |

레이아웃은 organization-service 를 미러링한다. `adapter/in/web`, `adapter/out/{persistence,event,external}`, `application/{port,service,exception}`, `domain`, `config`.

shared-common 에 의존한다. JWT 인증, Outbox, 멱등 컨슈머, `Money`, 감사 AOP, PII 마스킹, ShedLock 을 사용한다.

### 3.2 외부 연동

**account-service — 동기 조회.** `GET /internal/account/sellers/{sellerId}/funding` 을 신설한다. `X-Internal-Api-Key` 로 게이트하며, settlement 와 order 사이의 `/internal/recon` 과 같은 패턴이다. 응답은 `sellerPayable`, `holdbackPayable` 두 잔액이다.

**organization-service — 이벤트 구독.** `lemuel.organization.created`(type=SELLER, externalRef=sellerId), `member_joined`, 그리고 §3.3 에서 신설하는 `member_role_changed`·`member_removed` 네 종을 소비해 조직·멤버 프로젝션을 유지한다.

**company-service — 이벤트 구독.** `lemuel.company.reputation_changed` 를 소비해 평판 등급 프로젝션을 유지한다. loan-service 가 이미 같은 토픽을 구독한다.

**카드사 — Mock 어댑터.** `adapter/out/external/MockCardIssuerAdapter` 가 카드번호 채번과 발급 응답을 흉내낸다. settlement 의 `MockFirmBankingAdapter` 와 같은 처리이며, 실제 카드사를 붙일 자리는 출력 포트로만 열어둔다.

### 3.3 organization-service 이벤트 보강

organization-service 는 현재 `created` 와 `member_joined` 만 발행한다. 역할 변경과 멤버 제거에 대응하는 이벤트가 없어, 이 상태로 프로젝션을 구성하면 조직에서 제거된 임직원의 카드가 유효한 상태로 남는다. 법인카드에서 이는 허용할 수 없다.

1단계에 다음을 포함한다.

- organization-service 에 `lemuel.organization.member_role_changed` 와 `lemuel.organization.member_removed` 발행을 추가한다. Outbox 배선은 이미 존재하므로 이벤트 정의와 발행 지점만 추가한다.
- card-service 는 `member_removed` 수신 시 해당 임직원의 카드를 `SUSPENDED` 로 전이한다.

### 3.4 발행 이벤트

`aggregateType = "Card"` 로 Outbox 에 적재한다.

| 토픽                         | 시점                           | 1단계         |
| ---------------------------- | ------------------------------ | ------------- |
| `lemuel.card.account_opened` | 카드계정 개설·마스터 한도 확정 | 발행          |
| `lemuel.card.issued`         | 임직원 카드 발급               | 발행          |
| `lemuel.card.limit_changed`  | 마스터·서브 한도 변경          | 발행          |
| `lemuel.card.status_changed` | 정지·해지                      | 발행          |
| `lemuel.card.authorized`     | 카드 승인                      | 스키마만 확정 |
| `lemuel.card.captured`       | 매입 확정                      | 스키마만 확정 |

소비처는 account-service 다. 2단계에서 카드 이용액을 GL 에 인식하려면 계정과목 `CARD_RECEIVABLE` 이 필요하며, 1단계에서는 계약만 확정한다.

## 4. 도메인 모델

### 4.1 애그리거트

**`CardAccount`** — 법인(조직) 단위 카드계정. 조직당 하나다.

```
organizationId (UNIQUE) · sellerId · status · masterLimit
심사 스냅샷: screenedAt · sellerPayableSnapshot · holdbackPayableSnapshot
            · reputationGrade · appliedRatio · limitFormula
```

**`Card`** — 임직원 카드. `cardAccountId` 로 `CardAccount` 를 참조하는 별도 애그리거트다.

```
cardAccountId · holderUserId · maskedCardNo · subLimit · status
```

`Card` 를 `CardAccount` 내부 엔티티로 두지 않는다. 2단계의 카드 승인은 카드 단위 고빈도 연산인데, 계정 애그리거트에 묶으면 같은 법인의 임직원들이 서로의 승인을 차단한다. 한도 배분은 저빈도이므로 `CardAccount` 비관적 락으로 처리하고, 승인은 카드 단위 락으로 처리한다. 이 분리는 2단계에서 애그리거트 경계를 다시 설계하지 않기 위한 것이다.

### 4.2 불변식

- `masterLimit ≥ Σ subLimit` — 항상 성립한다. DB 제약으로 표현할 수 없으므로 한도 배분 유스케이스에서 `CardAccount` 를 비관적 락으로 잠그고 서브한도 합계를 재계산해 강제한다.
- 조직당 카드계정은 하나다 — `UNIQUE(organization_id)`.
- 임직원당 활성 카드는 한 장이다 — `(card_account_id, holder_user_id)` partial unique, `status != CANCELED` 조건. operation-service 의 활성 중복 방지와 같은 방식이다.
- 카드 발급 대상은 해당 조직의 `ACTIVE` 멤버여야 한다.
- 모든 금액은 `BigDecimal` 이며 shared-common 의 `Money` 를 사용한다.

### 4.3 상태머신

`CardAccount`: `SCREENING → ACTIVE ⇄ SUSPENDED → CLOSED`. 심사 탈락은 `REJECTED` 로 종료한다.

`Card`: `ISSUED ⇄ SUSPENDED → CANCELED`. `CANCELED` 는 터미널이다. `member_removed` 수신 시 `SUSPENDED` 로 전이한다.

Mock 발급은 동기이므로 발급 중간 상태를 두지 않는다. 발급 실패 시 트랜잭션을 롤백하며 카드가 생성되지 않는다.

### 4.4 권한

organization-service 의 `OrgAuthorizer` 를 미러링한다. 대상 조직은 요청에서 받되(신청은 본문의 `organizationId`, 그 외에는 경로의 `cardAccountId` 에서 유도), 권한은 요청 파라미터가 아니라 JWT 주체(`uid`)가 그 조직의 로컬 멤버 프로젝션에서 갖는 역할로 판정한다. 요청자가 해당 조직의 `ACTIVE` 멤버가 아니면 `403` 이다.

| 작업                                    | 허용 역할      |
| --------------------------------------- | -------------- |
| 카드계정 개설, 카드 발급, 서브한도 변경 | OWNER          |
| 카드 정지·해지, 카드 목록 조회          | OWNER, MANAGER |
| 본인 카드 조회                          | 본인           |

카드번호는 저장과 응답 모두 마스킹한다. shared-common 의 `PIIMaskingConverter` 가 로그 경로를 함께 차단한다.

## 5. 한도 정책

`CardLimitPolicy` 는 순수 도메인 정책으로 부수효과를 갖지 않는다.

```
F = sellerPayable + holdbackPayable          (account-service 조회)
R = 인정비율, 기본 0.70 (설정 주입)
H = 평판 haircut — A·B 1.0 / C 0.85 / D 0.70 / E 0.0

masterLimit = floor(F × R × H)
```

평판 등급은 company-service 의 `lemuel.company.reputation_changed` 를 구독해 로컬 프로젝션으로 유지한다. loan-service 의 `CreditPolicy` 가 사용하는 것과 같은 축이다.

평판이 E 등급이거나 산정액이 최소한도 `L_min` 에 미달하면 신청 시점에 `422` 로 거절한다. loan-service 의 신청 시점 거절 규약과 같다. `L_min` 은 `R` 과 마찬가지로 설정 주입값이며, 운영 가능한 최소 카드 한도를 뜻한다. 기본값은 300,000 원으로 둔다.

산정 근거(`F`, `R`, `H`, 적용 공식)를 `CardAccount` 에 스냅샷으로 보존한다. 사후에 특정 한도의 산출 근거를 재현할 수 있어야 한다. loan-service 가 신청 시점의 신용점수와 등급을 보존하는 것과 같은 이유다.

### 5.1 한도 재산정

일 1회 스케줄러가 `ACTIVE` 상태의 모든 카드계정에 대해 산정을 재실행한다. ShedLock 으로 다중 인스턴스 중복 실행을 방지한다.

- 상향은 그대로 반영한다.
- 하향은 `Σ subLimit` 을 하한으로 클램프한다. 이미 배분한 서브한도 아래로는 내리지 않는다.

이미 발급된 임직원 카드의 한도를 사전 통지 없이 축소하지 않기 위한 규칙이다. `card.limit_changed` 이벤트에 클램프 적용 여부를 포함해 발행한다.

## 6. 유스케이스 흐름

### 6.1 카드계정 개설(심사)

```
OWNER 신청
  → 조직 프로젝션에서 type=SELLER, externalRef=sellerId 확인
  → account-service 내부 API 로 F 조회
  → 평판 프로젝션에서 H 조회
  → CardLimitPolicy 산정 → masterLimit
  → SCREENING → ACTIVE, 산정 근거 스냅샷 저장
  → Outbox: card.account_opened
```

E 등급이거나 최소한도 미달이면 `422` 로 응답하고 `REJECTED` 로 기록한다.

### 6.2 임직원 카드 발급

```
OWNER 요청
  → 대상 userId 가 조직의 ACTIVE 멤버인지 프로젝션 확인
  → CardAccount 비관적 락
  → Σ subLimit + 신규 subLimit ≤ masterLimit 검증
  → MockCardIssuerAdapter 채번
  → Card ISSUED 저장
  → Outbox: card.issued
```

### 6.3 이벤트 수신

| 이벤트                             | 처리                                                    |
| ---------------------------------- | ------------------------------------------------------- |
| `organization.created`             | SELLER 타입이면 조직 프로젝션 적재                      |
| `organization.member_joined`       | 멤버 프로젝션 적재                                      |
| `organization.member_role_changed` | 멤버 프로젝션 역할 갱신 (권한 판정에 즉시 반영)         |
| `organization.member_removed`      | 해당 임직원 카드 `SUSPENDED` (멱등, 이미 정지면 무처리) |
| `company.reputation_changed`       | 평판 프로젝션 갱신                                      |

모든 컨슈머는 `IdempotentEventConsumer` 를 상속한다. 실패는 DLQ 로 보내고 `ConsumedEventQuarantine` 로 격리한다.

## 7. API 표면

base `/api/cards`. 전 경로 JWT 인증 필수.

| 메서드 | 경로                     | 권한           | 기능                    |
| ------ | ------------------------ | -------------- | ----------------------- |
| POST   | `/accounts`              | OWNER          | 카드계정 신청·심사      |
| GET    | `/accounts/{id}`         | 멤버           | 계정·한도·산정근거 조회 |
| POST   | `/accounts/{id}/cards`   | OWNER          | 임직원 카드 발급        |
| GET    | `/accounts/{id}/cards`   | OWNER, MANAGER | 카드 목록               |
| PATCH  | `/cards/{cardId}/limit`  | OWNER          | 서브한도 변경           |
| PATCH  | `/cards/{cardId}/status` | OWNER, MANAGER | 정지·해지               |
| GET    | `/cards/me`              | 본인           | 내 카드 조회            |

경로의 `{id}` 는 `cardAccountId` 다. 대상 조직은 이 값에서 유도한다. 카드계정 신청만 예외로 본문에 `organizationId` 를 받으며, 그 조직이 `type=SELLER` 이고 `externalRef` 가 해석 가능한 `sellerId` 여야 한다.

## 8. 에러 처리

shared-common 의 `BusinessException`, `ErrorCode`, `GlobalExceptionHandler` 를 사용한다.

| 상태 | 사유                                                                  |
| ---- | --------------------------------------------------------------------- |
| 422  | 심사 탈락(E 등급·최소한도 미달), 서브한도 합계 초과, 비멤버 대상 발급 |
| 403  | 역할 부족, 타인 카드 조회                                             |
| 409  | 조직당 카드계정 중복, 임직원당 활성 카드 중복                         |
| 503  | account-service 재원 조회 실패                                        |

재원 조회 실패에는 폴백을 두지 않는다. loan-service 는 기준금리 조회와 담보 평가 실패 시 제시값으로 폴백해 신청 가용성을 우선하지만, 재원은 성격이 다르다. 재원을 알 수 없는 상태에서 추정 한도를 부여하면 그 자체가 여신 사고다.

재시도는 settlement-service `OrderReconClient` 의 방식을 따른다. 총 2회 시도, 200ms 백오프, 4xx 는 재시도 없이 즉시 실패, 5xx 와 연결 실패만 재시도하며, 소진 시 전용 예외로 번역한다. Resilience4j 는 쓰지 않는다 — 이 리포에서 resilience4j 는 order-service 의 PG 어댑터 전용이고 `/internal/**` 호출에 적용한 전례가 없다. 도입은 리포 최초 도입에 해당하므로 1단계 범위 밖이다.

응답 본문을 읽지 못한 경우를 재원 0 으로 정규화하지 않는다. 그렇게 하면 조회 장애가 "이 셀러는 자격 미달"이라는 심사 결과로 둔갑한다. 실패는 실패로 남긴다.

## 9. 테스트

**도메인 단위** — `CardLimitPolicy` 경계값(E 등급 0원, 최소한도 경계, 클램프 동작), 불변식 위반, 상태머신 전이표.

**웹** — `@WebMvcTest` 와 spring-security-test 로 역할별 403·422 를 검증한다.

**계약** — `EventContractValidator` 로 양방향 계약을 검증한다(ADR 0024). 대상은 card-service 발행 4종(`account_opened`·`issued`·`limit_changed`·`status_changed`), 소비 5종(organization 4종 + `company.reputation_changed`), 그리고 organization-service 가 신설하는 발행 2종(`member_role_changed`·`member_removed`)이다. 스키마만 확정하는 `card.authorized`·`card.captured` 는 정본 샘플에 대한 스키마 검증까지만 수행한다.

**통합** — Testcontainers PostgreSQL 기반.

| 테스트                           | 검증 대상                                        |
| -------------------------------- | ------------------------------------------------ |
| `CardIssuanceLimitConcurrencyIT` | 동시 발급 2건이 `masterLimit` 을 초과하지 못한다 |
| `MemberRemovedSuspendsCardIT`    | 조직 이탈자의 카드가 자동 정지된다               |
| `LimitRecalculationClampIT`      | 하향 재산정이 `Σ subLimit` 로 클램프된다         |

`CardIssuanceLimitConcurrencyIT` 가 이 설계의 핵심 검증이다. `CardAccount` 와 `Card` 를 별도 애그리거트로 분리한 대가를 비관적 락으로 지불하고 있으므로, 락이 실제로 불변식을 지키지 못하면 설계 자체가 성립하지 않는다.

커버리지는 루트 `build.gradle.kts` 의 JaCoCo LINE 90% 게이트를 따른다. 신규 모듈에 예외를 두지 않는다.

## 10. 배선 체크리스트

`.claude/skills/msa-service-wiring/SKILL.md` 기준.

- `settings.gradle.kts` 에 `include("card-service")`
- `Dockerfile` COPY 2곳 (build.gradle.kts 블록, 소스 블록)
- gateway `application.yml` 에 `Path=/api/cards/**` 와 `CARD_SERVICE_URI`
- `docker-compose.yml` 에 `card-postgres` 서비스와 `MODULE=card-service` 빌드 아규먼트
- Flyway `V1__card_core.sql` 및 공통 3종(outbox, processed_events, audit_logs)
- `.github/workflows/ci.yml` 의 paths-filter 목록과 image_suffix 매핑 2곳
- 이벤트 계약 스키마와 정본 샘플을 `shared-common/src/testFixtures/resources/contracts/events/` 에 추가
- `card-service-rules` 스킬과 `HARNESS.md` 라우팅 행 추가 후 `harness-audit.mjs` 통과

## 11. 후속 단계

| 단계  | 범위                                                          |
| ----- | ------------------------------------------------------------- |
| 2단계 | 승인·매입 처리, 잔여한도 차감, `CARD_RECEIVABLE` GL 인식      |
| 3단계 | 신용공여기간, 결제일 회수, 연체. payout 상계로 §2.1 한계 해소 |
| 4단계 | 지출관리 — 영수증 제출, 부서별 결산, ERP 양식 export          |
