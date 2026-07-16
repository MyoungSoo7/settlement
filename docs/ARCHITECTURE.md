# 아키텍처 개요 (Architecture Overview)

> Lemuel 은 **이커머스 주문 → 셀러 정산 → 복식부기 원장**을 코어로, 그 위에 대출·투자·계정계·재무제표·경제지표·기업평판·운영관제·시세·AI·공공데이터·실시간/ML/이벤트 서비스를 확장한 **폴리글랏 MSA 플랫폼**이다.
> 본 문서는 *현재 서비스 구성 · 적용 아키텍처 · 디자인 패턴 · 기술 스택*을 한 곳에서 정리한다. 결정 배경은 [ADR](adr/) 참조.

---

## 1. 서비스 인벤토리 — 21개 서비스 (+ 플랫폼 라이브러리)

**언어를 능력에 맞게 배치한 폴리글랏 MSA**: JVM(Java/Kotlin)으로 도메인 정합성·트랜잭션, Go 로 동시성·엣지, Python 으로 데이터/ML.

### JVM · Java 14 (핵심 도메인 · 정합성)

| # | 서비스 | 포트 | 도메인 / 역할 |
|---|---|---|---|
| 1 | **order-service** | 8088 | 커머스 코어 — user·order·payment·cart·shipping·product·category·coupon·review. settlement-service 를 라이브러리로 번들 |
| 2 | **settlement-service** | 8082/8083 | 정산 코어 — 정산 상태머신·Outbox·역정산·원장(복식부기)·payout·정산 검색(ES). ADR 0020 으로 독립 프로세스 분리 |
| 3 | **gateway-service** | 8080 | API Gateway (Spring Cloud Gateway) — 라우팅·인증·레이트리밋 |
| 4 | **loan-service** | — | 선정산·기업대출 (settlement 확정 이벤트 수신) |
| 5 | **account-service** | 8102 | 계정계 — 집계·시산표·잔액·현금 인식(payout) |
| 6 | **organization-service** | 8104 | 조직/멤버십 |
| 7 | **operation-service** | — | 운영관제 콘솔 (ADMIN 전용, ops 이벤트) |
| 8 | **investment-service** | 8100/8101 | 투자 — 규칙 스크리닝 추천·투자점수·재원·매매계획 |
| 9 | **financial-statements-service** | 8086 | 코스피 상장사 재무제표 (DART) |
| 10 | **economics-service** | 8087 | 경제지표 (한국은행 ECOS) |
| 11 | **company-service** | 8090 | 기업 뉴스·평판 (LLM 감성분석) |
| 12 | **market-service** | 8094 | 주식 시세 (일별 종가 시계열) |
| 13 | **common-data-service** | — | 공공데이터 |
| 14 | **ai-service** | 8096 | AI 챗봇 (Gemini/Claude provider 스위치) |

> **shared-common** — 버전드 플랫폼 라이브러리(ADR 0021, composite build + maven-publish). JWT SecurityConfig · Outbox · 멱등 인프라 · JacksonCompat 등 코어 서비스가 공유. *서비스가 아니라 라이브러리.*

### Polyglot · Go 2 (동시성 · 엣지)

| 서비스 | 포트 | 역할 | 핵심 |
|---|---|---|---|
| **market-stream-service** | 8110 | 실시간 시세 스트리밍 (SSE `/stream/{code}` + WebSocket) | goroutine Hub 팬아웃(누수 0) |
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
| **notification-service** | 8130 | 도메인 이벤트(Kafka) → 다채널(log/Slack/email) 알림 | 코루틴 I/O 팬아웃 · 채널별 타임아웃/재시도 격리 · eventId 멱등 |
| **reconciliation-service** | 8131 | 정산 대사 (settlement ↔ PG/payout/원장) | sealed Discrepancy(MISSING/EXTRA/AMOUNT/STATUS) · 다소스 코루틴 병렬 fetch · @Scheduled |

**합계**: Java 14 + Go 2 + Python 3 + Kotlin 2 = **21 서비스** (+ shared-common 라이브러리).

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
| **JVM 언어** | Java 25 (코어 14 서비스) · Kotlin 2.0 (신규 이벤트 서비스 2종) |
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
- 거버넌스: `harness-guard` 가 STATUS.md 정본(서비스·테스트클래스·마이그레이션·ADR 수)을 실제와 대조해 문서-코드 드리프트를 CI 에서 차단.

---

## 6. 진화 (Evolution)

단일 모놀리스 → **Bounded Context 분리** → **이벤트 드리븐** → **DB-per-service + CQRS 프로젝션**(ADR 0020) → **폴리글랏 MSA(Go/Python/Kotlin)** 로 확장. 커머스·정산의 *깊이*(상태머신·Outbox·복식부기·멱등)를 시그니처로, 도메인·언어 양방향 확장력을 증명한다.
