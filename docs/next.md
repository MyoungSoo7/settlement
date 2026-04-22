# Next — Tier 2 & Tier 3 작업 계획

**작성일:** 2026-04-22
**컨텍스트:** Tier 1 (대사·불변성·Outbox·Resilience4j) 완료 이후 "100점"까지 남은 작업. 인프라성 + 도메인 성숙도 중심.

각 항목은 **독립 실행 가능한 PR 단위**로 쪼개져 있다. 순서 의존성은 "선행 조건" 항목에 명시.

---

## Tier 2 — 운영 품질 (Production Quality)

### T2-④ 구조화 로그 + traceId MDC + Alertmanager 룰

**Why.** 현재 로그는 평문이라 ELK/Loki 에 넣어도 필드 검색 어려움. 결제→정산→환불 체인에서 어디서 실패했는지 `traceId` 로 연결해 볼 수 있어야 인시던트 대응 가능. Prometheus 에 SLO 게이지는 있는데 **Alertmanager 룰이 없어서 실제로 알림이 오지 않음**.

**구현 범위.**
1. **`logback-spring.xml`** 추가
   - `spring` 프로파일 기본: JSON 출력 (`logstash-logback-encoder`)
   - `local` 프로파일: 기존 색깔 평문 포맷 유지
   - MDC 키: `traceId`, `spanId`, `userId`, `orderId`, `paymentId`, `settlementId`
2. **`TraceIdFilter`** (servlet Filter) — `common.config.observability`
   - 요청 헤더 `X-Request-Id` 있으면 사용, 없으면 `UUID.randomUUID()` 생성 후 MDC 주입 + 응답 헤더 에코
   - `finally { MDC.clear(); }`
3. **서비스 레이어 MDC 전파**
   - `CreatePaymentUseCase`, `RefundPaymentUseCase`, `CreateSettlementFromPaymentService`, `AdjustSettlementForRefundService` 에서 MDC 에 `paymentId`/`settlementId` 주입
   - `@Around("execution(* *..application.service..*(..))")` AOP 로 자동 주입도 고려 가능 (오버엔지니어링 위험)
4. **OpenTelemetry (선택)**
   - `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` 의존성
   - `management.tracing.sampling.probability=1.0` 로컬, 운영 0.1
   - Jaeger/Tempo 연결은 env 로 제공만 (인프라 쪽 작업)
5. **Alertmanager 룰 파일** — `monitoring/alertmanager/rules.yml`
   - `SettlementReconciliationMismatch`: `ReconcileDailyTotalsService` 의 ERROR 로그 (또는 미터 카운터) 1건 이상 → critical
   - `TossPgCircuitOpen`: `resilience4j_circuitbreaker_state{name="tossPg",state="open"} > 0` → critical
   - `PaymentRefundRate`: `rate(refund_processing_duration_seconds_count[5m]) > 10` → warning (비정상 환불 폭증)
   - `OutboxBacklog`: `lemuel_outbox_pending_count > 100` → warning (이벤트 발행 지연)

**추가될 의존성.**
- `net.logstash.logback:logstash-logback-encoder:7.4` (testImplementation 아님, runtime 필요)
- (선택) `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp`

**영향 받는 파일.**
- 신규: `src/main/resources/logback-spring.xml`, `common/config/observability/TraceIdFilter.java`, `monitoring/alertmanager/rules.yml`
- 수정: `build.gradle.kts` (logstash encoder), `application.yml` (로깅 레벨 + tracing 설정)
- 서비스 수정은 범위 넓음 → 최소한 결제·정산·환불 주요 진입점만 MDC 주입

**예상 난이도.** 중. 순수 추가성이라 기존 코드 영향 적음. OpenTelemetry 빼면 반나절.

---

### T2-⑤ Audit Log + PII 마스킹 + Rate Limiting

**Why.** 결제 확정·정산 확정·환불·권한 변경 같은 **민감 작업의 감사 추적**이 없음. 로그에 이메일·전화번호가 평문으로 남아 **컴플라이언스 리스크**. `nginx` 에 rate limit 있지만 애플리케이션 레벨은 없어서 로그인/환불 API 에 개별 사용자 기준 제한 불가.

#### (a) Audit Log

