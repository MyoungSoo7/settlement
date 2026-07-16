# 모듈 구조 (Module Structure)

> 저장소 전체 디렉토리·모듈 구조의 정본. 서비스 책임·API 는 [`SPEC.md`](./SPEC.md),
> 아키텍처 개요·패턴은 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md), 에이전트 지침은 [`CLAUDE.md`](./CLAUDE.md) 참조.

## JVM 코어 — Gradle 멀티모듈 (Java 13 서비스 + Gateway + shared-common)

```
settlement/                              # 모노레포 루트
├── settings.gradle.kts                  # 13 서비스 + gateway 모듈 선언 (shared-common 은 composite build)
├── build.gradle.kts                     # 부모 빌드 (subprojects 공통 설정, JaCoCo LINE 90% 게이트)
├── docker-compose.yml                   # PG 13종 · ES · Redpanda · 13 services + gateway
├── Dockerfile                           # MODULE 빌드 인자 파라미터화 (JVM 서비스 공용)
│
├── shared-common/                       # 📦 버전드 플랫폼 라이브러리 (java-library, ADR 0021)
│   ├── src/main/java/.../common/
│   │   ├── audit/                       # 감사 로그 (AuditLogger, AuditContext)
│   │   ├── config/jwt/                  # JWT 검증, SecurityConfig
│   │   ├── observability/               # MDC, TraceId 필터, PII 마스킹
│   │   ├── exception/                   # 공통 예외 (BusinessException 등)
│   │   ├── outbox/                      # Outbox 패턴 (이벤트 발행, 멱등 컨슈머)
│   │   ├── money/                       # 금액 VO·라운딩 (BigDecimal 강제)
│   │   ├── ledger/                      # 복식부기 공통 (균형 팩토리)
│   │   ├── opssignal/                   # 운영 신호 발행 (절대 throw 금지, fire-and-forget)
│   │   ├── ratelimit/                   # Bucket4j 기반 rate limiting
│   │   └── pdf/                         # iText PDF 유틸
│   └── src/testFixtures/resources/contracts/events/   # ★ 이벤트 계약 정본 (12토픽 JSON Schema+샘플, ADR 0024)
│
├── order-service/                       # 🛒 Commerce (8088, opslab)
│   └── .../{user,order,payment,cart,shipping,product,category,coupon,review,game,menu,rbac,commoncode}
│       ├── recon/                       # /internal/recon — 자기 합계 노출(settlement 대사용, ADR 0020)
│       └── projectionbackfill/          # settlement 프로젝션 백필 (ADR 0020)
│
├── settlement-service/                  # 💰 Settlement (8082/mgmt 8083, standalone, settlement_db)
│   └── .../{settlement,payout,ledger,chargeback,pgreconciliation,report,recon,integrity}
│       ├── settlement/adapter/in/kafka/     # Payment/Order/User/Product 이벤트 컨슈머 (프로젝션 적재)
│       ├── settlement/adapter/out/readmodel/# ★ 이벤트 프로젝션 뷰 (settlement_*_view, 자체 DB 소유)
│       ├── settlement/adapter/{in/batch, out/search, out/pdf}  # Spring Batch · ES 색인 · 정산서 PDF
│       └── recon/                       # OrderReconClient — order /internal/recon 호출(cross-DB 0)
│
├── loan-service/                        # 💸 Loan (8084, lemuel_loan) — 선정산 + 기업대출(CEO) + 상환 시뮬레이션
├── financial-statements-service/        # 📊 Financial (8086, lemuel_financial) — DART 재무제표 공개조회 ★
├── economics-service/                   # 📈 Economics (8087, lemuel_economics) — ECOS 경제지표 공개조회 ★
├── company-service/                     # 📰 Company (8090, lemuel_company) — 뉴스·평판·문서함 (ADR 0023)
├── operation-service/                   # 🖥️ Operation (8092, lemuel_operation) — 운영 관제(인시던트·신호·이상탐지)
├── market-service/                      # 📉 Market (8094, lemuel_market) — KRX 시세·시총 공개조회 ★ (PER/PBR 미계산)
├── ai-service/                          # 🤖 AI (8096, lemuel_ai·pgvector) — 챗봇 (Gemini/Anthropic provider 스위치)
├── common-data-service/                 # 🗂️ Common-Data (8098, lemuel_commondata) — data.go.kr 범용 커넥터 ★
├── investment-service/                  # 📈 Investment (8100/mgmt 8101, lemuel_investment) — CEO 투자하기
├── account-service/                     # 🏦 Account (8102, lemuel_account) — 계정계 GL 집계 (소비 전용)
├── organization-service/                # 👥 Organization (8104, lemuel_organization) — 조직·멤버십 (발행 전용)
└── gateway-service/                     # 🚪 API Gateway (8080) — 라우팅만 (자체 인증 필터 없음)
```

- `★` = 공개 read-only 위성(shared-common 미의존): GET 공개, `/admin/**` 는 X-Internal-Api-Key 게이트.
- 각 서비스 내부는 헥사고날 고정 골격: `domain/` · `application/port/{in,out}·service/` · `adapter/{in,out}/`.

## 폴리글랏 7종 — standalone (Gradle·gateway 미포함, 루트 레벨 디렉토리)

```
├── market-stream-service/               # 🟢 Go  :8110  실시간 시세 SSE/WebSocket (goroutine Hub 팬아웃)
├── payment-webhook-service/             # 🟢 Go  :8111  Toss 결제 웹훅(HMAC·멱등) → Kafka lemuel.payment.confirmed
├── screening-backtest-service/          # 🐍 Py  :8120  투자 스크리닝 백테스트 (pandas·numpy)
├── settlement-anomaly-service/          # 🐍 Py  :8121  정산/payout 이상탐지 (scikit-learn)
├── forecast-service/                    # 🐍 Py  :8122  정산액/매출 시계열 예측 (statsmodels)
├── notification-service/                # 🅺 Kt  :8130  이벤트 5토픽 → 다채널 알림 (코루틴 팬아웃·멱등)
└── reconciliation-service/              # 🅺 Kt  :8131  정산 대사 (sealed Discrepancy·병렬 fetch·19:00 cron)
```

- 자체 DB 없음(무영속 MVP) · CI 는 `.github/workflows/polyglot-ci.yml` 분리 · 배포는 전용 차트 격리
  (`charts/polyglot-services`, helm-deploy 레포). 정본: [`polyglot-services.md`](./polyglot-services.md).

## 부속 디렉토리

```
├── frontend/                            # ⚛️ React(Vite) 관리자/쇼핑 프론트 — nginx 프록시로 gateway 연동
├── docs/                                # 📚 ADR 26개(adr/) · 러너북(runbook/) · ARCHITECTURE · DEVELOPMENT · 검증(SETTLEMENT-VERIFICATION)
├── monitoring/                          # 📊 Prometheus·Grafana 대시보드(비즈니스 KPI)·alert rules
├── load-test/                           # 🔥 k6 부하 시나리오 4종
├── scripts/harness/                     # 🛡️ 저장소 가드(guard.mjs)·자기진단(harness-audit.mjs)·git hook 설치
├── hackathon/ · pwc/ · fashion-copilot/ # 🏆 해커톤·제출물 (플러그인·리포트 — 원격 실행 목적 추적)
└── https/ · gradle/                     # 로컬 TLS · Gradle wrapper
```
