# Lemuel — 이커머스 + 정산 MSA 플랫폼

> **상품·장바구니·주문·결제·배송·정산·선정산대출** 도메인을 **헥사고날 + MSA(DB-per-service)** 로 구현한 백엔드 포트폴리오.
> 이커머스·결제·정산 솔루션 회사 (카페24·메이크샵·아임웹·토스페이먼츠·KG이니시스 등) 면접용.

🔗 **GitHub**: https://github.com/MyoungSoo7/settlement (`develop` 브랜치)

---

## 한눈에 보기

| 항목 | 수치 |
|---|---|
| **Java / Spring Boot** | 25 / 4.0.4 |
| **마이크로서비스** | 3 비즈니스(order / settlement / loan) + API Gateway + `shared-common` 라이브러리 |
| **DB 분리** | DB-per-service — opslab / settlement_db / lemuel_loan (물리 분리, cross-DB 연결 0) |
| **마이그레이션** | 70 개 (order 63 / settlement 2 / loan 5) |
| **ADR** | 21 개 (0001 ~ 0022, 0019 결번) |
| **테스트** | 159 테스트 클래스 (단위 + ArchUnit + Testcontainers 통합 13개) |
| **부하테스트** | 4 시나리오 (k6) |

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

## 면접 자주 묻는 질문 → 답변 위치

| 질문 | 답변 |
|---|---|
| MSA 인데 DB 공유 아닌가요? | [settlement readmodel 프로젝션](settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/readmodel/) + [OrderReconClient](settlement-service/src/main/java/github/lms/lemuel/recon/OrderReconClient.java) |
| 결제 PG 장애 시 fallback? | [PgRouter](order-service/src/main/java/github/lms/lemuel/payment/adapter/out/pg/PgRouter.java) — fallback chain |
| 환불 중 PG 가 죽으면? | [RefundPaymentUseCase](order-service/src/main/java/github/lms/lemuel/payment/application/RefundPaymentUseCase.java) — 예외 변환 + 롤백, 유령 환불 방지 |
| 이벤트 발행 영구 실패 시? | [DLQ + Admin API](shared-common/src/main/java/github/lms/lemuel/common/outbox/) |
| PG 정산 누락 발견 어떻게? | [PG Reconciliation](settlement-service/src/main/java/github/lms/lemuel/pgreconciliation/) — 5종 분류 + 승인 시 보정 이벤트 |
| 재고 100개에 110건 동시? | [DecreaseVariantStockService](order-service/src/main/java/github/lms/lemuel/product/application/service/DecreaseVariantStockService.java) — 조건부 원자 UPDATE |
| 포인트+카드 분할결제 일부 환불? | [PaymentDomain.planRefundFromTenders](order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java) — seq 역순 |
| Outbox 패턴인데 trace 끊김? | [KafkaOutboxPublisher](shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/out/event/KafkaOutboxPublisher.java) — traceparent 헤더 복원 |
| 주문 상태 잘못된 전이 막나요? | [OrderStatus.canTransitionTo / Order.transitionTo](order-service/src/main/java/github/lms/lemuel/order/domain/OrderStatus.java) — 상태머신 가드 |
| 정산 주말·공휴일? | [BusinessDayCalculator](settlement-service/src/main/java/github/lms/lemuel/settlement/domain/BusinessDayCalculator.java) — T+N 영업일 |
| 신뢰도 낮은 셀러 환불 다발? | [HoldbackPolicy](settlement-service/src/main/java/github/lms/lemuel/settlement/domain/HoldbackPolicy.java) |
| 정산해서 셀러 입금까지? | [Payout 도메인](settlement-service/src/main/java/github/lms/lemuel/payout/) — 펌뱅킹 + 운영자 콘솔 |
| 선정산 대출은 어떻게 연계? | [loan-service](loan-service/src/main/java/github/lms/lemuel/loan/) — settlement 이벤트 saga |
| 이중 송금/상환 어떻게 막나요? | settlement_id / payment_id UNIQUE + 도메인 멱등 검증 |

---

## 아키텍처 한 장

```
   Client → Gateway (Spring Cloud Gateway :8080)
              ├─→ order-service (:8088)   ── PostgreSQL opslab
              │     user·product·variant·cart·order·payment·shipping
              │     coupon·review·game · 관리자(rbac·menu·commoncode)
              │     └─ Outbox + traceparent → Kafka
              │
              ├─→ settlement-service (:8082) ── settlement_db
              │     settlement·payout·ledger·chargeback·pgreconciliation·report
              │     이벤트 드리븐 프로젝션(settlement_*_view, 코드·DB 의존 0)
              │     Kafka Consumer + 멱등 3단 방어 / 대사: /internal/recon 호출
              │
              └─→ loan-service (:8084)    ── lemuel_loan
                    선정산 대출 · 상환 saga · 자체 복식부기 (settlement 이벤트로만 연계)

   Infra: PostgreSQL 17(×3), Elasticsearch 8.17, Redpanda(Kafka), Tempo, Grafana, Prometheus
```

자세한 구조: [README.md](README.md) 의 아키텍처 다이어그램 · [docs/adr/](docs/adr/)

---

## 운영성 자산

| 자산 | 위치 |
|---|---|
| **Architecture Decision Records** 21개 | [docs/adr/](docs/adr/) |
| **k6 부하테스트** 4 시나리오 | [load-test/](load-test/) |
| **Grafana 대시보드** + 커스텀 메트릭 | [monitoring/grafana/dashboards/](monitoring/grafana/dashboards/) |
| **분산 트레이싱** (Tempo + OTLP) | [docker-compose.yml](docker-compose.yml) |
| **장애 대응 Runbook** | [docs/runbook/](docs/runbook/) |
| **CI/CD** GitHub Actions | [.github/workflows/](.github/workflows/) |
| **Kubernetes 매니페스트** | [k8s/](k8s/) |

---

## 적용 가능 회사군

### 결제 회사 (토스페이먼츠 / KG이니시스 / NICE페이먼츠 / KCP)
**강점**: 다중 PG + Outbox 멱등 3단 + 분할결제 역순 환불 + 분산 트레이싱

### 이커머스 솔루션 (카페24 / 메이크샵 / 아임웹 / NHN커머스)
**강점**: 균형 잡힌 커머스 도메인 + SKU 동시성 + 장바구니 + 셀러 등급별 차등 정산 + 관리자(RBAC/메뉴/공통코드)

### 마켓플레이스 (29CM / 무신사 / 에이블리 / 스마트스토어)
**강점**: 정산 사이클 닫힘 (Holdback → Payout) + PG 대사 + 선정산 대출

### 정산 전문 / 핀테크 (페이히어 / 뱅크샐러드 / 토스B)
**강점**: 정산 도메인 깊이 (등급·주기·보류·역정산·대사·송금·복식부기) + MSA 경계(코드·DB 의존 0)

---

## 빠른 둘러보기

5분 만에 시스템 파악:
1. **[README.md](README.md)** 의 *"면접관용 빠른 둘러보기"* 표 + 아키텍처 다이어그램
2. **[CLAUDE.md](CLAUDE.md)** — 서비스 책임 분리 + 이벤트 드리븐 프로젝션 패턴
3. **[docs/adr/0020-order-settlement-db-split.md](docs/adr/0020-order-settlement-db-split.md)** — DB 물리 분리 결정
4. **[docs/adr/](docs/adr/)** 21개 — 왜 이렇게 설계했는지

---

## 연락

- 이메일: jinsim37@gmail.com
- GitHub: [MyoungSoo7](https://github.com/MyoungSoo7)
