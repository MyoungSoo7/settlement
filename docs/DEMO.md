# 시연 영상 스크립트 — Loom 3분

> 면접 자리에서 *"코드 보여주실 수 있나요?"* 대신 1줄 메시지로 영상 링크 보내기.
> 링크는 README 와 PORTFOLIO.md 에 박아둘 것.

## 사전 준비

```bash
# 1) 인프라 + 서비스 전체 기동
docker compose up -d

# 2) 데모용 펌뱅킹 실패율 10% 활성 (운영자 retry 콘솔 자연스럽게 보여주기 위함)
export APP_FIRMBANKING_FAILURE_RATE=0.1
docker compose restart settlement-service

# 3) Grafana 미리 띄워두기 — http://localhost:3001 (anonymous admin 자동)
# 4) Tempo trace 검색창 미리 열어두기
# 5) Postman / Thunder Client 컬렉션 미리 import
```

녹화 도구: **Loom** (브라우저 + 카메라 동시 녹화). 또는 OBS / QuickTime.

---

## 3분 스크립트 — 5 막

### 0:00 ~ 0:20 | 인트로 (20초)
> *"안녕하세요, [이름]입니다. 이 영상은 Lemuel 이커머스+정산 MSA 백엔드 포트폴리오의 핵심 5가지를 3분 안에 보여드립니다.*
> *1) 분할결제 → 2) 100스레드 동시 SKU 차감 → 3) Outbox 비동기 trace 추적 → 4) DLQ 운영자 콘솔 → 5) Payout 송금 사이클 입니다."*

화면: README.md 의 *"면접관용 빠른 둘러보기"* 표 (3초)

---

### 0:20 ~ 1:00 | 분할결제 (40초) — *"포인트 + 카드 + 상품권 한번에"*

```bash
curl -X POST http://localhost:8088/payments/split \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 1,
    "tenders": [
      {"type": "POINT",     "amount": 5000},
      {"type": "GIFT_CARD", "amount": 10000},
      {"type": "CARD",      "amount": 35000}
    ]
  }'
```

> *"50,000원 결제를 포인트 5,000 + 상품권 10,000 + 카드 35,000으로 분할했습니다.*
> *도메인이 합계를 자동 계산해서 amount 외부 수동 지정이 불가능하기 때문에 영수증·정산 정합성이 도메인 차원에서 보장됩니다."*

이어서 30,000원 부분환불:
```bash
curl -X POST http://localhost:8088/payments/split/{paymentId}/refund \
  -d '{"amount": 30000}'
```

> *"환불은 sequence 역순 — 외부 PG (CARD) 부터. 응답 보시면 CARD 에서만 30,000 차감됐습니다.*
> *PG 환불 실패 시 내부 잔액은 안 건드린 채 운영자 알람 — 운영 사고 방지 패턴입니다."*

---

### 1:00 ~ 1:40 | 100스레드 동시성 IT (40초)

화면: IDE 에서 `VariantStockConcurrencyIT.java` 실행

```bash
./gradlew :order-service:test --tests "*VariantStockConcurrencyIT"
```

> *"재고 50인 SKU 에 100스레드가 동시 1개 차감 시도. JPA @Version 으로 Optimistic Lock,*
> *충돌 시 5회 지수 백오프 재시도. 결과는 *정확히* 50건 성공 / 50건 InsufficientStock,*
> *최종 재고 0 (음수 없음), version 정확히 50 증가."*

테스트 결과 캡처 — `BUILD SUCCESSFUL` + 메트릭 출력

> *"Testcontainers + 실제 PostgreSQL 17 로 검증되어서, 단순 mock 이 아닌 진짜 DB 락 동작입니다."*

---

### 1:40 ~ 2:20 | Outbox 비동기 trace + Tempo (40초)

화면: 결제 capture 호출

```bash
curl -X POST http://localhost:8088/payments/{id}/capture
```

이어서 Grafana → Explore → Tempo 검색

> *"방금 결제의 trace ID 를 Tempo 에서 검색하면, HTTP 결제 → 결제 도메인 → Outbox INSERT*
> *→ 폴러 → Kafka 발행 → 컨슈머 → 정산 도메인까지 단일 trace 로 보입니다."*

화면: Tempo 의 단일 trace flame graph

> *"보통 Outbox 패턴은 폴러가 새 trace 를 만들어서 비동기 경계에서 끊어집니다.*
> *outbox_events 테이블에 trace_parent 컬럼을 추가해 도메인 트랜잭션 시점의 W3C trace context*
> *를 영속화 → 폴러가 Kafka 헤더로 복원 → 컨슈머 자동 합류. 시니어급 차별화 포인트입니다."*

---

### 2:20 ~ 2:50 | DLQ 운영자 콘솔 + Payout (30초)

화면: DLQ 콘솔
```bash
curl http://localhost:8088/admin/outbox/dlq
```

> *"이벤트 발행이 10회 재시도 모두 실패하면 자동으로 Kafka DLQ 토픽 발행 + 운영자 콘솔 노출.*
> *재처리 / 스킵 (사유 필수) 가능, 모든 액션은 audit log 기록됩니다."*

화면: Payout 콘솔 — failure-rate 0.1 설정 덕분에 일부 FAILED 표시됨
```bash
curl http://localhost:8082/admin/payouts/failed
curl -X POST http://localhost:8082/admin/payouts/{id}/retry
```

> *"송금 실패한 Payout 도 같은 운영자 콘솔 패턴. 펌뱅킹 mock 이 10% 무작위 실패하도록*
> *시뮬레이션해서 retry 워크플로 검증. 이중 송금은 settlement_id UNIQUE 인덱스로 DB 차단."*

---

### 2:50 ~ 3:00 | 마무리 (10초)

화면: Grafana 대시보드 (Lemuel Business KPI)

> *"Prometheus 30+ 커스텀 메트릭으로 4 영역 (결제·Outbox·재고·정산) 모니터링.*
> *전체 코드는 GitHub MyoungSoo7/settlement, ADR 16개 + 다이어그램 5개 + k6 부하테스트 4종*
> *문서로 정리되어 있습니다. 감사합니다."*

---

## 녹화 팁

1. **속도 1.0x ~ 1.2x**: 너무 느리면 면접관이 끝까지 안 봄
2. **자막 강제**: 한국어 자막 자동생성 후 검수 — 무음 환경 시청 대응
3. **터미널 폰트 크기 16pt+**: 모바일에서도 코드 보이게
4. **마우스 커서 강조**: Loom 자동, OBS 는 별도 플러그인
5. **첫 5초가 중요**: 인트로 후 바로 분할결제 결과 화면. 면접관 흥미 잡기

## 영상 업로드 후

- README.md 최상단에 *"📺 [3분 시연 영상](https://www.loom.com/share/...)"* 추가
- PORTFOLIO.md 에도 추가
- 이력서 / 자기소개서 / 지원서 *"포트폴리오"* 항목에 GitHub 링크 + 영상 링크 함께
- LinkedIn / Velog / Medium 게시 시 영상 임베드

## 영상 업로드 옵션

| 플랫폼 | 장점 | 단점 |
|---|---|---|
| **Loom** | 임베드 쉬움, 분석 (누가 어디까지 봤는지) | 무료 5분 한도 |
| **YouTube (unlisted)** | 영구·무한, 자막 자동 | 광고 위험 |
| **Vimeo** | 깔끔, 광고 없음 | 무료 한도 적음 |

추천: **Loom** (3분이면 충분하고 면접관 시청 추적 가능).
