# 기술 면접 Q&A 준비 문서

---

## 1. 프로젝트 기술 면접 (20문항)

### Q1. 모놀리스에서 MSA로 전환한 이유와 분리 기준은?

주문/결제와 정산은 Bounded Context가 명확히 다릅니다. 주문/결제는 실시간 사용자 요청 중심이고, 정산은 배치/비동기 처리 중심이라 배포 주기와 스케일링 요구가 다릅니다. 분리 기준은 DDD의 Bounded Context로 삼았고, order-service(주문/결제/상품/유저)와 settlement-service(정산/대사/송금/리포트)로 나눴습니다. 코드 의존성을 0으로 만들기 위해 Read-only Projection 패턴을 도입하여 settlement-service가 order-service의 테이블을 `@Immutable` 엔티티로 직접 매핑하되 코드 import는 하지 않는 방식을 택했습니다. `settings.gradle.kts`에서 `implementation(project(":order-service"))`가 없는 것으로 확인할 수 있습니다.

### Q2. Read-only Projection 패턴을 선택한 이유는? API 호출 방식과 비교하면?

MSA에서 서비스 간 데이터 조회는 보통 동기 API 호출, 이벤트 기반 데이터 복제, 공유 DB 세 가지 선택지가 있습니다. 동기 API는 settlement-service가 order-service에 런타임 의존성을 갖게 되어 장애 전파 위험이 있고, 이벤트 기반 복제는 Eventually Consistent하므로 정산 정합성에 리스크가 있습니다. Read-only Projection은 같은 PostgreSQL 인스턴스를 공유하되 settlement-service에 별도 `@Immutable` JPA 엔티티를 정의해서 payments/orders 테이블을 읽기 전용으로 매핑합니다. 코드 의존성 0, 런타임 API 호출 0, Strong Consistency를 모두 확보할 수 있습니다. 단, 진정한 MSA로 DB까지 분리하려면 이벤트 기반 복제로 전환해야 하는데, 현 단계에서는 정합성 우선으로 이 패턴을 선택했습니다.

### Q3. Transactional Outbox 패턴의 동작 원리와 도입 배경은?

결제 CAPTURED 시점에 Kafka로 이벤트를 발행해야 하는데, DB 커밋과 Kafka 발행을 같은 트랜잭션에 묶을 수 없습니다. 커밋 후 발행하면 발행 실패 시 이벤트 유실, 발행 후 커밋하면 커밋 실패 시 고스트 이벤트가 발생합니다. Outbox 패턴은 도메인 트랜잭션 안에서 `outbox_events` 테이블에 PENDING 상태로 INSERT하고, 별도 폴러(2초 주기)가 이를 읽어 Kafka로 발행 후 PUBLISHED로 전이합니다. DB 커밋 성공 = 이벤트 영속화 성공이므로 원자성이 보장됩니다. At-least-once 보장이므로 컨슈머 측 멱등 처리가 필수입니다.

### Q4. 3단 멱등 방어란 무엇이고, 왜 3단계나 필요한가요?

1단계는 `outbox_events.event_id UUID UNIQUE`로 프로듀서 측 중복 발행을 방지합니다. 2단계는 `processed_events` 테이블의 `(consumer_group, event_id)` PK로 컨슈머가 같은 이벤트를 두 번 처리하지 않게 합니다. 3단계는 `settlements.payment_id UNIQUE` 제약으로 하나의 결제에 대해 정산이 중복 생성되지 않게 합니다. At-least-once 메시징에서는 네트워크 재시도, Kafka 리밸런싱, 폴러 재시작 등 다양한 시점에서 중복이 발생할 수 있어서 단일 레이어 멱등으로는 부족합니다. 각 계층에서 독립적으로 방어해야 어떤 장애 시나리오에서도 정확히 한 번의 비즈니스 효과를 보장할 수 있습니다.

### Q5. 분할결제에서 역순 환불 정책을 채택한 이유는?

포인트+상품권+카드 같은 분할결제에서 환불 시 외부 PG(카드)부터 먼저 환불합니다. 만약 내부 잔액(포인트)을 먼저 환불했는데 PG 환불이 실패하면, 포인트는 복원됐지만 카드 거래는 살아있는 정합성 깨짐 상태가 됩니다. 역순으로 처리하면 PG 환불 실패 시 내부 잔액은 건드리지 않은 채 운영자 알람으로 수동 대응할 수 있고, PG 환불 성공 후 내부 잔액 복원 실패는 운영자가 잔액만 수동 복원하면 되므로 위험도가 훨씬 낮습니다. `PaymentDomain.planRefundFromTenders(amount)` 메서드가 sequence DESC 순서로 환불 계획을 생성합니다.

### Q6. SKU 재고 차감의 동시성 제어를 어떻게 했나요? (Optimistic / Pessimistic Lock과 비교)

선착순·핫딜처럼 같은 SKU에 차감이 폭주하는 상황을 가정해 **원자적 조건부 UPDATE**를 선택했습니다: `UPDATE product_variants SET stock = stock - :q WHERE id = :id AND stock >= :q AND status <> 'DISCONTINUED'` 단 한 번으로 "재고 검증 + 차감 + 매진 전이"를 DB row 락 안에서 처리합니다. 영향 행 1이면 성공, 0이면 재고 부족(`InsufficientStockException`)·단종(`IllegalStateException`)으로 분류합니다. Optimistic Lock(`@Version`)+재시도는 충돌이 잦은 hot SKU에서 재시도 폭증·한계 초과 실패가 생기고, Pessimistic Lock(`SELECT FOR UPDATE`)은 충돌이 없는 SKU에서도 항상 락 대기가 발생합니다. 조건부 UPDATE는 충돌 여부와 무관하게 단일 쿼리라 재시도 루프가 필요 없고 초과판매도 원천 차단됩니다. `VariantStockConcurrencyIT`에서 100스레드/재고 50개로 정확히 50건 성공, 50건 `InsufficientStock`, 최종 재고 0, 음수 0건을 검증합니다. (엔티티에는 `@Version` 컬럼(V36)이 남아 있으나 핫 차감 경로는 이 조건부 UPDATE를 사용합니다.) 메트릭은 `variant.stock.decrease.success` / `variant.stock.decrease.rejected`.