**도메인.**
- `AuditLog` 엔티티: `id, actor_id, actor_email, action, resource_type, resource_id, detail_json, ip_address, user_agent, created_at`
- `action` enum: `SETTLEMENT_CONFIRMED, SETTLEMENT_CANCELED, REFUND_REQUESTED, REFUND_COMPLETED, USER_ROLE_CHANGED, LOGIN_SUCCESS, LOGIN_FAILED`
- `Auditable` 커스텀 어노테이션 + `@Around` AOP — 대상 메서드 실행 후 자동 저장
- `AuditContextHolder` (ThreadLocal) — 현재 요청의 actor/ip/ua 보관. `AuditInterceptor` 에서 주입.

**포트/어댑터.**
- `SaveAuditLogPort` + `AuditLogPersistenceAdapter` + `AuditLogJpaEntity`
- V31 마이그레이션: `audit_logs` 테이블 (partial index on `actor_id`, `created_at`, `action`)
- `audit_logs.created_at` 기준 **월별 파티셔닝** 고려 (운영 장기 보관용)

**적용 지점.** (메서드에 `@Auditable(action=...)` 추가)
- `ConfirmDailySettlementsService.confirmDailySettlements`
- `RefundPaymentUseCase.refundPayment`
- `LoginService.login` (성공/실패 모두)
- `CreateUserService.createUser` (role=ADMIN 인 경우 특히)
- 추후 settlement 수동 취소 API 생기면 여기도

#### (b) PII 마스킹

- `PIIMaskingConverter` (Logback `ClassicConverter`) — 로그 메시지에서 이메일/전화/카드번호 패턴 치환
  - 이메일: `user@example.com` → `u***@e***.com`
  - 전화: `010-1234-5678` → `010-****-5678`
  - 카드: 앞 6자리 + `*` + 뒤 4자리
- `logback-spring.xml` 에 `<conversionRule conversionWord="mask" converterClass="..."/>` 등록 후 패턴에 `%mask(%msg)` 적용
- **API 응답 마스킹**은 별도: Jackson `@JsonSerialize(using = EmailMaskingSerializer.class)` 를 `UserResponse.email` 등에

#### (c) Rate Limiting (애플리케이션 레벨)

- 선택지:
  - (A) `bucket4j-spring-boot-starter` + Redis backed — 클러스터 환경 정확
  - (B) `bucket4j` in-memory — 단일 노드 가정, 빠르게 MVP
  - (C) Spring Cloud Gateway — 이미 nginx 있어서 오버엔지니어링

MVP 로 **(B) bucket4j + in-memory Caffeine-backed ProxyManager**.
- `RateLimitFilter` (servlet Filter): 경로별 정책
  - `/auth/login`: IP 기준 5req/min
  - `/payments/*/refund`: actor 기준 10req/min
  - `/admin/**`: actor 기준 30req/min
- 초과 시 429 + `Retry-After` 헤더

**추가될 의존성.**
- `com.bucket4j:bucket4j-core:8.10.1`
- (선택) `com.bucket4j:bucket4j-redis` + Redis 인프라

**영향 받는 파일.**
- 신규: `common/audit/*` (도메인·포트·어댑터·AOP), `AuditLogJpaEntity`, `V31_create_audit_logs.sql`, `PIIMaskingConverter.java`, `RateLimitFilter.java`
- 수정: `logback-spring.xml`, `SecurityConfig.java` (필터 체인에 RateLimitFilter 등록), 위 서비스 4개에 `@Auditable`

**예상 난이도.** 높음. Audit AOP + Rate limit + PII 세 개라 1~2일. 각 PR 로 쪼개 올릴 것.

---

### T2-⑥ 통합 테스트 확대 → JaCoCo 70%+

**Why.** 현재 커버리지 22%. CLAUDE.md 의 70% 목표까지 나머지 격차는 **전부 통합 테스트로만 커버 가능**. 저쪽 세션이 `2f24f17` 에 Testcontainers + SecurityConfig WebMvcTest 기반 깔아둠. 이걸 수평 확장.

**선행 조건.** 없음. `testImplementation("org.testcontainers:postgresql")` 이미 들어가 있음.

**구현 범위.**

1. **`@DataJpaTest` 베이스 클래스** (`AbstractPersistenceIntegrationTest`)
   - `@Testcontainers` + `@ServiceConnection` 으로 Postgres 16 컨테이너 공유
   - `@Sql` 로 테스트 데이터 세팅 가능
   - 예시 마이그레이션 검증: Flyway V1~V30 전부 재생 → Hibernate schema validation

