# Lemuel — 이커머스 + 정산 MSA 플랫폼

> **이커머스 주문 → 셀러 정산 → 복식부기 원장까지, "정확성을 기계로 강제한" 커머스 백엔드.**
> 핵심은 커머스(order)·정산(settlement) 두 축의 **깊이**이고, 그 위에 대출·투자·계정계·재무·경제·평판·관제·시세·AI·공공데이터 **위성 서비스로 도메인 확장력**을 증명한다.
> 이커머스 플랫폼 · 커머스 SaaS · 결제/정산 솔루션 도메인의 백엔드 포지션을 염두에 둔 1장 요약.

🔗 **GitHub**: https://github.com/MyoungSoo7/settlement (`develop` 브랜치)

---
--------------------------------------------
## 한눈에 보기

| 항목 | 수치 |
|---|---|
| **Java / Spring Boot** | 25 / 4.0.4 |
| **마이크로서비스** | **16 비즈니스 서비스 + API Gateway** + `shared-common` 라이브러리 |
| **DB 분리** | **DB-per-service (16 DB 물리 분리, cross-DB 연결 0)** — opslab / settlement_db / lemuel_loan … |
| **Flyway 마이그레이션** | **274 개**(`git ls-files '*/src/main/resources/db/migration/V*__*.sql' \| wc -l`) |
| **ADR** | **31 개** (0001 ~ 0032, 0019 결번) |
| **테스트** | **테스트 클래스 909개**(`*Test.java`+`*IT.java`, `git ls-files '*/src/test/java/**/*.java' \| grep -E '(Test\|IT)\.java$' \| wc -l`) — 핵심 정산 모듈 **520 테스트 실측 통과**(2026-07-12 측정) |
| **커버리지 (검증)** | **정산 모듈 LINE 94.17%** (게이트 90%, 2026-07-12 측정) — [SETTLEMENT-VERIFICATION.md](SETTLEMENT-VERIFICATION.md) |
| **부하테스트** | 4 시나리오 (k6) |

> 휘발성 수치는 문서에 박제하지 않는다 — 각 수치 옆의 `git ls-files` 명령을 그때그때 실행한 결과가 정답이다.

> **깊이는 의도적으로 배분했다.** order(490 파일)·settlement(522 파일, 각 `src/main/java` 기준·테스트 제외:
> `git ls-files '{service}/src/main/java/**/*.java' | wc -l`)가 시그니처이고,
> 공개조회형 위성 서비스(financial·economics·market·common-data·ai, 각 39~44 파일, 같은 기준)는
> 수집형이라 **얇은 것이 미완성이 아니라 스코프 선택**이다.

---

## ✅ "정말 작동하나?" — 재현 가능한 검증

포트폴리오의 정산은 주장이 아니라 **재현 가능한 테스트·게이트**로 증명된다.

```bash
./gradlew :settlement-service:test :settlement-service:jacocoTestCoverageVerification
# → 520 테스트 통과 (실패 0) · LINE 94.17% · BUILD SUCCESSFUL
```

무엇이 어떤 테스트로 검증되는지(복식부기 균형·멱등 3계층·동시성·정합성 INV-5~11) + **검증되지 않는 한계까지** 정직하게 정리:
→ **[docs/SETTLEMENT-VERIFICATION.md](SETTLEMENT-VERIFICATION.md)**

---

## 핵심 어필 포인트 — 다른 포트폴리오에 없는 6가지

### 1. MSA 경계 100% — 코드 의존 0 + DB 의존 0
> *"MSA 라면서 결국 DB 하나 같이 쓰는 거 아니에요?"* 에 대한 진짜 답

```
settlement-service/build.gradle.kts 에 implementation(project(":order-service")) 없음
order/payment/user/product 데이터는 settlement 자체 DB(settlement_db)의
이벤트 드리븐 프로젝션(settlement_*_view)으로 보유 — order DB 직접 조회 0
대사(reconciliation)는 order 내부 API /internal/recon 호출 (양측 자기 DB 만 읽음)
→ 코드 경계 + 데이터 경계 동시 확립 (ADR 0020)
```