### Q7. 부분 환불에서 Pessimistic Lock + Idempotency Key를 사용하는 이유는?

부분 환불은 동일 결제에 대해 여러 번 호출될 수 있고, 환불 금액 누적이 원 결제 금액을 초과하면 안 됩니다. `RefundPaymentUseCase`는 `REPEATABLE_READ` 격리 수준으로 트랜잭션을 열어 결제 레코드를 읽고, `refundableAmount`를 검증한 뒤 PG 환불을 호출합니다. Idempotency Key는 전액 환불 시 `payment-{id}-full`로 자동 생성되고, 부분 환불 시에는 호출자가 반드시 지정해야 합니다(없으면 `MissingIdempotencyKeyException`). `loadRefundPort.findByPaymentIdAndIdempotencyKey`로 이미 COMPLETED된 동일 키의 Refund가 있으면 PG 재호출 없이 현재 상태를 반환합니다.

### Q8. 다중 PG 라우팅 전략은 어떻게 구현했나요?

`PgRouter`가 결제수단, 거래금액, PG 건강 상태를 기반으로 PG를 선택합니다. 고액 거래(100만원 이상)는 NICE 우선, 결제수단별 1순위(카드→TOSS, 카카오페이→NICE, 계좌이체→KCP)가 있고, 1순위 PG가 unhealthy면 fallback chain(TOSS→NICE→KCP→INICIS) 순회합니다. 거래 ID에 PG prefix(`TOSS:xxx`)를 붙여서 환불 시 동일 PG로 자동 라우팅합니다. PG별 독립 Resilience4j CircuitBreaker(50% 실패율/30초 OPEN)를 적용해 한 PG 장애가 다른 PG로 전파되지 않는 Bulkhead 격벽을 구현했습니다.

### Q9. Outbox 비동기 경계에서 분산 트레이싱이 끊기는 문제를 어떻게 해결했나요?

일반적인 Outbox 구현은 DB 커밋과 폴러 사이, Kafka send와 receive 사이 두 곳에서 trace context가 끊깁니다. 도메인 트랜잭션 시점의 W3C Trace Context(`00-{traceId}-{spanId}-{flags}`)를 `outbox_events.trace_parent` 컬럼에 영속화하고, 폴러가 Kafka 발행 시 `ProducerRecord.headers()`에 `traceparent`로 복원합니다. 컨슈머 측 spring-kafka가 이 헤더를 읽어 같은 traceId로 새 span을 시작합니다. 비활성 환경에서는 `TraceContextCapture`가 null을 반환하여 기존 동작과 호환됩니다. Tempo에서 결제→정산까지 단일 trace로 추적 가능합니다.

### Q10. 셀러 등급별 T+N 정산 주기와 Holdback은 어떻게 설계했나요?

`SellerTier`(NORMAL/VIP/STRATEGIC)별로 기본 정산 주기와 보류 정책이 다릅니다. NORMAL은 T+7/보류 30%/30일, VIP는 T+3/보류 10%/14일, STRATEGIC는 T+1/보류 0%입니다. `BusinessDayCalculator`가 한국 고정 공휴일 8개와 주말을 건너뛰어 영업일 기준으로 정산일을 계산합니다. 보류금은 정산 확정 시 `Settlement.applyHoldback(rate, releaseDate)`로 적용되고, 매일 03:00 KST 배치가 `releaseDate` 도달한 보류건을 자동 해제합니다. 환불 발생 시 `consumeHoldbackForRefund()`로 보류금에서 우선 차감하여 셀러 실수령액에 영향을 최소화합니다.

### Q11. PG 대사(Reconciliation)는 어떤 불일치를 감지하나요?

PG사에서 받은 정산 파일과 내부 결제 데이터를 대조하여 5가지 유형의 불일치를 분류합니다: PG에만 있는 거래(내부 누락), 내부에만 있는 거래(PG 누락), 금액 불일치, 상태 불일치, 기타입니다. `PgReconciliationMatcher`가 PG 파일의 `PgTransactionRow`와 내부 `InternalPaymentRow`를 매칭하고, 불일치는 `ReconciliationDiscrepancy` 레코드로 기록됩니다. `ReconciliationRun`이 전체 대사 실행의 생명주기(시작→완료/실패)를 관리합니다. 일일 대사 배치(03:05)가 전일자를 자동 검증하고, 불일치 발견 시 Alertmanager로 알림을 발송합니다.

### Q12. 헥사고날 아키텍처의 패키지 의존 방향을 어떻게 강제하나요?

ArchUnit 테스트(`HexagonalArchitectureTest`)로 CI에서 강제합니다. 규칙은 네 가지입니다: (1) domain은 application/adapter를 import하지 않는다, (2) application은 domain과 자기 포트만 참조한다, (3) adapter는 자기 도메인의 포트만 구현한다, (4) 교차 도메인 조회가 필요하면 자기 도메인에 신규 아웃바운드 포트를 두고 어댑터가 다른 도메인 테이블을 읽는다. 이 규칙 덕분에 도메인 모델이 순수 POJO로 유지되어 Spring 없이 단위 테스트가 가능하고, 어댑터 교체 시(예: `OutboxBackedEventPublisher` → `KafkaOutboxPublisher`) 도메인 코드 수정이 필요 없습니다.

### Q13. 정산 상태 머신(State Machine)은 어떤 전이를 허용하나요?

