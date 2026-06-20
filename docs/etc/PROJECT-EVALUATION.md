# 정산 프로젝트 완성도 평가 · 기준 · 개선 체크리스트

> 코드 근거 기반 평가(2026-06). order-service / settlement-service / shared-common / gateway-service +
> frontend 전수 조사 결과를 종합했다. 점수는 "결제·정산 금융 시스템" 기준의 상대 평가다.

---

## Part 1. 완성도 평가 (스코어카드)

| # | 평가 영역 | 등급 | 한 줄 평 |
|---|-----------|------|----------|
| 1 | 도메인 완성도 (정산 lifecycle) | **A** | settlement·payout·ledger 프로덕션급. chargeback/pg대사 일부 deferred |
| 2 | 헥사고날·MSA 경계 | **A−** | read-model 격리 모범적, ArchUnit 강제. 단 settlement-service엔 ArchUnit 없음 |
| 3 | 이벤트·정합성 (Outbox/멱등) | **A** | 멱등 3단 검증됨, traceparent 전파, 트랜잭션 경계 보강 우수 |
| 4 | 동시성 안전성 | **B+** | 비관락+낙관락+격리수준 적용. 단 동시성 통합테스트 ~2건뿐 |
| 5 | 데이터 무결성 (복식부기) | **B** | 엔트리 단위 제약 OK, **차대 합계(SUM=0) 불변식은 앱계층 의존** |
| 6 | 테스트 | **C+** | 커버리지 ~38%(목표 70%), gateway 0건, 동시성 검증 빈약 |
| 7 | 관측성 | **B+** | 커스텀 메트릭·알람 15+·PII 마스킹. 분산추적 @Timed 미부착 |
| 8 | 회복탄력성 | **B** | PG 서킷브레이커·Kafka DLQ 있음. **Outbox FAILED 재시도 없음** |
| 9 | 보안 | **B** | JWT·RBAC·감사·마스킹 충실. **계좌 암호화 미구현(평문)**, rate limit 미적용 |
| 10 | 운영/DevOps | **B+** | Flyway ~50개 정연, graceful shutdown, k8s probe. 문서화 충실 |

**종합: B+ / A−** — 핵심 자금 흐름(결제→정산→원장→지급)은 프로덕션급이며 멱등·트랜잭션 설계가 돋보인다.
감점은 **(a) 테스트 커버리지·동시성 검증, (b) 미연결 배선(환불→정산, pg대사→보정), (c) 보안 마감(계좌 암호화·rate limit)** 세 곳에 집중된다.

### 강점 (근거)
- **멱등 3단 방어 실재**: `outbox_events.event_id` UNIQUE(V28) → `processed_events` PK(V29) → `settlements.payment_id` UNIQUE(V3).
- **트랜잭션 경계 보강**(commit 4258d1a): payout `noRollbackFor`(중복 송금 방지), tender별 `REQUIRES_NEW`(중간 PG 실패가 앞선 환불 롤백 방지), ledger self-call → `SingleLedgerEntryWriter` 빈 분리(프록시 우회 수정).
- **MSA 코드 경계 0**: settlement→order import 없음, `@Immutable` read-model 4종으로 조회, ArchUnit 검증(order-service).
- **회복탄력성 기초**: PG 게이트웨이별 `@CircuitBreaker`+`@Retry`(toss/kcp/nice/inicis), Kafka DLT 라우팅, 2초 Outbox 폴러.

### 핵심 갭 (근거)
- **환불→정산 미배선**: settlement에 `payment-refunded` `@KafkaListener` 없음, `PaymentRefunded` payload에 `refundAmount/refundId` 누락 → `AdjustSettlementForRefundService` 자동 트리거 불가 (Phase 5).
- **pg대사 보정 미연결**: `ResolveDiscrepancyService:47` TODO — 승인 시 정산 보정 흐름 미트리거.
- **복식부기 집계 불변식 부재**: `LedgerEntry`는 debit≠credit·amount>0만 강제, 정산별 ΣDr=ΣCr 검증은 앱계층뿐 (스키마/도메인 불변식 아님).
- **Outbox FAILED 무재시도**: 실패 이벤트가 FAILED로 멈춤, 지수백오프/자동복구 없음 → 수동 개입 필요.
- **계좌번호 평문 저장**: `SellerBankAccount` 주석은 "운영: KMS 암호화"지만 실제 암호화 코드 없음.
- **rate limit 미적용**: Bucket4j 존재하나 `/refund`·`/payouts` 등 고위험 엔드포인트에 미부착.
- **테스트 공백**: gateway 0건, settlement-service ArchUnit 없음, `PgReconciliation` 도메인 테스트 없음, 동시성 IT ~2건.

---

## Part 2. 평가 기준 (재사용 가능한 루브릭)

결제·정산 시스템을 평가할 때 쓸 10영역 기준. 각 영역 **A=프로덕션급 / B=동작하나 갭 / C=골격/미검증**.

