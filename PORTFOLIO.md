# Lemuel — 이커머스 + 정산 MSA 플랫폼

> **상품·장바구니·주문·결제·배송·정산·출금** 7개 도메인을 헥사고날 + MSA 로 구현한 백엔드 포트폴리오.
> 코스닥 이하 / 이커머스·결제·정산 솔루션 회사 (카페24·메이크샵·아임웹·토스페이먼츠·KG이니시스 등) 면접용.

🔗 **GitHub**: https://github.com/MyoungSoo7/settlement (`develop` 브랜치)

---

## 한눈에 보기

| 항목 | 수치 |
|---|---|
| **Java / Spring Boot** | 25 / 4.0.4 |
| **마이크로서비스** | 3 (order / settlement / gateway) + shared-common |
| **도메인 수** | 7 (상품·SKU·장바구니·주문·결제·배송·정산·출금) |
| **마이그레이션** | V1 ~ V43 (43 개) |
| **ADR** | 16 개 (0001 ~ 0016) |
| **테스트** | 250+ (단위 + 통합 + ArchUnit + Testcontainers IT) |
| **Prometheus 메트릭** | 30+ 커스텀 |
| **부하테스트 시나리오** | 4 (k6) |

---

## 핵심 어필 포인트 — 다른 포트폴리오에 없는 5가지

### 1. 다중 PG + 격벽 회복탄력성
> *"PG 가 30분 다운되면 어떻게 대응하나요?"* 단골 질문 답변 가능

```
PgRouter — 결제수단 / 거래금액 / health 보고 자동 선택
  TOSS / KCP / NICE / INICIS 4개 어댑터
  PG 별 독립 CircuitBreaker — 한 PG 장애가 격벽
  거래 ID prefix (TOSS:xxx) 로 환불 시 동일 PG 자동 라우팅
```

### 2. Outbox 비동기 경계에서 끊기지 않는 분산 트레이싱
> 이 부분이 시니어급 차별화 — 일반적 Outbox 구현은 trace context 끊김

```
도메인 트랜잭션 시점 W3C trace context → outbox.trace_parent 컬럼 영속화
→ 폴러 발행 시 Kafka 헤더 복원 → 컨슈머 자동 합류
→ Tempo 에서 결제→정산 단일 trace 추적
```

### 3. 분할결제 역순 환불 정책
> *"포인트+카드 같이 쓴 결제를 일부만 환불하면?"*

```
[seq=1: POINT 5,000] [seq=2: GIFT_CARD 10,000] [seq=3: CARD 35,000]
환불 30,000 요청 → 외부 PG 부터 (seq 역순) → CARD 30,000 차감
이유: PG 환불 실패 시 내부 잔액 복원이 더 안전
```

### 4. SKU 동시성 — 100스레드 IT 검증
> 도메인 불변식 + Optimistic Lock + 재시도가 race condition 에서도 깨지지 않는 증거

```
재고 50, 100스레드 동시 차감
→ 정확히 50건 success, 50건 InsufficientStock
→ 최종 재고 0 (음수 없음), version 정확히 50 증가
→ 재시도 한계 초과 0건 (5회 백오프로 모두 흡수)
검증: VariantStockConcurrencyIT (Testcontainers + PostgreSQL 17)
```

### 5. 정산 닫힌 사이클 — Holdback + Payout
> 정산 도메인이 *반쪽이 아니라* 셀러 통장 입금까지

```
결제 CAPTURED
  ↓ Outbox + Kafka
Settlement DONE (T+1/T+3/T+7 영업일, 셀러 등급별)
  ↓
Holdback (NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%)
  ↓ 매일 03:00 KST 자동 해제 배치
Payout REQUESTED → 펌뱅킹 → COMPLETED
  ↓ 일/셀러별 한도 검증
셀러 통장 입금 ✓
```

---

## 면접 자주 묻는 질문 → 답변 위치