2. **모든 persistence adapter 에 통합 테스트 1개씩** (예상 12~15개)
   - `OrderPersistenceAdapterIT`, `PaymentPersistenceAdapterIT`, `SettlementPersistenceAdapterIT`,
     `SettlementAdjustmentPersistenceAdapterIT`, `RefundPersistenceAdapterIT`,
     `EcommerceCategoryPersistenceAdapterIT`, `ProductImagePersistenceAdapterIT`,
     `CouponPersistenceAdapter IT`, `ReviewPersistenceAdapterIT`, `UserPersistenceAdapterIT`,
     `OutboxEventPersistenceAdapterIT`, `DailyTotalsJdbcAdapterIT`
   - 각각 CRUD + 커스텀 쿼리 (`findByPaymentId`, `findBySettlementDateAndStatus` 등) + `@Version` 동시성 테스트 (해당되는 곳)

3. **V30 트리거 통합 테스트** (`SettlementImmutabilityTriggerIT`)
   - DONE 정산의 `payment_amount` 를 JDBC 로 직접 UPDATE 시도 → PSQLException 확인
   - 트리거가 실제로 작동하는지 CI 에서 검증 (단위 테스트로는 불가능)

4. **`@WebMvcTest` 확대** (현재 SettlementController, OrderController, UserController, ProductController, GameController 는 존재. 나머지)
   - `PaymentController`, `CategoryController`, `CouponController`, `ReviewController`, `ReconciliationController`
   - 각 엔드포인트 happy/sad path (인증 모킹 + `@MockitoBean UseCase`)

5. **`@SpringBootTest` end-to-end 1~2 개**
   - `PaymentRefundSettlementFlowIT`: 결제 CAPTURED → 환불 → 정산 조정 → Outbox 이벤트 발행까지 전 구간
   - `ReconciliationFlowIT`: seed 후 `/admin/reconciliation` 호출해서 matched=true 확인

6. **Pitest mutation testing (선택)**
   - `info.solidsoft.pitest:info.solidsoft.pitest.gradle.plugin`
   - `mutationThreshold=60` 으로 설정 → 테스트가 실제로 버그를 잡는지 메타 검증
   - CI 에서 PR 단위 feature 패키지만 수행 (전체 돌리면 너무 오래)

**JaCoCo 게이트 상향 로드맵.**
- 현재 0.22
- `@DataJpaTest` + Persistence adapter 테스트 12개 추가 → ~35%
- `@WebMvcTest` 나머지 + `@SpringBootTest` → ~55%
- 남은 서비스·도메인 빈 구멍 메우기 → 65~70%
- 각 단계마다 build.gradle.kts 의 `minimum` 값 조정

**예상 난이도.** 높음 (단순 작업이 많이 쌓여 있는 형태). 3~5일. 하지만 패턴만 잡히면 나머지는 복붙.

---

## Tier 3 — 비즈니스 성숙도 (Business Maturity)

### T3-⑦ VIP 차등 수수료 + 정산 주기 + Refund 이력 API

**Why.** 현재 수수료율이 `Settlement.java` 의 `COMMISSION_RATE = 0.03` 으로 **하드코드**. 실제 서비스에서는 판매자 등급별 차등 (VIP 2.5%, 일반 3.5% 등) 이 기본. 정산 주기도 일일만 지원 → 주간/월간 요청 대응 불가. 부분 환불 이력을 관리자가 볼 엔드포인트 없음.

#### (a) 차등 수수료

**도메인 모델.**
- `FeeRateStrategy` 인터페이스 (`settlement.domain`):
  ```java
  public interface FeeRateStrategy {
      BigDecimal rateFor(SellerTier tier, BigDecimal paymentAmount, LocalDate settlementDate);
  }
  ```
- `SellerTier` enum: `NORMAL(0.035)`, `VIP(0.025)`, `STRATEGIC(0.02)`
- `DefaultFeeRateStrategy` (기본 구현): 판매자 등급 기반 단순 조회
- `PromotionalFeeRateStrategy` (향후): 특정 기간 할인 적용

