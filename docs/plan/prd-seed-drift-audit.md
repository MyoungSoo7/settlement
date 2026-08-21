# PRD·Seed 드리프트 감사 — 2026-08-22

> **무엇을 한 문서인가**: `docs/plan/prd/` 의 역산 PRD 와 `docs/plan/seeds/` 의 Seed 가 **현행 코드와
> 맞는지**를 전 서비스 역산으로 대조한 기록이다. 결론과 함께 **어떤 검사를 어떻게 돌렸고, 무엇이
> 오탐이라 걸러졌는지**를 남긴다 — 다음 감사가 같은 오탐을 다시 밟지 않게 하기 위해서다.
>
> | 항목      | 값                                                              |
> | --------- | ------------------------------------------------------------------ |
> | 대조 기준 | `develop` `92d25c463` (2026-08-22)                              |
> | 직전 기준 | PRD 25종은 2026-08-12~13 (board 만 08-15), Seed 29종은 2026-07-18 |
> | 그 사이   | develop 커밋 **420건**                                          |
> | 조치      | PRD 2종 신규 작성 · order PRD/Seed 갱신 · 잔여 항목 아래 기록    |

---

## 1. 요약

| 축                      | 감사 전                         | 감사 후                     |
| ----------------------- | ------------------------------- | --------------------------- |
| 서비스 PRD 커버리지     | 25 / 27                         | **27 / 27**                 |
| 서비스 Seed 커버리지    | 24 / 27                         | 24 / 27 (미조치 — §5)       |
| order PRD 미기재 경로   | 22건                            | **0건**                     |
| 나머지 PRD 미기재 경로  | 18건                            | 18건 (미조치 — §5)          |
| PRD 「근거」 수치 오류  | 9개 서비스                      | order 1건 해소, 8건 잔여    |
| order Seed 불변식 오류  | Payment 상태머신 · 계약 5토픽   | **해소 (v1.1)**             |

핵심은 **"문서가 오래됐다"가 아니라 "문서가 틀린 것을 사실로 주장하고 있었다"** 는 점이다. order PRD 는
`PaymentCreated`·`UserMembershipChanged` 의 소비처를 구체적으로 적어 두었는데, 토픽 카탈로그는 같은
이벤트를 "레거시, 어느 서비스도 참조하지 않음"으로 분류하고 있었다. PRD 자신이 §12 G-5 에서 지적한
"소비처가 문서에만 있다"의 실례가 그 문서 자신이었다.

## 2. 조치한 것

### 2.1 신규 PRD 2종

| 문서                                                     | 최상위 발견                                                                 |
| -------------------------------------------------------- | --------------------------------------------------------------------------- |
| [`prd/education-service.md`](prd/education-service.md)   | **공개 이벤트가 Kafka 로 나가지 않는다** — `spring-kafka` 의존·bootstrap 설정·Outbox 폴러 빈이 전부 없다. 제한 스캔(`scanBasePackages`)이라 shared-common 의 `OutboxPublisherScheduler` 가 붙지 않고, `PersistenceConfig` 도 `@Import` 하지 않는다. `lemuel.education.course_published` 는 카탈로그에 소유 토픽으로 등재돼 있으나 **생산된 적이 없다.** |
| [`prd/receipt-ocr-service.md`](prd/receipt-ocr-service.md) | **아무도 부르지 않는다** — compose·gateway·CI·Dockerfile 어디에도 없고, card-service 의 `ExtractReceiptFieldsPort` 구현은 여전히 `GeminiReceiptOcrAdapter` 하나다. 폴리글랏 7종은 최소한 `polyglot-ci.yml` 에 들어가 있는데 이 서비스만 없어 **CI 가 이 코드를 한 번도 돌리지 않는다.** |

두 발견 모두 **컴파일러도 테스트도 잡지 못하는 종류**다 — 코드는 정상이고 배선만 없다.

### 2.2 order PRD 갱신

| 항목            | 수정 전                        | 수정 후                                              |
| --------------- | ------------------------------ | ---------------------------------------------------- |
| 역산 기준       | 2026-08-13                     | 2026-08-22 `92d25c463`                               |
| 도메인 컨텍스트 | 16                             | **20** (point·giftcard 08-18, bulkorder·auditconsole 08-21) |
| 발행 이벤트     | "8토픽"                        | **19종** = 카탈로그 등재 16 + 의도적 제외 3          |
| 배치            | 6종                            | **9종** (포인트 소멸 03:40 · 기프트카드 소멸 03:50 · 안심번호 회수 매시 :10 추가) |
| REST 목록       | 대표 7행                       | 22개 미기재 경로 반영                                |
| §9.2 소비처     | `PaymentCreated` 등에 소비처 명시 | **소비자 0 임을 명시** + 왜 그 기술이 틀렸는지 기록 |
| G-1             | "16개 컨텍스트"                | 20개 + "9일 새 4개 증가" 사실 추가                   |
| G-5             | 가설                           | **실측 3종**으로 승격                                |