| 질문 | 답변 |
|---|---|
| 결제 PG 장애 시 fallback? | [PgRouter](order-service/src/main/java/github/lms/lemuel/payment/adapter/out/pg/PgRouter.java) — fallback chain |
| 이벤트 발행 영구 실패 시? | [DLQ + Admin API](shared-common/src/main/java/github/lms/lemuel/common/outbox/) |
| PG 정산 누락 발견 어떻게? | [PG Reconciliation](settlement-service/src/main/java/github/lms/lemuel/pgreconciliation/) — 5종 분류 |
| 색상 옵션 있는 상품 주문? | [ProductVariant](order-service/src/main/java/github/lms/lemuel/product/domain/ProductVariant.java) |
| 재고 100개에 110건 동시? | [VariantStockConcurrencyIT](order-service/src/test/java/github/lms/lemuel/product/application/service/VariantStockConcurrencyIT.java) |
| 장바구니 다건 결제? | [CheckoutCartService](order-service/src/main/java/github/lms/lemuel/cart/application/service/CheckoutCartService.java) |
| 포인트+카드 분할결제? | [PaymentDomain.createSplit](order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java) |
| Outbox 패턴인데 trace 끊김? | [TraceContextCapture](shared-common/src/main/java/github/lms/lemuel/common/outbox/application/service/TraceContextCapture.java) |
| 정산 주말·공휴일? | [BusinessDayCalculator](settlement-service/src/main/java/github/lms/lemuel/settlement/domain/BusinessDayCalculator.java) — T+N 영업일 |
| 신뢰도 낮은 셀러 환불 다발? | [HoldbackPolicy](settlement-service/src/main/java/github/lms/lemuel/settlement/domain/HoldbackPolicy.java) |
| 정산해서 셀러 입금까지? | [Payout 도메인](settlement-service/src/main/java/github/lms/lemuel/payout/) — 펌뱅킹 + 운영자 콘솔 |
| 이중 송금 어떻게 막나요? | settlement_id UNIQUE + 도메인 멱등 검증 |

---

## 아키텍처 한 장

```
   Client → Gateway (Spring Cloud Gateway)
              ├─→ order-service (8088)
              │     ├─ user · product · variant · cart · order · payment · shipping
              │     └─ Outbox + traceparent → Kafka
              │
              └─→ settlement-service (8082)
                    ├─ settlement · pgreconciliation · payout · report
                    ├─ Read-only Projection (order-service 코드 의존 0)
                    └─ Kafka Consumer + 멱등 3단 방어

   Infra: PostgreSQL 17, Elasticsearch 8.17, Redpanda(Kafka), Tempo, Grafana, Prometheus
```

자세한 구조: [docs/diagrams/architecture.md](docs/diagrams/architecture.md)

---

## 운영성 자산

| 자산 | 위치 |
|---|---|
| **ERD + Sequence 5종** (Mermaid) | [docs/diagrams/](docs/diagrams/) |
| **Architecture Decision Records** 16개 | [docs/adr/](docs/adr/) |
| **k6 부하테스트** 4 시나리오 | [load-test/](load-test/) |
| **Grafana 대시보드** 30+ 메트릭 | [monitoring/grafana/dashboards/](monitoring/grafana/dashboards/) |
| **분산 트레이싱** (Tempo + OTLP) | [docker-compose.yml](docker-compose.yml) |
| **CI/CD** GitHub Actions | [.github/workflows/](.github/workflows/) |
| **Kubernetes 매니페스트** | [k8s/](k8s/) |

---

## 적용 가능 회사군

### 결제 회사 (토스페이먼츠 / KG이니시스 / NICE페이먼츠 / KCP)
**강점**: 다중 PG + Outbox 멱등 3단 + 분할결제 + 분산 트레이싱

### 이커머스 솔루션 (카페24 / 메이크샵 / 아임웹 / NHN커머스)
**강점**: 7 도메인 균형 + SKU + 장바구니 + 셀러 등급별 차등 정산

### 마켓플레이스 (29CM / 무신사 / 에이블리 / 스마트스토어)
**강점**: 정산 사이클 닫힘 (Holdback → Payout) + PG 대사

### 정산 전문 / 핀테크 (페이히어 / 뱅크샐러드 / 토스B)
**강점**: 정산 도메인 깊이 (등급·주기·보류·역정산·대사·송금)

---

## 빠른 둘러보기

5분 만에 시스템 파악:
1. **[README.md](README.md)** 의 *"면접관용 빠른 둘러보기"* 표
2. **[docs/diagrams/architecture.md](docs/diagrams/architecture.md)** — 전체 그림
3. **[docs/diagrams/sequence-payment-to-settlement.md](docs/diagrams/sequence-payment-to-settlement.md)** — 핵심 흐름
4. **[docs/adr/](docs/adr/)** 16개 — 왜 이렇게 설계했는지

10분 시연: [docs/DEMO.md](docs/DEMO.md)

---

## 연락

- 이메일: jinsim37@gmail.com
- GitHub: [MyoungSoo7](https://github.com/MyoungSoo7)
