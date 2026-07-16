# Polyglot Services (MVP)

settlement MSA 에 언어별 강점을 살려 붙인 신규 서비스 5종. 모두 **동작하는 MVP**(핵심 로직 + 헬스체크 + 테스트 + Dockerfile) 이며, 프로덕션 하드닝(실데이터 연동·모델 학습·배포 차트)은 각 README 의 TODO 로 분리했다. 기존 Java(Gradle) 모듈과 별개의 독립 서비스로, `settings.gradle.kts` 에 포함되지 않으므로 Gradle 빌드에 영향이 없다.

| 서비스 | 언어 | 포트 | 역할 | 상태 |
|---|---|---|---|---|
| `market-stream-service` | Go | 8110 | 실시간 시세 스트리밍 (SSE `/stream/{code}` + WS `/ws/{code}`), Hub 팬아웃 | MVP·green |
| `payment-webhook-service` | Go | 8111 | Toss 결제 웹훅 수신 (HMAC 서명검증·멱등) → Kafka `lemuel.payment.confirmed` | MVP·green |
| `screening-backtest-service` | Python/FastAPI | 8120 | 투자 스크리닝 규칙 백테스트 (수익률·MDD·Sharpe·승률) | MVP·green |
| `settlement-anomaly-service` | Python/FastAPI | 8121 | 정산/payout 이상탐지 (MAD z-score + IsolationForest 앙상블) | MVP·green |
| `forecast-service` | Python/FastAPI | 8122 | 정산액/매출 시계열 예측 (Holt-Winters + seasonal-naive) | MVP·green |

## 언어 선택 근거 (polyglot MSA)
- **Go** — 동시성·저지연·엣지. 다수 커넥션 실시간 스트리밍(goroutine 팬아웃)과 빠르고 멱등한 웹훅 수신에 JVM 대비 유리.
- **Python** — 데이터/ML/퀀트. 백테스트(pandas/numpy)·이상탐지(scikit-learn)·시계열 예측(statsmodels)은 JVM 서비스가 채우기 어려운 공백.

## 공통 규약
- 각 서비스: `GET /health`(또는 `/healthz`) → `{"status":"UP"}`, 환경변수 설정, 구조적 로깅, 멀티스테이지 Dockerfile(non-root), 데모 엔드포인트로 무-외부의존 실행 가능.
- Kafka 토픽: `lemuel.<domain>.<event>` (기존 컨벤션).
- 외부 연동(market-service 등)은 env base-url + 인터페이스로 교체 가능하게 두고, 기본값은 시뮬레이션/번들 샘플이라 단독 실행·테스트 가능.

## 로컬 실행/테스트
- Go: `cd <svc> && go test ./... && go run ./cmd/server`
- Python: **Python 3.11** 필수(pinned deps 는 3.14 wheel 없음). `python3.11 -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-dev.txt && .venv/bin/pytest`

## CI
`.github/workflows/polyglot-ci.yml` — 변경된 서비스 경로만 Go(build+test) / Python 3.11(pytest) 매트릭스로 검증. 기존 Java `ci`/harness-guard 와 독립(신규 Java·마이그레이션·ADR 추가 없어 STATUS 카운트 불변).

## 배포 (후속 — helm-deploy 레포)
각 서비스의 helm 차트 + ArgoCD image-updater 이미지 목록 + DB/시크릿 배선은 이 PR 범위 밖. 이미지 태그 컨벤션은 기존 `settlement-<svc>:main` 을 따르되, 비-JVM 이라 별도 이미지 빌드 파이프라인 필요.