`REQUESTED → PROCESSING → DONE`이 정상 흐름이고, `PROCESSING → FAILED → REQUESTED`로 재시도가 가능합니다. 각 전이 메서드(`startProcessing`, `complete`, `fail`, `retry`)가 현재 상태를 검증하고, 잘못된 전이 시 `IllegalStateException`을 던집니다. DONE 상태의 정산은 immutable로, 금액 변경이 필요하면 `SettlementAdjustment` 별도 레코드로 기록하여 원장 정합성을 유지합니다. `confirm()` 메서드는 레거시 호환용으로 REQUESTED→PROCESSING→DONE을 한 번에 수행하지만, 신규 코드에서는 각 단계를 명시적으로 호출하도록 권장합니다.

### Q14. ASAT 프로젝트에서 Web Audio API의 +-5ms 타이밍 정확도를 어떻게 달성했나요?

Web Audio API의 `AudioContext.currentTime`은 하드웨어 클럭 기반으로 JavaScript의 `setTimeout`/`setInterval`보다 훨씬 정밀합니다. 음원 재생 시점을 `AudioBufferSourceNode.start(when)`으로 스케줄링하여 OS 스레드 스케줄링과 무관하게 정확한 타이밍을 보장합니다. 응답 시간 측정은 `AudioContext.currentTime` 기준으로 음원 시작 시점과 사용자 입력 시점의 차이를 계산합니다. 브라우저별 오디오 레이턴시 보정값을 적용하고, 측정 신뢰도가 낮은 시행은 데이터 신뢰도 등급(A/B/C/F)으로 분류하여 분석에서 제외합니다.

### Q15. ASAT의 적응적 계단법(2-down 1-up) 알고리즘은 어떤 문제를 해결하나요?

청각 재활 훈련에서 고정 난이도는 너무 쉽거나 너무 어려워 훈련 효과가 떨어집니다. 2-down 1-up 계단법은 2회 연속 정답이면 난이도를 올리고, 1회 오답이면 난이도를 내려 피검자의 70.7% 정답률 수준에 수렴합니다. 이 수렴점이 청각 역치(threshold)를 나타냅니다. Trial 동시성은 Optimistic Lock으로 처리하여 같은 세션에서 중복 응답이 기록되지 않도록 합니다. 각 세션의 데이터 신뢰도를 reversal 횟수, 응답 시간 변동성, 수렴 안정성으로 평가하여 A~F 등급으로 분류합니다.

### Q16. goods-online 프로젝트의 래플(Raffle) 해시 체인은 어떤 목적인가요?

래플(추첨) 결과의 무결성과 사전 조작 불가능성을 보장합니다. 추첨 전에 시드값의 해시를 공개하고, 추첨 후 시드값을 공개하면 누구나 해시를 검증할 수 있어 운영자가 결과를 사후 조작할 수 없습니다. 해시 체인은 각 추첨 라운드의 결과를 이전 라운드의 해시와 연결하여 중간 라운드 조작도 탐지 가능하게 합니다. 블록체인의 원리를 경량화하여 적용한 것으로, 별도 인프라 없이 SHA-256 해시만으로 투명성을 확보합니다.

### Q17. global-seat-ticketing의 Redis 분산 락은 어떤 시나리오에서 필요한가요?

만석 콘서트 좌석 예매에서 동일 좌석에 대한 동시 예매를 방지해야 합니다. DB Pessimistic Lock은 단일 DB 인스턴스에서는 동작하지만 다중 인스턴스 환경에서는 부족합니다. Redis 분산 락(Redisson 기반)으로 좌석별 락 키(`seat:{eventId}:{seatNo}`)를 사용하여 클러스터 전체에서 단 하나의 요청만 예매를 진행할 수 있게 합니다. 락 TTL을 설정하여 프로세스 크래시 시에도 락이 자동 해제되고, 대기 중인 요청은 타임아웃 후 실패 응답을 받습니다. SKU Optimistic Lock과 달리 좌석 예매는 재시도의 의미가 없으므로(이미 다른 사람이 예매) 분산 락이 적합합니다.

### Q18. SNS 프로젝트에서 Kafka+SSE 조합을 선택한 이유는?

SNS 피드에서 실시간 알림을 구현할 때, 폴링은 불필요한 요청이 많고, WebSocket은 양방향이 불필요한데 서버 리소스를 많이 소비합니다. SSE(Server-Sent Events)는 단방향 스트림으로 알림 전달에 적합하고, HTTP 기반이라 로드밸런서/프록시 호환성이 좋습니다. Kafka는 알림 이벤트의 내구성(durability)과 다중 컨슈머(알림/이메일/푸시) 지원을 보장합니다. 각 사용자의 SSE 연결은 서버 인스턴스에 로컬이므로, Kafka 컨슈머가 이벤트를 받으면 해당 인스턴스에 연결된 사용자에게만 SSE로 push합니다.

### Q19. Spring Boot 4.0과 Java 25를 실무 프로젝트에서 사용한 이유는?

최신 기술 스택에 대한 적응력을 보여주기 위해 선택했습니다. Java 25의 Virtual Thread는 Kafka 컨슈머/Outbox 폴러 같은 I/O 바운드 작업에서 플랫폼 스레드 대비 처리량을 크게 향상시킵니다. Sealed class/record는 도메인 모델(예: `HoldbackPolicy` record, `SellerTier` enum)을 더 간결하게 표현합니다. Spring Boot 4.0의 Spring Cloud Gateway 2025는 기존 Zuul/SCG MVC 대비 Reactive 기반 라우팅 성능이 개선되었습니다. 마이그레이션 과정에서 발생한 호환성 이슈들을 ADR 0009에 기록했습니다.

### Q20. 이 프로젝트에서 가장 어려웠던 기술적 결정은?

MSA 분리 시 settlement-service와 order-service 간 코드 의존성을 끊는 것이었습니다. 처음에는 모놀리스에서 settlement 코드가 order/payment 엔티티를 직접 import하고 있었는데, 단순히 API 호출로 바꾸면 동기 의존성이 생기고, 이벤트 기반 복제는 정산 정합성에 리스크가 있었습니다. Read-only Projection 패턴으로 같은 테이블을 `@Immutable` 엔티티로 별도 매핑하는 방식을 선택했는데, 이것이 진정한 MSA인가에 대한 고민이 있었습니다. 결론적으로 코드 의존성 0 + Strong Consistency를 우선하되, DB 분리가 필요한 시점에 이벤트 기반으로 전환할 수 있도록 이미 Outbox+Kafka 파이프라인을 갖추어 놓은 것이 핵심 전략입니다.

