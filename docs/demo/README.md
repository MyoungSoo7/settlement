# 시연 데모 자료

3분 영상 녹화 부담을 30분으로 줄이기 위한 자료. Postman 컬렉션 + 시드 SQL + 실행 스크립트.

## 파일 구성

| 파일 | 용도 |
|---|---|
| `postman-collection.json` | Postman / Thunder Client 임포트 — 시연 5막 8 요청 |
| `postman-environment.json` | 환경 변수 (BASE_URL, JWT_TOKEN, DEMO_PAYMENT_ID 등) |
| `seed-data.sql` | 시연용 결제·정산·Payout 시드 — 깨끗한 시작점 |
| `seed.sh` | 한 번에 실행: docker compose up + seed SQL + 토큰 발급 |
| `reset.sh` | 시연 후 데모 데이터만 정리 (재녹화용) |

---

## 사전 1회 세팅 (5분)

```bash
# 1. 의존 설치 — Docker / curl / psql / jq
brew install jq postgresql            # macOS
sudo apt install jq postgresql-client # Ubuntu

# 2. Postman / Thunder Client 설치
# Postman: https://www.postman.com/downloads/
# Thunder Client: VS Code Extension (가벼움, 추천)

# 3. 컬렉션 + 환경 임포트
# Postman: Import → Folder → demo/
# Thunder Client: 우측 컬렉션 패널 → Import
```

---

## 녹화 직전 (3분)

```bash
cd demo

# 1) 인프라 + 서비스 기동 + 시드 데이터 삽입 + JWT 토큰 출력
./seed.sh

# 출력:
#  ✅ Postgres ready
#  ✅ order-service healthy
#  ✅ settlement-service healthy
#  ✅ Seed data inserted
#  📋 JWT_TOKEN=eyJhbGciOiJIUzI1NiJ9...
#  📋 DEMO_PAYMENT_ID=9001
#  📋 Postman 환경 변수에 복사하세요

# 2) Loom / OBS 녹화 시작
# 3) Postman 컬렉션 5폴더 위→아래로 클릭하면 끝
```

---

## 시연 5막 (DEMO.md 의 스크립트와 1:1 매핑)

| 폴더 | 시간 | 핵심 |
|---|---|---|
| `00. Health` | 5초 | 모든 서비스 healthy 확인 |
| `01. 분할결제` | 40초 | 50,000 = POINT 5K + GIFT_CARD 10K + CARD 35K → 30,000 부분환불 (역순) |
| `02. 100스레드 동시성 IT` | 40초 | IDE 에서 `VariantStockConcurrencyIT` 실행 — 콘솔 출력 캡처 |
| `03. Outbox 단일 trace` | 40초 | 결제 capture → Tempo 에서 단일 trace 검색 |
| `04. DLQ 콘솔` | 30초 | failed 목록 → retry → skip |
| `05. Payout 사이클` | 30초 | pending → execute-now → failed (10% 시뮬) → retry |

녹화 도구·자막·업로드 가이드: `docs/DEMO.md` 참조.

---

## 자주 묻는 트러블슈팅

**Q. JWT 401 Unauthorized**
```bash
./seed.sh   # 토큰 재발급 — 1시간 만료
```

**Q. 시드 SQL 충돌 (이미 9001 ID 존재)**
```bash
./reset.sh && ./seed.sh
```

**Q. Tempo 에 trace 안 보임**
- `app.kafka.enabled=true` 확인 — Outbox 폴러가 동작해야 traceparent 전파됨
- `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces` 환경 변수 확인

**Q. Payout 모두 COMPLETED 만 보임 (FAILED 없음)**
```bash
# 펌뱅킹 실패율 0.3 (30%) 으로 설정해서 데모 화려하게
APP_FIRMBANKING_FAILURE_RATE=0.3 docker compose up -d settlement-service
```