| 영역 | A (프로덕션급) | B (동작·갭 존재) | C (골격/미검증) |
|------|----------------|------------------|------------------|
| **도메인 완성도** | 전 상태전이 구현+검증, deferred 없음 | 핵심 동작, 일부 Phase deferred | 스텁/TODO 다수 |
| **헥사고날·MSA** | 양방향 ArchUnit, 코드의존 0, 포트 격리 | 한쪽만 ArchUnit, 화이트리스트 수동 | 레이어 혼재 |
| **이벤트·정합성** | Outbox+멱등 3단+추적전파 | Outbox 있으나 재시도/추적 부분 | 직접발행, 멱등 없음 |
| **동시성** | 락+멱등+격리 + 동시성 IT로 증명 | 방어 코드 있으나 테스트 빈약 | 무방비 read-then-write |
| **데이터 무결성** | 스키마+도메인+집계 불변식 3중 | 엔트리 제약만, 집계는 앱계층 | 비정규화 수기 정합 |
| **테스트** | 커버리지≥70%, 동시성/멱등 IT | 단위는 충실, IT 빈약 | 핵심 경로 미검증 |
| **관측성** | 메트릭+알람+추적+구조화로그 | 메트릭·알람 있으나 추적 갭 | 로그만 |
| **회복탄력성** | 서킷+DLQ+자동재시도+타임아웃 | 서킷/DLQ 있으나 재시도/타임아웃 갭 | 무방비 |
| **보안** | 인증+RBAC+암호화+rate limit+감사 | 인증·RBAC·감사 OK, 암호화/제한 갭 | 평문/시크릿 노출 |
| **운영** | CI 게이트+무중단+마이그레이션 정연 | 대부분 충족, 일부 수동 | 수동 배포 |

> 본 프로젝트 적용 결과: A 3개 · A− 2개 · B+ 3개 · B 2개 · C+ 1개 → **가중 종합 B+/A−**.

---

## Part 3. 더 나은 시스템을 위한 체크리스트

우선순위: **P0(금융 정합성·보안 필수)** → **P1(완성도)** → **P2(고도화)**.

### P0 — 금융 정합성·보안 (출시 전 필수)
- [ ] **환불→정산 배선 완성**: settlement에 `payment-refunded` `@KafkaListener` 추가, `PaymentRefunded` payload에 `refundAmount`/`refundId` 포함, 멱등(`processed_events`) 적용.
- [ ] **복식부기 집계 불변식 강제**: 정산별 ΣDr=ΣCr 검증을 도메인/서비스 커밋 시점에 단언(불일치 시 롤백), 정기 reconciliation에 SUM=0 체크 추가.
- [ ] **계좌번호 컬럼 암호화**: `SellerBankAccount.bankAccountNumber` JPA AttributeConverter(AES)/KMS 적용 — 평문 저장 제거.
- [ ] **고위험 엔드포인트 rate limit**: `/payments/*/refund`, `/admin/payouts/**`에 Bucket4j 적용.
- [ ] **비관적 락 트랜잭션 타임아웃**: `RefundPaymentUseCase`에 `@Transactional(timeout=N)` — PG 지연 시 락 장기 점유 방지.
- [ ] **JWT 시크릿 운영 강제**: dev fallback(`dev-secret-...`) 제거 또는 부팅 시 prod 미설정 fail-fast.

### P1 — 완성도 (포트폴리오 신뢰도)
- [ ] **pg대사 보정 연결**: `ResolveDiscrepancyService` 승인 → 정산 보정 흐름 트리거(TODO 해소).
- [ ] **Outbox 재시도 전략**: FAILED 이벤트 지수백오프 재발행 + 최대횟수 후 DLQ/알람.
- [ ] **동시성 통합테스트 확충**: 환불·정산조정·outbox·재고 race를 Testcontainers + ExecutorService/Barrier로 증명(현재 ~2건 → 핵심 자금경로 전부).
- [ ] **settlement-service ArchUnit 추가**: 어댑터 다수(kafka/batch/search) 경계 회귀 방지.
- [ ] **테스트 커버리지 70% 도달**: PgReconciliation 도메인, split payment, gateway 라우팅 우선 보강.
- [ ] **chargeback Phase 3**: 사전-정산 분쟁 backfill + PG 웹훅 자동등록.
- [ ] **processed_events/outbox 보존정책**: 오래된 레코드 정리 배치 + 인덱스 점검.

### P2 — 고도화 (운영 성숙도)
- [ ] **분산추적 완성**: 핵심 비즈니스 메서드 `@Timed`, Kafka/DLT 헤더 traceparent 전파 검증, OTLP 활성.
- [ ] **알람 임계값 SLA 기반 튜닝**: outbox backlog·batch lag 임계를 실측 베이스라인으로.
- [ ] **감사로그 변조방지**: `audit_logs` 해시체인/서명 또는 불변 저장소 복제.
- [ ] **reconciliation 자동보정**: 불일치 탐지 시 알람을 넘어 보정 큐/플래그.
- [ ] **Phase B 독립 부팅**: settlement-service 단독 실행(현재 order-service:8088 번들) 전환.
- [ ] **부하/카오스 테스트**: PG 지연·브로커 장애·중복 이벤트 주입 시나리오.

---

## 부록 — 평가 방법
- 4개 병렬 코드조사(도메인 완성도 / 테스트 / 회복탄력성·관측성·보안 / 아키텍처·무결성) 결과 종합.
- 등급은 "결제·정산 금융 시스템" 기준 상대평가이며, 미연결/TODO/Phase 마커는 갭으로 계산.
- 한계: 정적 코드 근거 중심. 실 부하/장애 주입 결과는 미반영(P2 항목).