---

## 2. Java/Spring 심화 (15문항)

### Q21. JPA N+1 문제란 무엇이고, 어떻게 해결하나요?

N+1 문제는 연관 엔티티를 LAZY 로딩으로 조회할 때, 부모 1건 조회 후 자식 N건을 개별 쿼리로 가져오는 현상입니다. Settlement 프로젝트에서 주문 목록 조회 시 각 주문의 결제 정보를 가져올 때 발생할 수 있습니다. 해결 방법은 `@EntityGraph`나 `JOIN FETCH`로 한 번에 가져오기, `@BatchSize`로 IN 절 묶기, DTO Projection으로 필요한 컬럼만 조회하기가 있습니다. settlement-service의 Read-only Projection은 필요한 컬럼만 매핑한 `@Immutable` 엔티티를 사용하므로 N+1 위험이 근본적으로 줄어듭니다.

### Q22. Spring의 트랜잭션 전파(Propagation) 유형 중 REQUIRES_NEW는 언제 사용하나요?

REQUIRES_NEW는 기존 트랜잭션과 독립적인 새 트랜잭션을 시작합니다. SKU 재고 차감의 `decreaseInNewTransaction()`이 이를 사용하는데, 결제 트랜잭션 안에서 호출되더라도 재고 차감을 **독립 커밋**해 분리하기 위해서입니다(차감 자체는 원자적 조건부 UPDATE로 처리). 주의할 점은 REQUIRES_NEW 트랜잭션이 커밋되면 외부 트랜잭션이 롤백되어도 그 결과는 유지된다는 것입니다. 따라서 독립적으로 확정돼야 하는 작업(보상 트랜잭션, 감사 로깅, 독립 커밋이 필요한 차감)에 적합하며, 외부와 운명을 같이해야 하는 작업에는 적합하지 않습니다.

### Q23. Spring Security 필터 체인의 동작 순서를 설명해주세요.

요청이 들어오면 `SecurityFilterChain`의 필터들이 순서대로 실행됩니다. `CorsFilter` → `CsrfFilter` → `UsernamePasswordAuthenticationFilter`(또는 커스텀 JWT 필터) → `ExceptionTranslationFilter` → `FilterSecurityInterceptor`(Authorization) 순입니다. Settlement 프로젝트에서는 Gateway 서비스에서 JWT 인증 필터가 토큰을 검증하고, 인증 정보를 SecurityContext에 저장합니다. 각 서비스는 `shared-common`의 JWT 설정(`common.config.jwt`)을 공유하며, HS256 알고리즘으로 토큰을 검증합니다. Actuator 엔드포인트는 별도 SecurityFilterChain으로 인증 필수 설정되어 있습니다.

### Q24. Bean 생명주기(Lifecycle)를 설명해주세요.

Spring 컨테이너가 빈을 생성하면: (1) 인스턴스화 → (2) 의존성 주입(`@Autowired`, 생성자) → (3) `@PostConstruct`/`InitializingBean.afterPropertiesSet()` → (4) 사용 → (5) `@PreDestroy`/`DisposableBean.destroy()` 순입니다. Settlement 프로젝트에서 `OutboxPublisherScheduler`는 `@PostConstruct`에서 초기 상태를 확인하고, `@PreDestroy`에서 진행 중인 폴링을 안전하게 종료합니다. `@Scope("prototype")`은 요청마다 새 인스턴스를 생성하고 컨테이너가 소멸을 관리하지 않으므로, 기본 singleton과 혼용할 때 주의해야 합니다.

### Q25. AOP(Aspect-Oriented Programming)의 실제 활용 사례를 설명해주세요.

AOP는 횡단 관심사(로깅, 트랜잭션, 보안)를 비즈니스 로직에서 분리합니다. Settlement 프로젝트에서는 `shared-common`의 감사(Audit) 모듈이 AOP로 PII 마스킹 + 감사 로그를 처리합니다. `@Transactional`도 AOP 기반으로, 프록시가 메서드 호출을 가로채 트랜잭션을 시작/커밋/롤백합니다. 주의할 점은 같은 클래스 내부 메서드 호출 시 프록시를 거치지 않아 AOP가 동작하지 않는 것입니다. 이를 self-invocation 문제라 하며, 별도 빈으로 분리하거나 `AspectJ` 위빙으로 해결합니다. SKU 재고 차감의 `decreaseInNewTransaction()`(`REQUIRES_NEW`)도 같은 빈 내부에서 호출하면 프록시를 거치지 않아 새 트랜잭션이 열리지 않으므로, 반드시 다른 빈에서 호출해야 합니다.

### Q26. `@Transactional`의 isolation level을 REPEATABLE_READ로 설정하면 어떤 효과가 있나요?

REPEATABLE_READ는 트랜잭션 시작 시점의 스냅샷을 유지하여 같은 쿼리를 반복 실행해도 동일한 결과를 보장합니다(Phantom Read는 DB에 따라 다름). `RefundPaymentUseCase`에서 REPEATABLE_READ를 사용하는 이유는 결제 조회 → 환불 가능 금액 계산 → PG 호출 → 상태 업데이트 과정에서 다른 트랜잭션이 같은 결제를 수정하는 것을 방지하기 위해서입니다. PostgreSQL은 REPEATABLE_READ에서 실제로 Serializable Snapshot Isolation에 가까운 동작을 하며, 충돌 시 `SerializationFailure`를 던져 애플리케이션이 재시도할 수 있게 합니다.

### Q27. JPA의 Dirty Checking과 Merge의 차이는?