### 2.3 order Seed v1.1

Seed 는 as-is 사양이라 **결함을 교정하지 않고 기술만 현행으로 옮겼다**(원칙 유지).

| 항목            | v1                                          | v1.1                                                            |
| --------------- | ------------------------------------------- | --------------------------------------------------------------- |
| Payment 상태머신 | 4상태, `PaymentStatus.java:22-37`           | **7상태**, `:27-43`. `EXPIRED` 와 `READY→EXPIRED`(가상계좌 입금 기한 경과) 추가 |
| 계약 표면       | 5토픽                                       | **16토픽** (seller.tier_changed 1 + point 6 + giftcard 4 편입)  |
| 텐더 타입       | 미기술                                      | 9종 — `POINT`·`GIFT_CARD` 는 외부 PG 없는 사내 원장 텐더        |
| AC-2 게이트     | 계약 테스트 4클래스                         | **7클래스** + "미소비 토픽은 이 게이트로 안 잡힌다" 주석        |
| Known Issues    | KI-1·KI-2                                   | **KI-3 추가** — 미소비 발행 3종                                 |
| 파일:라인       | —                                           | 전수 재대조. `RefundPaymentUseCase` 는 `application/service/` → `application/` 이동, 쿠폰은 90,144 → 97,151(+회수 163) |

**이동하지 않아 그대로 유효한 참조**: `OrderStatus.java:41-54`·`:69-75`, `PaymentTender.java:103`,
`PaymentJpaRepository.java:32`, `SpringDataProductJpaRepository.java:42-43`.

## 3. 검사 방법 (재현 가능)

| 검사                | 방법                                                                     |
| ------------------- | ------------------------------------------------------------------------ |
| 서비스 커버리지     | `ls -d *-service` ↔ `docs/plan/prd/*.md` · `docs/plan/seeds/*.seed.yaml` (settlement-service → `settlement-core.md` 매핑) |
| 엔드포인트 드리프트 | 코드의 `@RequestMapping("...")` 를 수집해 **`{` 앞 리터럴 접두사**로 PRD 본문 검색 |
| 소비 토픽 드리프트  | `@KafkaListener` 이후 3줄에서 `"lemuel.*"` 를 뽑아 PRD 검색               |
| 발행 이벤트         | `OutboxBacked*EventPublisher` 의 문자열 리터럴 ↔ `topic-catalog.json` ↔ `contracts/events/*.schema.json` 3자 대조 |
| 수치 주장           | PRD 「근거」 줄의 각 수치를 `ls`/`find`/`grep -c` 로 실측                |
| Seed 불변식         | 인용된 `파일:라인` 을 직접 열어 대조                                    |

## 4. 걸러낸 오탐 — 다음 감사가 다시 밟지 말 것

| 오탐                                      | 왜 생겼나                                                                 | 올바른 방법                                          |
| ----------------------------------------- | ------------------------------------------------------------------------- | ---------------------------------------------------- |
| board·loan 엔드포인트 "미기재" 2건        | `{param}` 을 `{}` 로 정규화해 검색 — PRD 는 `{boardKey}` 로 적는다        | `{` 앞 리터럴 접두사로만 매칭                        |
| 발행 토픽 "전 서비스 미기재" 45건         | PRD 는 **발행을 PascalCase 이벤트명**(`CardAccountOpened`)으로 적는다     | 발행은 토픽명으로 대조하지 말 것. 소비는 `lemuel.x.y` 라 대조 가능 |
| operation 소비 토픽 "미기재" 6건          | PRD 가 `lemuel.` 접두사 없이 `ops.order.failed` 로 적는다                 | 접두사 유무 양쪽 확인                                |
| account "banking 미기재"                  | PRD 가 한글(`예금`·`적금`·`연금`)로 적는다                                | 영문 패키지명만 grep 하지 말 것                      |
| `lemuel.ops.*` 카탈로그 누락              | 카탈로그 22행이 **"의도적 제외"** 로 사유와 함께 명시 (owner 가 하나로 정해지지 않음) | 누락 판정 전 카탈로그 `$comment` 를 읽을 것          |
| `lemuel.payment.{created,authorized}` 누락 | 같은 곳에 "레거시, 어느 서비스도 참조하지 않음"으로 명시                  | 위와 동일 — 단 **발행 코드는 살아 있다**(KI-3)       |

## 5. 잔여 — 이번에 손대지 않은 것

### R-1. Seed 없는 서비스 3종

| 서비스              | PRD | Seed |
| ------------------- | --- | ---- |
| board-service       | ✅  | ❌   |
| education-service   | ✅(신규) | ❌ |
| receipt-ocr-service | ✅(신규) | ❌ |

### R-2. PRD 미기재 엔드포인트 18건