**Settlement.createFromPayment 시그니처 확장.**
```java
public static Settlement createFromPayment(
    Long paymentId, Long orderId, BigDecimal paymentAmount,
    LocalDate settlementDate, BigDecimal commissionRate)
```
- 기본 오버로드 `createFromPayment(..., 3% 고정)` 유지 — 테스트 호환

**판매자 등급 조회.**
- `Product.sellerId` 에서 판매자 정보 필요 → `LoadSellerTierPort` 신설 (user 도메인에 `Seller` 서브 도메인 추가 or 기존 `User.role` 에 `VIP_SELLER` 같은 값)
- 단순하게 접근: `User` 에 `seller_tier` 컬럼 추가 (V32 마이그레이션), 기본값 NORMAL
- `CreateSettlementFromPaymentService` 가 payment→order→product→seller 로 거슬러 올라가 tier 조회 후 Strategy 호출

**주의:** 과거 정산의 commission_rate 는 그대로 유지되어야 함 → `settlements` 테이블에 `commission_rate NUMERIC(5,4)` 컬럼 추가 (V33). 조회 시 이 값으로 재계산 확인 가능.

#### (b) 정산 주기 유연화

**도메인.**
- `SettlementCycle` enum: `DAILY`, `WEEKLY_MON`, `MONTHLY_LAST`
- `Seller.settlementCycle` — 판매자별 설정
- `SettlementScheduleResolver` 도메인 서비스: `resolveNextSettlementDate(paymentDate, cycle) → LocalDate`
  - DAILY: paymentDate + 1
  - WEEKLY_MON: 다음 월요일
  - MONTHLY_LAST: 같은 달 마지막 일

**적용.**
- `CreateSettlementFromPaymentService` 가 주기 기반 `settlementDate` 계산 (현재는 `LocalDate.now().plusDays(1)` 하드코드)
- 정산 배치 Cron 은 그대로 매일 돌되, 각 정산은 자기 `settlementDate` 에 맞춰 처리됨 (현재 이미 이렇게 동작)
- V32 에 `users.settlement_cycle VARCHAR(20) DEFAULT 'DAILY'` 추가

#### (c) Refund 이력 조회 API

**엔드포인트.**
```
GET /refunds?paymentId=123
GET /payments/123/refunds   (alternative)
```

**추가 포트.**
- `LoadRefundsByPaymentPort` + 어댑터 구현 (JPA repository `findByPaymentIdOrderByRequestedAtDesc`)

**응답.**
```json
{
  "paymentId": 123,
  "originalAmount": "50000.00",
  "totalRefundedAmount": "30000.00",
  "refundableAmount": "20000.00",
  "refunds": [
    { "id": 1, "amount": "10000.00", "status": "COMPLETED",
      "idempotencyKey": "...", "reason": "PARTIAL_REFUND",
      "requestedAt": "...", "completedAt": "..." },
    ...
  ]
}
```

**Controller.** `RefundQueryController` 신규. 관리자 권한 필수 (`@PreAuthorize("hasRole('ADMIN')")`).

#### (d) 선택: 정산서 PDF 버전 관리

- 현재 `GenerateSettlementPdfService` 는 호출할 때마다 새로 생성. 과거 정산서를 그대로 재발급해야 하는 요구사항 (감사·분쟁) 있으면 `settlement_pdf_snapshots` 테이블에 한번 생성한 PDF 의 SHA256 + S3 key 저장. 이건 별도 PR 로.

**예상 난이도.** 중~높음. (a)(b) 는 DB 스키마 변경 + 다 도메인 수정이라 꼼꼼히. (c) 는 반나절.

---

### T3-⑧ ADR + Runbook 문서화

**Why.** 지금까지 내린 결정들이 **커밋 메시지에만 있음**. 새로 합류하는 사람이 "왜 헥사고날?", "왜 Kafka?", "왜 @Version 쓰면서 domain 에 version 필드?" 같은 질문에 답을 찾으려면 커밋 archaeology 가 필요. 운영 장애 때 **Runbook 없으면 매번 재발명**.

#### (a) ADR (Architecture Decision Records)

**경로.** `docs/adr/`
**템플릿:** Michael Nygard 표준 (Context / Decision / Consequences / Status)