Dirty Checking은 영속성 컨텍스트가 관리하는 엔티티의 변경을 트랜잭션 커밋 시 자동으로 감지하여 UPDATE 쿼리를 생성합니다. `merge()`는 준영속(detached) 엔티티를 다시 영속 상태로 만들 때 사용하며, 모든 컬럼을 UPDATE합니다. Settlement 도메인에서 `startProcessing()`, `complete()` 같은 상태 전이 메서드는 영속 엔티티의 필드를 변경하므로 Dirty Checking으로 자동 반영됩니다. `@DynamicUpdate`를 사용하면 변경된 컬럼만 UPDATE하여 불필요한 갱신을 줄일 수 있습니다. `merge()`는 SELECT 후 UPDATE가 발생하므로 Dirty Checking보다 비효율적일 수 있습니다.

### Q28. Spring Batch의 Chunk 기반 처리와 Tasklet의 차이는?

Chunk 기반은 Reader → Processor → Writer 파이프라인으로, chunk-size만큼 읽어서 한 번에 쓰기 처리합니다. Tasklet은 단순한 단일 작업(파일 삭제, 알림 발송 등)에 적합합니다. Settlement 프로젝트의 정산 배치는 Chunk 기반으로, 전일자 결제 데이터를 chunk 단위로 읽어 정산을 생성하고 DB에 쓰니다. Holdback 자동 해제 배치도 Chunk 기반으로, `releaseDate` 도달한 정산 건을 조회하여 `releaseHoldback()`을 호출합니다. Chunk 실패 시 해당 chunk만 롤백되므로 전체 배치가 실패하지 않고, skip/retry 정책으로 일시적 오류를 흡수합니다.

### Q29. Caffeine 캐시와 Redis 캐시의 선택 기준은?

Caffeine은 JVM 로컬 캐시로 네트워크 지연이 없고 매우 빠르지만, 인스턴스 간 공유가 불가합니다. Redis는 분산 캐시로 인스턴스 간 공유 가능하지만 네트워크 왕복이 필요합니다. Settlement 프로젝트에서 Caffeine을 선택한 이유는, 정산 조회/리포트 데이터가 인스턴스별로 독립적으로 캐싱되어도 정합성 문제가 없고, 별도 Redis 인프라 없이도 충분한 성능을 얻을 수 있기 때문입니다. ASAT 프로젝트에서는 세션 관리와 분산 환경 지원을 위해 Redis를 사용합니다. 캐시 무효화 전략(TTL, 이벤트 기반)도 선택의 핵심 요소입니다.

### Q30. Resilience4j의 CircuitBreaker 상태 전이를 설명해주세요.

CLOSED(정상) → OPEN(차단) → HALF_OPEN(탐색) 세 상태입니다. CLOSED에서 실패율이 임계값(Settlement에서는 50%)을 넘으면 OPEN으로 전이되어 요청을 즉시 실패시킵니다. OPEN에서 대기 시간(30초)이 지나면 HALF_OPEN으로 전이되어 일부 요청을 통과시킵니다. 성공하면 CLOSED로, 실패하면 다시 OPEN으로 돌아갑니다. Settlement의 PG별 독립 CircuitBreaker(`tossPg`, `kcpPg`, `nicePg`, `inicisPg`)는 한 PG의 장애가 다른 PG 호출에 영향을 주지 않는 Bulkhead 효과를 제공합니다.

### Q31. `@Version`을 사용한 Optimistic Lock에서 OptimisticLockException이 발생하면 어떻게 되나요?

JPA가 UPDATE 쿼리에 `WHERE version = :currentVersion`을 추가하고, 영향받은 행이 0이면 `OptimisticLockException`을 던집니다. 이는 다른 트랜잭션이 먼저 해당 행을 수정해 version이 증가했다는 의미입니다. 일반적 대응은 예외를 잡아 새 트랜잭션에서 최신 데이터를 다시 읽어 재시도하는 것입니다. 이 프로젝트에서 `@Version`은 `Settlement`·`Payout`·`ProductVariant` 등에 lost update 방지용 안전망으로 둡니다. 다만 **고경합 SKU 재고 차감은 의도적으로 낙관 락 재시도 대신 원자적 조건부 UPDATE를 사용**합니다 — hot SKU에서 낙관 락은 재시도 폭증·한계 초과 실패가 생기는 반면, 단일 조건부 UPDATE는 재시도 없이 정확성과 처리량을 동시에 확보하기 때문입니다. 실패는 `InsufficientStockException`으로 분류하고 `variant.stock.decrease.rejected` 메트릭을 남깁니다.

### Q32. Flyway와 Liquibase의 차이, 그리고 마이그레이션 관리 전략은?

Flyway는 SQL 기반으로 단순하고, Liquibase는 XML/YAML/JSON으로 DB 독립적 마이그레이션을 지원합니다. Settlement에서 Flyway를 선택한 이유는 PostgreSQL 전용이므로 DB 독립성이 불필요하고, 순수 SQL로 작성하면 DBA 리뷰가 용이하기 때문입니다. 현재 순차 V1~V50 + `V{timestamp}__` 형식 6개로 총 56개 마이그레이션이 있으며, 테이블 생성, 인덱스 추가, 컬럼 변경이 모두 버전 관리됩니다. 주의할 점은 이미 적용된 마이그레이션은 수정하면 안 되고(체크섬 검증 실패), 롤백은 별도 마이그레이션으로 작성해야 합니다. CI에서 Testcontainers로 마이그레이션을 매번 처음부터 실행하여 무결성을 검증합니다.

### Q33. Virtual Thread(Project Loom)의 장점과 주의점은?

Virtual Thread는 OS 스레드가 아닌 JVM이 관리하는 경량 스레드로, I/O 블로킹 시 캐리어 스레드를 양보하여 적은 OS 스레드로 많은 동시 요청을 처리합니다. Settlement의 Outbox 폴러, Kafka 컨슈머 같은 I/O 바운드 작업에서 효과적입니다. 주의점은 `synchronized` 블록에서 캐리어 스레드를 pin하므로 `ReentrantLock`을 사용해야 하고, ThreadLocal 사용 시 수백만 개 Virtual Thread가 각각 ThreadLocal을 가지면 메모리 문제가 발생할 수 있습니다. 또한 CPU 바운드 작업에서는 이점이 없으므로, 정산 금액 계산 같은 순수 연산에는 기존 스레드풀이 적합합니다.