| 서비스             | 경로                                                                                                              |
| ------------------ | ----------------------------------------------------------------------------------------------------------------- |
| settlement-service | `/admin/audit-trail` · `/admin/backfill/ledger-reverse` · `/admin/payouts/{backfill,pii}` · `/admin/settlements/rerun` · `/admin/tax/scans` · `/admin/tax/settlements/{id}` · `/api/reports/sales-stats` · `/api/tax-invoices/scans` · `/api/tax-invoices/settlement/{id}` |
| card-service       | `/admin/expense-receipts` · `/internal/api/v1/cards` · `/van/v1/{captures,refunds,voids}`                          |
| company-service    | `/admin/company/{sellers,workforce}`                                                                              |
| investment-service | `/api/investment/recommendations`                                                                                 |

### R-3. PRD 「근거」 수치 드리프트 8건

| PRD                | 문서 주장                  | 실측                        |
| ------------------ | -------------------------- | --------------------------- |
| account            | 소비 17토픽 / 컨슈머 12+   | **27 / 19** (`GiftCardLedgerConsumer`·`PointLedgerConsumer` 신규) |
| insurance          | 발행 4토픽 / 테스트 42     | **9 / 54**                  |
| loan               | 컨트롤러 6 / Flyway 27 / 테스트 72 | **12 / 28 / 81**    |
| deposit            | Flyway V1~V3 / 테스트 9    | **5개(V20260814140000까지) / 26** |
| card               | 컨슈머 6종                 | **8** (Flyway V2~V9 는 정확) |
| gateway            | 라우트 18건                | **20** (08-22 `164fd0c5a`)  |
| company            | REST 8개 매핑              | **9**                       |
| operation          | 소비 8토픽                 | **9** — 본문도 "실패 5" 라 쓰고 6개를 나열 |

**정확했던 것**: settlement-core Flyway 42 · notification 구독 5토픽 · card Flyway V2~V9 ·
organization 발행 4토픽 · company 발행1·소비1.

### R-4. settlement-service 슬라이스 2종이 PRD·Seed 양쪽에 없다

`auditconsole` · `crypto`. (`tax`·`recovery`·`closing` 이 Seed 에 없는 것은 **선언된 범위**라 격차가 아니다 —
settlement Seed 4종은 accounting-core·chargeback·pgreconciliation·recon 만 다룬다.)

### R-5. 미소비 발행 이벤트 3종 (order)

`PaymentCreated` · `PaymentAuthorized` · `UserMembershipChanged`. 발행 코드가 살아 있어 매 결제·멤버십
변경마다 아무도 읽지 않는 Outbox 행이 쌓인다. **존치/제거는 결정 사항**이라 이번에 손대지 않고
order PRD T-6 · Seed KI-3 로 기록만 했다.

## 6. 구조적 제안 — 이 감사를 반복하지 않으려면

이번에 발견된 것 중 **기계가 잡을 수 있었던 것**과 아닌 것을 가른다.

| 발견                                | 기계로 잡을 수 있었나                                                          |
| ----------------------------------- | ------------------------------------------------------------------------------ |
| 서비스 PRD/Seed 누락                | **가능** — `*-service` 디렉토리 ↔ `docs/plan/{prd,seeds}` 대조는 게이트로 만들 수 있다(`harness-audit` 가 이미 `settings.gradle.kts` 로 로스터 드리프트를 잡는 것과 같은 방식) |
| PRD 미기재 엔드포인트               | **가능** — `@RequestMapping` 수집 ↔ PRD 검색. 단 `{param}`·표기 변형 때문에 오탐 관리가 필요하다 |
| education Outbox 폴러 미배선        | **가능** — "Outbox 어댑터는 있는데 폴러 빈이 없다"는 정적으로 판정 가능하다     |
| receipt-ocr CI 미포함               | **가능** — 최상위 서비스 디렉토리 ↔ CI 워크플로 경로 필터 대조                 |
| 미소비 토픽(KI-3)                   | **가능** — 카탈로그 owner ↔ 전 서비스 `@KafkaListener` 대조 (order PRD T-5 가 이미 요청) |
| PRD 「근거」 수치                   | 부분적 — 수치 표기가 자유 서술이라 파싱이 취약하다. 표 형식 고정이 선행돼야 한다 |
| Seed 불변식의 `파일:라인`           | **가능** — 인용 라인이 여전히 그 내용인지는 앵커 문자열로 검증 가능하다        |

> 우선순위 제안: 위에서 **오탐 위험이 낮고 손해가 큰 것** 순으로 — ① 서비스↔문서 커버리지,
> ② Outbox 폴러 미배선, ③ 미소비 토픽 감지. ①은 이번에 3건(교육·영수증 PRD, Seed 3종)을 놓치고
> 있었고, ②는 이미 **운영 이벤트 하나가 나가지 않고 있는 상태**를 만들었다.