### 2. Outbox 비동기 경계에서 끊기지 않는 분산 트레이싱
> 이 부분이 시니어급 차별화 — 일반적 Outbox 구현은 trace context 끊김

```
도메인 트랜잭션 시점 W3C trace context → outbox.trace_parent 컬럼 영속화
→ 폴러 발행 시 Kafka 헤더(traceparent) 복원 → 컨슈머 자동 합류
→ Tempo 에서 결제 → 정산 단일 trace 추적
```

### 3. 다중 PG + 격벽 회복탄력성
> *"PG 가 30분 다운되면 어떻게 대응하나요?"* 단골 질문 답변 가능

```
PgRouter — 결제수단 / 거래금액 / health 보고 자동 선택
  TOSS / KCP / NICE / INICIS 4개 어댑터
  PG 별 독립 CircuitBreaker(Resilience4j) — 한 PG 장애가 격벽
  거래 ID prefix 로 환불 시 동일 PG 자동 라우팅
  환불 PG 실패는 도메인 예외 변환 + 트랜잭션 롤백 → 유령 환불 방지
```

### 4. 정산 닫힌 사이클 — Holdback + Payout + 복식부기 원장
> 정산 도메인이 *반쪽이 아니라* 셀러 통장 입금 + 회계 원장까지

```
결제 CAPTURED ─ Outbox+Kafka ─▶ Settlement DONE (T+1/T+3/T+7 영업일, 셀러 등급별)
  ↓ Holdback (NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%) → 매일 03:00 자동 해제 배치
  ↓ Payout REQUESTED → 펌뱅킹 → COMPLETED (일/셀러별 한도 검증)
  ↓ 복식부기 원장(LedgerEntry, PENDING→POSTED→REVERSED) 동시 기록
셀러 통장 입금 ✓  +  회계 정합성 ✓
```

### 5. 선정산 대출(loan-service) — 이벤트 saga 로만 연계된 독립 서비스
> 셀러의 미확정 정산금을 담보로 선지급하는 별도 바운디드 컨텍스트

```
자체 DB(lemuel_loan) + 자체 복식부기 원장
settlement.created / settlement.confirmed 이벤트만 수신 (코드·DB 의존 0)
정산 확정 → 상환 차감 saga (멱등: processed_events + loan_repayments.settlement_id UNIQUE)
```

### 6. SKU 동시성 + 환불 멱등 — 동시성 안전성 증거
> 도메인 불변식 + 락 + 멱등이 race condition 에서도 깨지지 않음

```
재고 차감: 조건부 원자 UPDATE (stock = stock - ? WHERE stock >= ?) → 음수 재고 0
환불: 비관락(SELECT FOR UPDATE) + Idempotency-Key UNIQUE + payment 상태 멱등
정산 생성: 3단 멱등 (outbox.event_id UNIQUE → processed_events PK → settlements.payment_id UNIQUE)
```

---

## 위성 서비스 — 도메인 확장력 (얇음은 의도)

핵심 2축(커머스·정산) 외 14개 서비스는 **공개조회·수집·소비 전용**으로, 각각 얇지만 서로 다른 설계 제약을 증명한다.