### Q34. Spring의 `@Scheduled`와 Spring Batch의 차이는?

`@Scheduled`는 단순 주기적 작업에 적합하고, 실패 시 재시도/보상이 없으며, 클러스터 환경에서 중복 실행 방지를 별도로 구현해야 합니다. Spring Batch는 대용량 데이터 처리에 특화되어 chunk 기반 처리, skip/retry, 재시작, 실행 이력 관리를 기본 제공합니다. Settlement에서 Outbox 폴러는 `@Scheduled`(2초 주기)로 가볍게 실행하고, 정산 생성/Holdback 해제 같은 대량 처리는 Spring Batch로 구현합니다. Batch Job의 `JobExecution` 이력으로 실패 지점부터 재시작이 가능하여 운영 안정성이 높습니다.

### Q35. DTO와 도메인 모델을 분리하는 이유는?

도메인 모델은 비즈니스 규칙과 불변식을 캡슐화하고, DTO는 외부와의 데이터 전송에만 사용합니다. Settlement 도메인의 `Settlement` 클래스는 상태 전이 규칙, 수수료 계산, 환불 검증 로직을 가지지만, API 응답에는 필요한 필드만 담은 DTO를 반환합니다. 분리하지 않으면 API 스펙 변경이 도메인 로직에 영향을 주거나, 도메인 내부 필드(version, failureReason)가 외부에 노출됩니다. 헥사고날 아키텍처에서 adapter/in/web이 DTO↔도메인 변환을 담당하고, domain 패키지는 어떤 직렬화 어노테이션(`@JsonProperty` 등)도 갖지 않습니다.

---

## 3. 시스템 설계 (10문항)

### Q36. "이커머스 정산 시스템을 설계하라"는 질문에 어떻게 답하나요?

핵심 요구사항을 먼저 확인합니다: 일 거래량, 정산 주기, 다중 PG 여부, 환불/분쟁 처리. 설계는 크게 4계층입니다. (1) 이벤트 수집: 결제 CAPTURED 이벤트를 Outbox+Kafka로 at-least-once 전달, 컨슈머 멱등 처리. (2) 정산 생성: 셀러 등급별 T+N 영업일 정산일 계산, 수수료 차등 적용, Holdback 보류. (3) 대사/검증: PG 파일과 내부 데이터 대조, 3대 불변식(결제-환불=정산net+수수료, 역정산=환불, Outbox발행수=정산생성수) 배치 검증. (4) 송금: 펌뱅킹 연동, 멱등 키로 이중 송금 방지, 일/셀러별 한도 검증. 실제 Settlement 프로젝트에서 이 4계층을 모두 구현했습니다.

### Q37. "만석 콘서트 좌석 예매 시스템을 설계하라"

핵심 병목은 동일 좌석에 대한 동시 예매입니다. (1) 좌석 선택: Redis 분산 락(`seat:{eventId}:{seatNo}`)으로 동시 접근 직렬화, 락 TTL로 크래시 안전성 확보. (2) 결제: 락 획득 후 결제 진행, 결제 실패 시 락 해제하여 다른 사용자에게 기회 제공. (3) 대기열: 트래픽 폭주 시 Redis Sorted Set 기반 대기열로 유입량 제어. (4) 좌석 상태: DB는 최종 정합성 보장, Redis는 실시간 좌석 현황 표시. global-seat-ticketing 프로젝트에서 Redisson 기반 분산 락으로 구현했으며, SKU Optimistic Lock과 달리 좌석은 재시도 의미가 없으므로 즉시 실패(fail-fast) 전략이 적합합니다.

### Q38. "이벤트 드리븐 주문 파이프라인을 설계하라"

주문 생성 → 결제 승인 → 재고 차감 → 배송 준비 → 정산 생성이 이벤트로 연결됩니다. 각 단계는 Transactional Outbox로 이벤트를 발행하여 DB 커밋과 이벤트 발행의 원자성을 보장합니다. 실패 처리는 두 가지 전략: 보상 트랜잭션(Saga)과 재시도입니다. 결제 실패는 주문 취소(보상), 재고 차감 실패는 재시도(Optimistic Lock), 정산 실패는 FAILED 상태 후 수동/자동 재시도. 멱등성은 각 컨슈머의 `processed_events` 테이블 + 도메인 UNIQUE 제약으로 보장합니다. Settlement 프로젝트의 결제→정산 파이프라인이 이 패턴의 실제 구현입니다.

### Q39. "실시간 알림 시스템을 설계하라"

(1) 이벤트 소스: 각 서비스가 알림 이벤트를 Kafka 토픽에 발행. (2) 라우팅: 알림 서비스가 이벤트를 소비하여 사용자별 채널(인앱/이메일/푸시)로 분배. (3) 인앱 전달: SSE(Server-Sent Events)로 서버→클라이언트 단방향 스트림, 연결 끊김 시 마지막 이벤트 ID부터 재전송. (4) 스케일아웃: 사용자 SSE 연결은 특정 인스턴스에 로컬이므로, Kafka 파티셔닝(user_id 키)으로 같은 사용자의 이벤트가 같은 인스턴스로 라우팅. SNS 프로젝트에서 Kafka+SSE 조합으로 구현했으며, WebSocket 대비 서버 리소스 절약과 HTTP 인프라 호환성이 장점입니다.

### Q40. "결제 PG가 30분 다운되면 어떻게 대응하나요?"

