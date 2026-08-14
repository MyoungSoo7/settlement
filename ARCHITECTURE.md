# 아키텍처 개요 (Architecture Overview)

> Lemuel 은 **이커머스 주문 → 셀러 정산 → 복식부기 원장**을 코어로, 그 위에 대출·투자·계정계·재무제표·경제지표·기업평판·운영관제·시세·AI·공공데이터·실시간/ML/이벤트 서비스를 확장한 **폴리글랏 MSA 플랫폼**이다.
> 본 문서는 *현재 서비스 구성 · 적용 아키텍처 · 디자인 패턴 · 기술 스택*을 한 곳에서 정리한다. 결정 배경은 [ADR](docs/adr/) 참조.

---

## 1. 서비스 인벤토리 — 24개 서비스 (+ 플랫폼 라이브러리)

**언어를 능력에 맞게 배치한 폴리글랏 MSA**: JVM(Java/Kotlin)으로 도메인 정합성·트랜잭션, Go 로 동시성·엣지, Python 으로 데이터/ML.

### JVM · Java 서비스 16종 + Gateway (핵심 도메인 · 정합성)

| # | 서비스 | 포트 | 도메인 / 역할 |
|---|---|---|---|
| 1 | **order-service** | 8088 | 커머스 코어 — user·order·payment·cart·shipping·product·category·coupon·review + 정합성 부속(recon·프로젝션 백필) |
| 2 | **settlement-service** | 8082/8083 | 정산 코어 — 정산 상태머신·Outbox·역정산·원장(복식부기)·payout·정산 검색(ES). ADR 0020 으로 독립 프로세스 분리 |
| 3 | **gateway-service** | 8080 | API Gateway (Spring Cloud Gateway) — 경로 라우팅 전용. 인증(JWT)·레이트리밋은 각 서비스가 자체 강제 ([§7](#7-금융권-아키텍처-용어-대응-mci--eai--esb--fep)) |
| 4 | **loan-service** | 8084 | 선정산·기업대출 (settlement 확정 이벤트 수신) |
| 5 | **account-service** | 8102 | 계정계 — 집계·시산표·잔액·현금 인식(payout) |
| 6 | **organization-service** | 8104 | 조직/멤버십 |
| 7 | **operation-service** | 8092 | 운영관제 콘솔 (ADMIN 전용, ops 이벤트) |
| 8 | **investment-service** | 8100/8101 | 투자 — 규칙 스크리닝 추천·투자점수·재원·매매계획 |
| 9 | **financial-statements-service** | 8086 | 코스피 상장사 재무제표 (DART) |
| 10 | **economics-service** | 8087 | 경제지표 (한국은행 ECOS) |
| 11 | **company-service** | 8090 | 기업 뉴스·평판 (LLM 감성분석) |
| 12 | **market-service** | 8094 | 주식 시세 (일별 종가 시계열) |
| 13 | **common-data-service** | 8098 | 공공데이터포털 범용 커넥터 (SSRF 가드) |
| 14 | **ai-service** | 8096 | AI 챗봇 (Gemini/Claude provider 스위치) |
| 15 | **card-service** | 8106/8107 | 법인카드 카드계정·카드(마스터/서브 한도) — 승인·매입·명세서·지출관리 |
| 16 | **insurance-service** | 8108/8109 | GA 보험대리점 — 상담·가입설계·청약·계약·유지변경·수수료정산 |
| 17 | **deposit-service** | 8112/8113 | 셀러 예치금 원장 (잔고 단일 진실원, hold/offset) |

> **shared-common** — 버전드 플랫폼 라이브러리(ADR 0021, composite build + maven-publish). JWT SecurityConfig · Outbox · 멱등 인프라 · JacksonCompat 등 코어 서비스가 공유. *서비스가 아니라 라이브러리.*

### Polyglot · Go 2 (동시성 · 엣지)

| 서비스 | 포트 | 역할 | 핵심 |
|---|---|---|---|
| **market-stream-service** | 8110 | 실시간 시세 스트리밍 (SSE `/stream/{code}` + WebSocket) | goroutine Hub 팬아웃(누수 0) · 재생 없는 라이브 전용 스트림([`sse.md`](docs/study/sse.md)) |
| **payment-webhook-service** | 8111 | Toss 결제 웹훅 수신 → Kafka 발행 | HMAC 서명검증 · 멱등(TTL) · `lemuel.payment.confirmed` 발행 |

### Polyglot · Python 3 (데이터 · ML · 퀀트)

| 서비스 | 포트 | 역할 | 스택 |
|---|---|---|---|
| **screening-backtest-service** | 8120 | 투자 스크리닝 규칙 백테스트 (수익률·MDD·Sharpe·승률) | FastAPI · pandas · numpy |
| **settlement-anomaly-service** | 8121 | 정산/payout 이상탐지 | FastAPI · scikit-learn (IsolationForest + MAD z-score 앙상블) |
| **forecast-service** | 8122 | 정산액/매출 시계열 예측 | FastAPI · statsmodels (Holt-Winters + seasonal-naive) |

### Polyglot · Kotlin 2 (이벤트 · 코루틴)

| 서비스 | 포트 | 역할 | 핵심 |
|---|---|---|---|
| **notification-service** | 8130 | 도메인 이벤트(Kafka) → 다채널(log/Slack/email) 알림 + 브라우저 푸시 SSE(`/api/notifications/stream`) | 코루틴 I/O 팬아웃 · 채널별 타임아웃/재시도 격리 · eventId 멱등 · JWT 신원 라우팅/`Last-Event-ID` 재생([`sse.md`](docs/study/sse.md)) |
| **reconciliation-service** | 8131 | 정산 대사 (settlement ↔ PG/payout/원장) | sealed Discrepancy(MISSING/EXTRA/AMOUNT/STATUS) · 다소스 코루틴 병렬 fetch · @Scheduled |

**합계**: Java 17종(16 서비스 + gateway) + Go 2 + Python 3 + Kotlin 2 = **24 서비스** (+ shared-common 라이브러리). *런타임은 Java 25 — 위 숫자는 서비스 수다.*

---

## 2. 적용 아키텍처 (Applied Architecture)

- **폴리글랏 MSA** — 동일 클러스터에서 언어별 강점 배치. JVM=도메인/트랜잭션, Go=실시간/멱등 엣지, Python=ML/퀀트. 기존 JVM 서비스가 못 채우는 실시간·데이터 공백을 보완.
- **헥사고날 (Ports & Adapters)** — 전 서비스가 `domain / application / adapter(in·out)` 로 분리. 의존 방향(도메인은 프레임워크 무의존, application→adapter 금지)을 **ArchUnit 으로 컴파일 게이트화**. ADR 0001.
- **Bounded Context 분리 + DB-per-service** — 서비스마다 독립 PostgreSQL(`lemuel_*`), 스키마는 서비스별 Flyway 가 관리. 물리 격리로 결합 차단. ADR 0020(order↔settlement DB 분리).
- **이벤트 드리븐 + CQRS 프로젝션** — 서비스 간 상태는 Kafka 이벤트(`lemuel.<domain>.<event>`)로 전파. settlement 확정·payment·investment 체결 등이 이벤트로 흐르고, 읽기 측은 프로젝션으로 조회. Kafka vs 애플리케이션 이벤트 경계는 ADR 0005.
- **GitOps 배포** — GitHub Actions(CI, 이미지 빌드·ghcr 푸시) → ArgoCD(k3s 에 선언적 sync) → image-updater(신규 빌드 자동 롤아웃). 코드/설정이 git 이 단일 진실.
- **관측성 내장** — Micrometer→Prometheus→Grafana(비즈니스 KPI 대시보드), 분산 트레이싱(Outbox 관통, ADR 0012), 중앙 로깅(ELK/fluent-bit).

---

## 3. 디자인 패턴 (Design Patterns)

정합성이 핵심 자산이라, 패턴 다수가 "정확성을 기계로 강제"하는 데 쓰인다. 각 패턴의 결정 배경은 대응 ADR.

| 패턴 | 어디에 | 배경 |
|---|---|---|
| **Transactional Outbox** | settlement 이벤트 발행 (PENDING→PUBLISHED 상태머신·배치 폴링·Micrometer 4종·DLQ) | ADR 0003 |
| **Triple Idempotency** | L1 outbox `event_id` UNIQUE → L2 `processed_events` PK → L3 DB 자연키 UNIQUE. at-least-once + 멱등 수신 | ADR 0003/0017 |
| **State Machine** | 정산 상태 전이표를 enum `canTransitionTo` 단일 출처로 강제 | ADR 0002 |
| **Saga / 보상 트랜잭션** | 역정산을 조정(adjustment)으로 (음수 상쇄, 불변 원장 유지) | ADR 0004 |
| **Circuit Breaker · Bulkhead** | Toss PG 연동 Resilience4j, 멀티-PG 라우팅 격벽 | ADR 0006/0010 |
| **DLT & Replay** | Kafka 컨슈머 DLT + 재처리 | ADR 0017 |
| **Field-level Encryption** | payout 지급계좌 PII AES-256 JPA 컨버터 (PAYOUT_ENC_KEY) | ADR 0016 |
| **Optimistic Lock / 조건부 UPDATE** | SKU 변형 재고 원자 차감 | ADR 0011 |
| **Event Contract as Code · Schema Registry** | 이벤트 스키마를 코드 계약으로 검증 | ADR 0022/0024 |
| **2-tier Cache** | Caffeine(L1) + 선택적 Redis(L2, Pub/Sub 무효화) | — |
| **Rate Limiting** | Bucket4j | — |
| **Feature Flag** | 정산 검색(ES) `app.search.enabled` 로 on/off + JDBC 폴백 | — |
| **Rule-based Screening** | 투자 추천 = 예측 아닌 규칙(재무·악재뉴스·시세위치) | — |
| **Coroutine I/O Fan-out** | notification 다채널 병렬 발송 + 격리, reconciliation 다소스 병렬 대사 | — |
| **Double-entry Ledger + 일일 대사** | 원장 불변식(차변=대변)·일일 reconciliation | ADR 0007 |

> 헥사고날/멱등/Outbox 경계는 각 서비스의 ArchUnit 테스트가 **위반 시 빌드를 깨뜨려** 회귀를 막는다.

---

## 4. 기술 스택 (Tech Stack)

| 레이어 | 기술 |
|---|---|
| **JVM 언어** | Java 25 (코어 16 서비스 + gateway) · Kotlin 2.0 (신규 이벤트 서비스 2종) |
| **JVM 프레임워크** | Spring Boot 4.0.4 / Spring 7 (Java) · Spring Boot 3.3 (Kotlin, JDK 21) · Spring Cloud Gateway |
| **Go** | Go 1.22+ (goroutine, `net/http` SSE/WebSocket, kafka-go, HMAC-SHA256) |
| **Python** | Python 3.11 · FastAPI · pandas/numpy · scikit-learn · statsmodels |
| **빌드** | Gradle Multi-module (Kotlin DSL) · 폴리글랏 서비스는 standalone 빌드 |
| **DB** | PostgreSQL 17 (DB-per-service) · Flyway 마이그레이션 |
| **검색** | Elasticsearch 8.x (Nori 한글 분석기) — 정산 검색/집계 |
| **메시징** | Apache Kafka — dev: Redpanda 호환 / prod: **Strimzi KRaft** (`lemuel.<domain>.<event>`) |
| **캐시** | Caffeine (L1) + 선택적 Redis (L2, Pub/Sub 무효화) |
| **회복탄력성** | Resilience4j (Circuit Breaker, Retry, Bulkhead) · Bucket4j (rate limit) |
| **AI/LLM** | Google Gemini(기본) · Anthropic Claude (Spring AI) — provider 스위치 |
| **PG 연동** | Toss Payments (웹훅 HMAC 검증) |
| **인증/보안** | JWT(HS256) · BCrypt(cost 12) · payout PII AES-256 필드 암호화 |
| **관측성** | Micrometer + Prometheus + Grafana · 분산 트레이싱 · ELK(fluent-bit) 중앙 로깅 |
| **테스트/품질** | JUnit 5 · Mockito/MockK · ArchUnit · Testcontainers · JaCoCo · SonarCloud · Snyk · Go `-race` · pytest |
| **CI/CD** | GitHub Actions (빌드·테스트·이미지 push) → GHCR → **ArgoCD + image-updater** (GitOps) |
| **런타임** | Docker (dev compose) · **k3s (6-node)** + traefik · Cloudflare Tunnel (외부노출) |

---

## 5. CI/CD 파이프라인

```
코드 push → GitHub Actions
  ├─ JVM: Gradle build/test(JaCoCo gate) + SonarCloud + Snyk → ghcr.io/.../settlement-<svc>
  └─ Polyglot(polyglot-ci): Go build+test(-race) / Python pytest / Kotlin gradle → ghcr.io/.../settlement-<svc>
         ↓ (tag: main / main-<sha7> / latest)
      ArgoCD (k3s) — 매니페스트 선언적 sync (helm-deploy 레포)
         ↓
      argocd-image-updater — 신규 main-<sha7> 감지 → 태그 write-back → 자동 롤아웃
```

- JVM 코어는 `charts/settlement`·`charts/settlement-msa`, 폴리글랏 7종은 전용 `charts/polyglot-services` + `polyglot-services` ArgoCD 앱으로 **격리 배포**(기존 서비스 리스크 0).
- 거버넌스: `harness-guard` 가 라우팅 맵·가드 훅 경로·모듈 로스터를 실제와 대조해 문서-코드 드리프트를 CI 에서 차단.

---

## 6. 진화 (Evolution)

단일 모놀리스 → **Bounded Context 분리** → **이벤트 드리븐** → **DB-per-service + CQRS 프로젝션**(ADR 0020) → **폴리글랏 MSA(Go/Python/Kotlin)** 로 확장. 커머스·정산의 *깊이*(상태머신·Outbox·복식부기·멱등)를 시그니처로, 도메인·언어 양방향 확장력을 증명한다.

---

## 7. 금융권 아키텍처 용어 대응 (MCI · EAI · ESB · FEP)

> 은행 SI 용어로 이 플랫폼을 읽으면 무엇이 어디에 대응하는가. 핵심은 **용어만 다르고 같은 문제를 푸는 것**과, **의도적으로 다르게 푼 것**을 구분하는 데 있다.

| 은행 용어 | 원래 역할 | 이 플랫폼의 대응 | 관계 |
|---|---|---|---|
| **MCI** (Multi Channel Integration) | 인터넷·모바일뱅킹, ATM, 텔러단말, 콜센터를 계정계 앞단에서 통합 | `gateway-service` (8080, Spring Cloud Gateway) | **부분 대응** — 경로 라우팅 전용. 채널 식별·공통 전처리는 없고 인증은 각 서비스의 JWT 체인이 자체 강제 |
| **EAI** (Enterprise Application Integration) | 행내 시스템 간 연계 허브(Hub & Spoke) — 어댑터 + 전문 매핑 | Kafka(`lemuel.<domain>.<event>`) + Transactional Outbox + CQRS 프로젝션 | **대체** — 중앙 허브를 두지 않는다. 허브 SPOF/병목 대신 브로커를 dumb pipe 로 두고, 소비 서비스가 자기 프로젝션을 소유(ADR 0003/0020) |
| **ESB** (Enterprise Service Bus) | 프로토콜 변환 + 서비스 라우팅 + **업무 오케스트레이션**을 버스에 집중 | (대응물 없음 — 의도적 부재) | **미채택** — 코레오그래피 사가로 대체. 아래 §7.1 |
| **FEP** (Front End Processor) | 대외기관(금결원·타행·카드사) 전문 송수신 | payout 펌뱅킹 FEP 어댑터(`payout/adapter/out/firmbanking/fep/`, 고정길이 전문 레이아웃 + 소켓 클라이언트) · `payment-webhook-service`(8111, Toss 웹훅 HMAC) | **직접 대응** — 대외 전문/웹훅 경계 |
| **계정계** | 원장·잔액의 단일 진실원 | `account-service`(8102, 복식부기 GL·시산표) + settlement 원장 | 직접 대응 (ADR 0007) |
| **정보계** | 조회·분석 목적의 사본 | settlement 프로젝션(`settlement_*_view`) · ES 정산검색 · Python 예측/이상탐지 | 대응 — 다만 야간 배치 복제가 아니라 **이벤트 실시간 반영** |

### 7.1 ESB 를 두지 않은 이유

ESB 는 업무 흐름을 버스로 끌어올려 재사용을 노렸지만, 그 대가로 **버스가 도메인 지식을 갖게 되어** 전행 배포 병목이 된다. 이 플랫폼은 반대를 택했다 — **"smart pipes, dumb endpoints"(ESB) 가 아니라 "dumb pipes, smart endpoints"(MSA)**.

선정산 상계 흐름이 그 사례다. 중앙 오케스트레이터 없이 각 서비스 컨슈머가 자기 몫만 처리한다.

```
settlement: 정산 확정 → lemuel.settlement.confirmed
   → loan: SettlementConfirmedConsumer (group: lemuel-loan) → 대출 상환 적용
   → lemuel.loan.repayment_applied
   → settlement: LoanRepaymentAppliedConsumer (group: lemuel-settlement) → 정산금 상계 반영
```

- 정산 수수료·홀드백 규칙과 대출 상환 규칙이 **버스 설정이 아니라 각 서비스 도메인 안에 봉인**된다. 한쪽 정책이 바뀌어도 다른 서비스는 재배포하지 않는다.
- 각 단계는 Outbox 발행 + `processed_events` 멱등으로 at-least-once 를 흡수한다(ADR 0003/0017). ESB 의 보장전송을 브로커+멱등으로 대체하는 셈.
- **트레이드오프는 흐름 가시성**이다. 전체 사가를 한 화면에 보여주는 지점이 없어, `/admin/event-track/**` 과 Outbox 관통 분산 트레이싱(ADR 0012)으로 보완한다.

### 7.2 남은 간극

- **MCI 의 채널 계층이 얇다** — 게이트웨이가 라우팅만 하므로 채널 식별(웹/모바일/운영콘솔/파트너), correlation-id 주입, 유량 제어가 채널 단위로 일원화되어 있지 않다. RateLimit(Bucket4j)은 shared-common 경유라 미의존 서비스(financial·economics·market·commondata)에는 적용되지 않는다.
- **경로 계약이 혼재** — order 는 `/auth`·`/orders`(비-`/api`)와 `/api/products`·`/admin/**` 이 섞여 있어 게이트웨이 predicates 가 길다. 채널 대면 경로와 내부 경로의 분리가 MCI 층의 미해결 과제.