**작성할 ADR (최소):**
1. `0001-hexagonal-architecture.md` — 왜 헥사고날, 도메인/포트/어댑터 경계 규칙
2. `0002-settlement-state-machine.md` — REQUESTED → PROCESSING → DONE/FAILED → CANCELED. 레거시 상태 제거 기록
3. `0003-refund-idempotency-and-outbox.md` — idempotency_key = `payment-{id}-full` 규칙 + Outbox 패턴 도입 이유
4. `0004-reverse-settlement-via-adjustment.md` — DONE 정산 immutable + SettlementAdjustment 음수 레코드 이유
5. `0005-kafka-vs-application-events.md` — `app.kafka.enabled` 플래그로 듀얼 구동 가능 이유, Redpanda 선택 근거
6. `0006-resilience4j-tosspg.md` — 서킷·재시도 파라미터(20/50/30s, 3회/500ms×2) 선택 근거
7. `0007-audit-log-strategy.md` — (T2-⑤ 수행 후) AOP + 테이블 분리 설계
8. `0008-fee-rate-strategy-pattern.md` — (T3-⑦ 수행 후) 차등 수수료 전략 설계

**작성 방법.** 각 ADR 한 파일당 30~60분. 과거 결정은 현재 코드 상태 기반으로 retrofit. 미래 결정은 구현 전 draft 올리고 review 후 Accepted.

#### (b) Runbook

**경로.** `docs/runbook/`

**작성할 Runbook (시나리오):**
1. `settlement-mismatch.md` — `/admin/reconciliation` 에서 matched=false 나왔을 때 조사 절차
   - `settlement_adjustments.refund_id IS NULL` 행 조회 (Refund 엔티티 도입 전 레거시)
   - payments vs refunds 합계 직접 쿼리
   - 최근 DB 변경 로그 (`audit_logs`) 확인
   - 에스컬레이션 연락처
2. `toss-pg-outage.md` — 서킷 OPEN 알림 수신 시 절차
   - Grafana 대시보드 URL
   - Toss 상태 페이지 확인
   - 수동으로 서킷 CLOSE 전환 방법 (`POST /actuator/circuitbreakerevents`)
   - 장시간 장애 시 결제 수단 전환 정책
3. `outbox-backlog.md` — `lemuel_outbox_pending_count` 알림 수신 시
   - Kafka/Redpanda 헬스체크
   - polling job 로그 확인
   - 수동 flush 쿼리
4. `db-migration-rollback.md` — Flyway 마이그레이션 실패 시
   - `flyway_schema_history` 수동 수정 절대 금지
   - 전진 롤백 원칙 (V{N+1} 에 UNDO 로직 적용)
   - 예외 상황 (운영 중단 시 snapshot 복원) 판단 기준
5. `disaster-recovery.md` — DB 손실 / K8s 클러스터 장애 시
   - PITR 복구 단계
   - RPO/RTO 정의
   - DR 훈련 주기 (분기 1회 권장)

**예상 난이도.** 낮음~중. 각 0.5~1일. 기존 코드/인프라 조사가 시간 잡아먹음.

---

## 진행 권장 순서

1. **T2-④** (로그 + traceId) — 다른 모든 관찰가능성의 전제. 가장 ROI 큼.
2. **T2-⑥** (통합 테스트) — JaCoCo 70% 도달의 유일한 경로. 다른 T2/T3 작업의 회귀 방어망.
3. **T2-⑤** (Audit + PII + Rate limit) — 3개 하위 항목으로 PR 쪼개기
4. **T3-⑦ (c)** (Refund 이력 API) — 단독 반나절. (a)(b) 보다 먼저 해도 무방
5. **T3-⑧ (a)** (ADR) — T2/T3 작업 진행하면서 **병행** 작성 (결정을 내린 순간 기록)
6. **T3-⑦ (a)(b)** (차등 수수료 + 주기) — DB 스키마 변경이라 신중히
7. **T3-⑧ (b)** (Runbook) — 모든 인프라 구축 후 운영 관점에서 작성

## 남은 잠재 리스크 (여기 계획 밖)

- **Kafka 이벤트 스키마 버전 관리** (Avro/Protobuf + Schema Registry) — 현재는 JSON 직렬화라 BREAKING change 제어 없음
- **JWT refresh token 회전** — access token 1시간이면 현재 설정 충분하지만 보안팀 요구 시
- **다통화 지원** — 요구사항 없으면 스킵
- **부하 테스트 (k6/Gatling)** — 정산 배치 1000만 건 시나리오. T2-⑥ 마친 후 SRE 관점 별도 작업