(1) 감지: PG별 CircuitBreaker가 실패율 50% 초과 시 OPEN으로 전환, Prometheus 메트릭 + Alertmanager 알림. (2) 자동 대응: PgRouter의 fallback chain이 다른 건강한 PG로 자동 라우팅. (3) 복구: CircuitBreaker가 30초 후 HALF_OPEN으로 전환, 일부 요청을 장애 PG로 보내 복구 확인. (4) 보류 건 처리: OPEN 동안 실패한 결제는 사용자에게 재시도 안내 또는 다른 결제수단 제안. Settlement 프로젝트에서 4개 PG(TOSS/KCP/NICE/INICIS) 독립 CircuitBreaker + Bulkhead로 장애 격리를 구현했습니다.

### Q41. "대용량 데이터 마이그레이션 전략을 설명하라"

(1) 이중 쓰기(Dual Write): 새 스키마와 구 스키마에 동시 쓰기, 읽기는 구 스키마. (2) 백필(Backfill): 배치로 구 데이터를 새 스키마로 복사, chunk 단위로 진행하여 DB 부하 분산. (3) 전환: 읽기를 새 스키마로 전환, 검증 후 구 스키마 쓰기 중단. (4) 정리: 구 스키마 삭제. Settlement의 56개 Flyway 마이그레이션 중 SellerTier 도입(V32)이 이 패턴을 따랐습니다. `commissionRate` 컬럼 추가 시 기존 데이터는 기본 3%를 유지하고, 신규 생성분부터 등급별 rate를 적용하는 점진적 전환을 했습니다.

### Q42. "분산 트랜잭션을 어떻게 처리하나요?"

2PC(Two-Phase Commit)는 성능/가용성 비용이 크므로, 실무에서는 Saga 패턴이나 Outbox 패턴을 사용합니다. Saga는 각 서비스가 로컬 트랜잭션을 수행하고, 실패 시 보상 트랜잭션을 실행합니다. Outbox 패턴은 도메인 트랜잭션과 이벤트 발행을 같은 DB 트랜잭션에 묶어 원자성을 보장합니다. Settlement에서는 Outbox+Kafka+3단 멱등으로 결제→정산 간 분산 트랜잭션을 처리합니다. 핵심은 "최종적 일관성(Eventual Consistency)"을 수용하되, 대사(Reconciliation)로 불일치를 탐지하고 자동/수동 보정하는 안전망을 갖추는 것입니다.

### Q43. "검색 시스템을 어떻게 설계하나요?"

RDBMS 전문 검색은 LIKE 쿼리로 인덱스를 타지 못해 느립니다. Elasticsearch를 도입하여 역인덱스(inverted index) 기반 전문 검색을 수행합니다. 데이터 동기화는 Change Data Capture(CDC)나 이벤트 기반으로 합니다. Settlement에서는 정산 데이터를 ES에 색인하여 기간/셀러/상태별 빠른 검색을 지원합니다. 주의점은 ES와 DB 간 데이터 지연(lag)을 감안해야 하고, ES 장애 시 DB 폴백 쿼리를 준비해야 합니다. 인덱스 설계 시 한국어 형태소 분석기(nori)를 적용하고, 집계(aggregation)는 ES의 강점을 활용합니다.

### Q44. "Rate Limiting을 어떻게 구현하나요?"

Token Bucket 알고리즘이 가장 일반적입니다. 일정 속도로 토큰이 충전되고, 요청마다 토큰을 소비하며, 토큰이 없으면 429 Too Many Requests를 반환합니다. Settlement에서는 Bucket4j(`shared-common.common.ratelimit`)로 API별 rate limit을 적용합니다. 분산 환경에서는 Redis 기반 Token Bucket(또는 Sliding Window)으로 인스턴스 간 공유해야 합니다. Gateway에서 글로벌 rate limit, 각 서비스에서 API별 세밀한 rate limit을 이중으로 적용하면 DDoS와 개별 API 남용을 모두 방어할 수 있습니다.

### Q45. "모니터링/관측성(Observability) 스택을 설계하라"

3대 축은 메트릭(Prometheus), 로그(Loki/ELK), 트레이스(Tempo/Jaeger)입니다. Settlement에서는 Micrometer로 30+ 커스텀 메트릭을 수집하고, Prometheus가 스크랩하여 Grafana 대시보드로 시각화합니다. 분산 트레이싱은 OTLP로 Tempo에 전송하며, Outbox 경계에서 traceparent를 영속화하여 결제→정산 단일 trace를 유지합니다. 알림은 Alertmanager로 PG 장애, 대사 불일치, Outbox 적체 등 핵심 지표에 대한 알림을 설정합니다. 핵심은 "무엇이 고장났는지"(메트릭) → "어디서 고장났는지"(트레이스) → "왜 고장났는지"(로그) 순으로 드릴다운하는 흐름입니다.

---

## 4. 행동 면접 (5문항)

### Q46. 프로덕션 장애 상황을 경험한 적이 있나요? 어떻게 대응했나요?

개발 환경에서 Outbox 폴러의 폴링 주기(2초)와 Kafka 컨슈머의 처리 속도 불균형으로 PENDING 이벤트가 적체되는 상황을 경험했습니다. 원인은 settlement-service의 정산 생성 로직에서 Read-only Projection 조회 시 N+1 쿼리가 발생하여 컨슈머 처리가 느려진 것이었습니다. `OutboxPendingBacklog` 메트릭이 알림 임계값을 넘어 감지했고, 즉시 컨슈머 측 쿼리를 JOIN FETCH로 최적화하여 해결했습니다. 이후 대사(Reconciliation)의 3번 불변식(Outbox 발행 수 == 정산 생성 수)으로 이벤트 누락을 자동 감지하는 안전망을 강화했습니다.

### Q47. 기술적 의견 충돌이 있었던 경험은?

MSA 분리 시 서비스 간 데이터 조회 방식에 대해 동기 API 호출 vs Read-only Projection 패턴으로 의견이 나뉘었습니다. API 호출 방식은 진정한 MSA 분리이지만 런타임 의존성과 장애 전파 위험이 있고, Read-only Projection은 DB 공유라는 제약이 있지만 정합성과 성능이 우수합니다. 트레이드오프를 정리하여 현 단계의 우선순위(정산 정합성 > 완전한 MSA 분리)를 기준으로 Read-only Projection을 선택했습니다. 동시에 이벤트 기반 전환 경로(Outbox+Kafka)를 미리 구축하여 DB 분리가 필요한 시점에 전환할 수 있도록 했습니다. ADR 문서로 결정 근거를 기록했습니다.