| 서비스 | 성격 | 증명하는 것 |
|---|---|---|
| loan / investment | 비즈니스(saga·점수) | 정산 이벤트로만 연계된 독립 컨텍스트 |
| account (계정계 GL) | 소비 전용 | 이벤트→복식부기 집계, **발행 코드 없음**(경계 규율) |
| company / operation | 수집·관제 | Outbox + 문서 JWT / fire-and-forget 신호 |
| financial / economics / market / commondata | 공개조회 | shared-common 미의존, admin 키 게이트, PER/PBR 미계산 경계 |
| ai | 챗봇 | provider 스위치 + LLM 격리(ArchUnit) — [아래 LLM 섹션](#llm-적용--프로덕션-서비스--개발-하네스) |
| organization | 조직·멤버십 | 발행 전용(4토픽 → card 가 프로젝션 소비), 마지막 OWNER 보호 불변식 |
| card | 법인카드(SaaS) | `master_limit >= Σ sub_limit` 불변식, 재원 조회 폴백 금지(503) |
| insurance | GA 보험대리점 | 완전판매 게이트, 방카 25%룰, 수수료 환수(clawback) |
| deposit | 셀러 예치금 원장 | hold/offset 로 재원 이중사용 구조적 차단 |

> 위성을 다 깊게 파지 않은 것은 **판단**이다 — 시그니처(정산 정확성)에 깊이를 몰아주고, 나머지는 각기 다른 아키텍처 제약을 얇게 증명한다.

---

## LLM 적용 — 프로덕션 서비스 + 개발 하네스

> *"LLM 써봤나요?"* 에 대한 답은 두 갈래다: **LLM 을 서비스에 붙여본 경험**(ai-service)과
> **LLM 에이전트로 개발하되 아키텍처를 지켜낸 경험**(하네스). 돈을 다루는 플랫폼이라 둘 다 "안전장치가 본체"다.

### 1. ai-service — LLM 을 어댑터 뒤에 가둔 챗봇 (8096, `lemuel_ai`)

```
[벤더 격리]  ChatCompletionPort ← GeminiChatAdapter(기본) / AnthropicChatAdapter
             @ConditionalOnProperty(app.ai.provider) 로 정확히 하나만 등록
             Spring AI·SDK 타입은 adapter.out.llm 밖 사용 금지 — ArchUnit 강제
             → 벤더 교체가 도메인·유스케이스 계약 변경 0

[트랜잭션]   LLM 호출(최대 30s)은 트랜잭션 밖 — 조회(무tx) → 호출(무tx) → 저장(단일 tx)
             DB 커넥션을 붙든 채 외부 API 를 기다리면 풀이 고갈된다
             부수효과: LLM 실패 시 아무것도 저장되지 않는 원칙이 구조에서 자연히 성립

[PII]        저장·대화제목·외부 전송 3경로의 단일 초크포인트(PiiMasker, 순수 도메인)
             주민번호 정규식(성별자리 제약) + 카드번호 Luhn 체크섬 → 무작위 숫자열 오탐 제거
             정책은 차단이 아니라 마스킹 — 대화는 이어지되 원문은 남기지 않는다

[비용]       history-window 10 (LLM 에 싣는 직전 메시지 수 = 비용 1차 상한) + max-tokens
             사용자별 5회/분 · 100회/일 (Bucket4j) → 초과 시 429 + Retry-After
             LLM 이 과금 없이 실패하면 소비한 토큰 환불 / 스트리밍 중 이탈은 이미 과금 → 환불 안 함

[실패]       폴백 없음이 정답 — 자유 대화는 룰 대체가 불가능하므로 명시적 503 + 무저장
             API 키 미설정도 정상 상태: 부팅·이력 조회는 동작, 채팅만 503

[환각]       시스템 프롬프트가 "실 주문·정산 금액은 조회 불가 → 지어내지 말고 안내"를 못박음
             Function Calling 으로 실데이터 도구가 붙기 전까지는 "모른다"가 정답

[저장]       chat_messages.content 컬럼 암호화 + 파티션 유지 + 토큰 사용량(input/output) 기록
```

- 코드: [ChatService](../ai-service/src/main/java/github/lms/lemuel/ai/chat/application/service/ChatService.java) ·
  [AnthropicChatAdapter](../ai-service/src/main/java/github/lms/lemuel/ai/chat/adapter/out/llm/AnthropicChatAdapter.java) ·
  [PiiMasker](../ai-service/src/main/java/github/lms/lemuel/ai/chat/domain/PiiMasker.java) ·
  [AiArchitectureTest](../ai-service/src/test/java/github/lms/lemuel/ai/AiArchitectureTest.java)

### 2. LLM 에이전트 개발 하네스 — 규칙을 문서가 아니라 **기계**로

LLM 에게 코드를 맡기면 아키텍처 경계는 "지침을 잘 쓰면" 지켜지지 않는다. 이 저장소는 경계를 실행 가능한 가드로 박아뒀다.

```
guard.mjs 3중 강제   실시간 훅(exit 2 로 차단) · git pre-commit · CI 워크플로
                     막는 것: MSA 경계 위반 import, 금액 double/float, 도메인 setter,
                     반쪽 전표, 헥사고날 역방향 의존
스킬 37개            도메인 규칙을 온디맨드 로드(skill-router) + 라우팅 순응률 텔레메트리
harness-audit        문서 드리프트·라우팅 dangling·가드 무결성 자기 진단
게이트 테스트         oo-gate / aop-proxy / tx-rollback / scheduler-lock (scripts/harness/test/)
도메인 MCP 서버       settlement-copilot — 진단 도구 15종 + 도메인 스킬 8종
                     (정합성·원장 완전성·대사·outbox 적체를 LLM 이 직접 질의)
```

- 하네스 정본: [HARNESS.md](../HARNESS.md) · 가드: [scripts/harness/](../scripts/harness/) ·
  MCP 서버: `settlement-service/src/main/resources/settlement-copilot/`
  (플러그인 트리로는 **링크를 걸지 않는다** — 하네스는 플러그인 없는 클론에서도 동일하게 동작해야 하고,
  링크를 걸면 그 클론에서 doc link 가 dangling 이 된다. ADR 0028)

### 아직 없는 것 (선을 긋는다)

RAG·벡터 검색(pgvector 이미지는 선점했으나 확장 미활성) · Function Calling 실데이터 조회 · 프롬프트 회귀 평가셋(LLM-as-judge) · 파인튜닝.
ai-service 는 **시스템 프롬프트 + 컨텍스트 윈도의 단순 대화형**이며, 깊이는 정산 쪽에 몰아준 결과다.

---

## 면접 자주 묻는 질문 → 답변 위치

| 질문 | 답변 |
|---|---|
| MSA 인데 DB 공유 아닌가요? | [settlement readmodel 프로젝션](../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/readmodel/) + [OrderReconClient](../settlement-service/src/main/java/github/lms/lemuel/recon/OrderReconClient.java) |
| 정산이 정말 정확한가요? | **[SETTLEMENT-VERIFICATION.md](SETTLEMENT-VERIFICATION.md)** — 520 테스트·94% 커버리지 + 불변식 매핑 + 한계 |
| 결제 PG 장애 시 fallback? | [PgRouter](../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/pg/PgRouter.java) — fallback chain |
| 환불 중 PG 가 죽으면? | [RefundPaymentUseCase](../order-service/src/main/java/github/lms/lemuel/payment/application/RefundPaymentUseCase.java) — 예외 변환 + 롤백, 유령 환불 방지 |
| 이벤트 발행 영구 실패 시? | [DLQ + Admin API](../shared-common/src/main/java/github/lms/lemuel/common/outbox/) |
| PG 정산 누락 발견 어떻게? | [PG Reconciliation](../settlement-service/src/main/java/github/lms/lemuel/pgreconciliation/) — 5종 분류 + 승인 시 보정 이벤트 |
| 재고 100개에 110건 동시? | [DecreaseVariantStockService](../order-service/src/main/java/github/lms/lemuel/product/application/service/DecreaseVariantStockService.java) — 조건부 원자 UPDATE |
| 포인트+카드 분할결제 일부 환불? | [PaymentDomain.planRefundFromTenders](../order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java) — seq 역순 |
| Outbox 패턴인데 trace 끊김? | [KafkaOutboxPublisher](../shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/out/event/KafkaOutboxPublisher.java) — traceparent 헤더 복원 |
| 주문 상태 잘못된 전이 막나요? | [OrderStatus.canTransitionTo / Order.transitionTo](../order-service/src/main/java/github/lms/lemuel/order/domain/OrderStatus.java) — 상태머신 가드 |
| 정산 주말·공휴일? | [BusinessDayCalculator](../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/BusinessDayCalculator.java) — T+N 영업일 |
| 신뢰도 낮은 셀러 환불 다발? | [HoldbackPolicy](../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/HoldbackPolicy.java) |
| 정산해서 셀러 입금까지? | [Payout 도메인](../settlement-service/src/main/java/github/lms/lemuel/payout/) — 펌뱅킹 + 운영자 콘솔 |
| 선정산 대출은 어떻게 연계? | [loan-service](../loan-service/src/main/java/github/lms/lemuel/loan/) — settlement 이벤트 saga |
| 이중 송금/상환 어떻게 막나요? | settlement_id / payment_id UNIQUE + 도메인 멱등 검증 |
| LLM 벤더 교체 비용은? | [AiArchitectureTest](../ai-service/src/test/java/github/lms/lemuel/ai/AiArchitectureTest.java) — Spring AI 타입을 `adapter.out.llm` 밖에서 금지(ArchUnit), 포트 계약 불변 |
| AI 채팅에 카드번호를 붙여넣으면? | [PiiMasker](../ai-service/src/main/java/github/lms/lemuel/ai/chat/domain/PiiMasker.java) — Luhn 검증 후 마스킹, 저장·전송 전 단일 초크포인트 |
| LLM 이 죽거나 느리면? | [ChatService](../ai-service/src/main/java/github/lms/lemuel/ai/chat/application/service/ChatService.java) — tx 밖 호출(풀 보호) + 폴백 없이 503·무저장 + 레이트리밋 토큰 환불 |
| LLM 이 정산 금액을 지어내면? | 시스템 프롬프트로 실데이터 조회 불가 명시(환각 차단) — 실데이터는 Function Calling 연동 후 |

---

## 운영성 자산

| 자산 | 위치                                                             |
|---|------------------------------------------------------------------|
| **Architecture Decision Records** 31개 | [docs/adr/](adr/)                                                |
| **정산 정확성 검증 문서** | [docs/SETTLEMENT-VERIFICATION.md](SETTLEMENT-VERIFICATION.md)    |
| **k6 부하테스트** 4 시나리오 | [load-test/](../load-test/)                                      |
| **Grafana 대시보드** + 커스텀 메트릭 | [monitoring/grafana/dashboards/](../monitoring/grafana/dashboards/) |
| **분산 트레이싱** (Tempo + OTLP) | [docker-compose.yml](../docker-compose.yml)                      |
| **장애 대응 Runbook** | [docs/runbook/](plan/runbook/)                                        |
| **CI/CD** GitHub Actions | [.github/workflows/](../.github/workflows/)                      |



---

## 도메인별로 무엇이 강점인가

### 이커머스 마켓플레이스
**강점**: 커머스 도메인 + SKU 동시성 + 셀러 등급별 차등 정산 + **셀러 정산 사이클 닫힘(Holdback→Payout→원장)** — 커머스의 어려운 뒷단(정산·회계)까지 소유

### 이커머스 솔루션 / 커머스 SaaS
**강점**: 균형 잡힌 커머스 도메인 + 장바구니 + 관리자(RBAC/메뉴/공통코드) + 정산

### 결제(PG)
**강점**: 다중 PG + Outbox 멱등 3단 + 분할결제 역순 환불 + 분산 트레이싱

### 정산 전문 / 핀테크
**강점**: 정산 도메인 깊이 (등급·주기·보류·역정산·대사·송금·복식부기) + MSA 경계(코드·DB 의존 0)

---

## 빠른 둘러보기 (5분)

1. **[docs/SETTLEMENT-VERIFICATION.md](SETTLEMENT-VERIFICATION.md)** — "정말 작동하나"의 재현 가능한 답(520 테스트·94%)
2. **[README.md](../README.md)** 의 *"면접관용 빠른 둘러보기"* + 아키텍처 다이어그램
3. **[docs/adr/0020-order-settlement-db-split.md](adr/0020-order-settlement-db-split.md)** — DB 물리 분리 결정
4. **[docs/adr/](adr/)** 31개 — 왜 이렇게 설계했는지
5. **[LLM 적용 섹션](#llm-적용--프로덕션-서비스--개발-하네스)** — ai-service(벤더 격리·PII·비용 가드) + 개발 하네스(guard 3중 강제)

---

## 연락

- GitHub: [MyoungSoo7](https://github.com/MyoungSoo7) — 연락처는 프로필 참조
