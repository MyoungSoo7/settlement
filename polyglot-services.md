# Polyglot Services (MVP)

settlement MSA 에 언어별 강점을 살려 붙인 신규 서비스 7종(Kotlin 2 + Go 2 + Python 3). 모두 **동작하는 MVP**(핵심 로직 + 헬스체크 + 테스트 + Dockerfile) 이며, 프로덕션 하드닝(실데이터 연동·모델 학습·배포 차트)은 각 README 의 TODO 로 분리했다. 기존 Java(Gradle) 모듈과 별개의 독립 서비스로, `settings.gradle.kts` 에 포함되지 않으므로 Gradle 빌드에 영향이 없다.

| 서비스 | 언어 | 포트 | 역할 | 상태 |
|---|---|---|---|---|
| `notification-service` | Kotlin/Boot | 8130 | 도메인 이벤트 알림 팬아웃 (settlement.confirmed·payment.captured/refunded/confirmed·investment.executed 구독 → 채널 발송) | MVP·green |
| `reconciliation-service` | Kotlin/Boot | 8131 | 대사 스케줄러 스켈레톤 (HTTP 소스 인터페이스만 — 실소스 빈 미등록) | MVP·skeleton |
| `market-stream-service` | Go | 8110 | 실시간 시세 스트리밍 (SSE `/stream/{code}` + WS `/ws/{code}`), Hub 팬아웃 | MVP·green |
| `payment-webhook-service` | Go | 8111 | Toss 결제 웹훅 수신 (HMAC 서명검증·멱등) → Kafka `lemuel.payment.confirmed` | MVP·green |
| `screening-backtest-service` | Python/FastAPI | 8120 | 투자 스크리닝 규칙 백테스트 (수익률·MDD·Sharpe·승률) | MVP·green |
| `settlement-anomaly-service` | Python/FastAPI | 8121 | 정산/payout 이상탐지 (MAD z-score + IsolationForest 앙상블) | MVP·green |
| `forecast-service` | Python/FastAPI | 8122 | 정산액/매출 시계열 예측 (Holt-Winters + seasonal-naive) | MVP·green |

## 언어 선택 근거 (polyglot MSA)
- **Kotlin** — JVM 생태(Spring Kafka·스케줄러)를 그대로 쓰되 코루틴/간결 문법으로 이벤트 팬아웃·배치성 워크로드를 가볍게.
- **Go** — 동시성·저지연·엣지. 다수 커넥션 실시간 스트리밍(goroutine 팬아웃)과 빠르고 멱등한 웹훅 수신에 JVM 대비 유리.
- **Python** — 데이터/ML/퀀트. 백테스트(pandas/numpy)·이상탐지(scikit-learn)·시계열 예측(statsmodels)은 JVM 서비스가 채우기 어려운 공백.

## 알려진 한계 (무영속 MVP — 통신면 감사 2026-07-17 반영)
- **멱등 저장소가 in-memory·휘발성**: notification(TTL 30분)·payment-webhook(`eventType:paymentKey`, TTL 24h)의
  dedup 은 재시작/스케일아웃 시 소실된다 → 알림 중복 발송·웹훅 재전송의 Kafka 재발행 가능. 하류 Java 컨슈머는
  `processed_events` 멱등으로 방어되므로 회계 영향은 없다. 내구 멱등이 필요해지면 Redis 등 외부 스토어로 교체.
- **notification 은 auto-commit(at-least-once)**: 처리 중 크래시 시 재소비 → 채널 부분 중복 발송 가능. DLT 없음
  (파싱 실패는 skip, 그 외 예외는 log 후 진행 — 컨테이너는 죽지 않음). 리스너 기본 OFF(`APP_KAFKA_ENABLED:false`).
- **reconciliation 의 HTTP 소스는 스켈레톤**: `SETTLEMENT_BASE_URL`/`PAYMENT_BASE_URL`(기본 `order-service:8080`)
  프로퍼티만 있고 실소스 빈이 등록되지 않아 fetch 가 빈 리스트를 반환한다.
- **screening-backtest 의 `MARKET_BASE_URL` 은 선언만 존재**: 코드는 번들 샘플(`sample_backtest.json`)만 사용 —
  실연동 경로 미구현.

## 공통 규약
- 각 서비스: `GET /health`(또는 `/healthz`, Kotlin reconciliation 은 Spring Actuator `/actuator/health`) → `{"status":"UP"}`, 환경변수 설정, 구조적 로깅, 멀티스테이지 Dockerfile(non-root), 데모 엔드포인트로 무-외부의존 실행 가능.
- Kafka 토픽: `lemuel.<domain>.<event>` (기존 컨벤션).
- 외부 연동(market-service 등)은 env base-url + 인터페이스로 교체 가능하게 두고, 기본값은 시뮬레이션/번들 샘플이라 단독 실행·테스트 가능.

## 로컬 실행/테스트
- Go: `cd <svc> && go test ./... && go run ./cmd/server`
- Python: **Python 3.11** 필수(pinned deps 는 3.14 wheel 없음). `python3.11 -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-dev.txt && .venv/bin/pytest`

## CI
`.github/workflows/polyglot-ci.yml` — `changes` 잡(dorny/paths-filter)이 **변경된 서비스만** 골라
Go(build+vet+test -race) / Python 3.11(pytest) / Kotlin(gradle build) 매트릭스와 이미지 푸시 매트릭스를
동적으로 계산한다(서비스 단위 CI — ci.yml 의 JVM 14종과 동일 패턴, 워크플로 파일 변경 시엔 7종 전부 폴백).
기존 Java `ci`/harness-guard 와 독립(신규 Java·마이그레이션·ADR 추가 없어 STATUS 카운트 불변).

## 배포 (후속 — helm-deploy 레포)
각 서비스의 helm 차트 + ArgoCD image-updater 이미지 목록 + DB/시크릿 배선은 이 PR 범위 밖. 이미지 태그 컨벤션은 기존 `settlement-<svc>:main` 을 따르되, 비-JVM 이라 별도 이미지 빌드 파이프라인 필요.