### Q48. 가장 도전적이었던 기술적 문제는?

Outbox 비동기 경계에서 분산 트레이싱이 끊기는 문제였습니다. DB 커밋과 폴러 사이, Kafka send와 receive 사이 두 곳에서 trace context가 사라져 Tempo에서 결제→정산이 별개 trace로 보였습니다. W3C Trace Context를 `outbox_events.trace_parent` 컬럼에 영속화하고, 폴러가 Kafka 헤더로 복원하는 방식을 설계했습니다. 어려웠던 점은 Tracer가 없는 환경(로컬/CI)에서도 기존 동작과 호환되어야 한다는 것이었고, `TraceContextCapture`가 null을 반환하면 traceparent 없이 동작하도록 graceful degradation을 구현했습니다. 이 설계를 ADR 0012로 문서화했습니다.

### Q49. 코드 리뷰에서 중요하게 보는 기준은?

첫째, 도메인 불변식이 깨지지 않는가입니다. Settlement의 `adjustForRefund`에서 DONE 상태는 immutable로 금액 변경을 막고, 누적 환불이 결제 금액을 초과하지 못하게 하는 것 같은 규칙이 명시적인지 확인합니다. 둘째, 헥사고날 의존 방향이 지켜지는가입니다. domain이 adapter를 import하거나, 서비스 간 코드 의존이 생기면 즉시 지적합니다. 셋째, 동시성 처리가 안전한가입니다. 락 전략, 멱등 키, 트랜잭션 격리 수준이 적절한지 확인합니다. ArchUnit 테스트와 Testcontainers 통합 테스트를 CI 게이트로 두어 이런 기준이 자동으로 검증되게 합니다.

### Q50. 이 프로젝트를 통해 가장 크게 성장한 부분은?

"정합성"에 대한 깊은 이해입니다. 단순히 ACID 트랜잭션만으로는 분산 시스템의 정합성을 보장할 수 없다는 것을 체감했습니다. Outbox+멱등으로 이벤트 파이프라인의 정합성, 대사 3대 불변식으로 시스템 수준의 정합성, Holdback으로 비즈니스 수준의 안전장치를 층층이 쌓아야 한다는 것을 배웠습니다. 또한 20개의 ADR을 작성하면서 "왜 이렇게 설계했는가"를 명확히 기록하는 습관이 생겼고, 트레이드오프를 정량적으로 평가하는 능력이 크게 향상되었습니다. 정산이라는 도메인이 결제, 환불, 배송, 셀러 관리까지 모든 도메인의 교차점이라 전체 시스템을 설계하는 관점을 키울 수 있었습니다.

---

## 5. 아키텍처 의사결정 방어 (비판적 질문 대응)

> 면접관이 설계 선택에 회의적일 때, 맞서지 말고 "트레이드오프를 알고 골랐다"는 프레임으로 답한다.

### Q51. 헥사고날 아키텍처, 이 규모에 과한 것 아닌가요? (오버엔지니어링 비판)

타당한 지적입니다. 단순 CRUD에는 헥사고날이 보일러플레이트 과잉이라, **전 영역에 일괄 적용하지 않았습니다**. `game` 같은 빈약한 도메인은 가볍게 두고, 정산·결제처럼 (1) 도메인 규칙 자체가 핵심 자산이고 (2) 입출력 어댑터가 REST/Kafka/Batch/Elasticsearch/PDF/PG로 다양한 영역에만 포트/어댑터를 적용했습니다. 이 경우 포트 추상화가 비용값을 합니다 — 도메인을 Spring 없이 단위 테스트할 수 있고(`HoldbackPolicy`, `Settlement` 상태 전이), 어댑터 교체가 도메인에 무영향입니다(`OutboxBackedEventPublisher` ↔ `KafkaOutboxPublisher`). 또한 맹목적 적용이 아니라 ArchUnit(`HexagonalArchitectureTest`)으로 의존 방향을 CI에서 강제하고, 아직 못 지킨 위반은 숨기지 않고 exclude 목록 + 리팩터 주석으로 **부채를 가시화**했습니다. 핵심은 "패턴을 알아서 쓴 게 아니라, 트레이드오프를 알고 도메인별로 차등 적용했다"는 점입니다.

### Q52. MSA라면서 gRPC 같은 서비스 간 통신이 왜 없나요?

이 시스템은 **서비스 간 동기 호출 자체를 설계로 제거**했기 때문에 gRPC가 들어갈 자리가 없습니다. order→settlement는 Transactional Outbox + Kafka 비동기 이벤트로, settlement가 order 데이터를 읽을 때는 공유 DB의 Read-only Projection으로 처리합니다(서비스 간 동기 REST/RPC 0건). gRPC/REST 같은 동기 RPC는 강타입 계약·낮은 지연이 강점이지만 temporal coupling — 호출자가 응답을 기다리고 장애가 전파되는 — 을 유발합니다. 정산은 백오피스 도메인이라 eventual consistency가 허용되고, 장애 격리(order가 죽어도 결제는 지속)가 더 중요하므로 비동기가 적합합니다. 다만 gRPC가 무의미하다는 뜻은 아닙니다. 현재 read-model의 약점은 두 서비스가 같은 DB 테이블을 공유하는 결합인데, **Database per Service로 분리하면 settlement가 order 데이터를 즉시 동기 조회해야 할 때 gRPC 조회 API(Protobuf 강타입 + HTTP/2)가 1순위 후보**입니다. 즉 "몰라서 안 쓴 게 아니라, 현재 일관성·결합도 요구에 비동기가 더 맞아 선택하지 않았고, DB 분리 시 도입 후보로 명확히 인지하고 있다"가 정확한 답입니다.
