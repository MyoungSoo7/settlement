# TPS 개선 작업 정리

주문·결제·정산 MSA 플랫폼의 처리량(TPS) 개선을 위해 적용한 전략 모음. 각 항목은 **문제 → 변경 →
파일 → 활성화 → 검증** 순으로 기술한다.

## 배경 — 현재 처리량 천장

`spring.threads.virtual.enabled=true` 로 **가상 스레드**가 켜져 있어 요청 스레드 수에는 상한이 없다.
즉 Tomcat `threads.max` 는 사실상 의미가 없고, 실질 천장은 다음 두 곳이었다.

1. **HikariCP 풀(서비스당 20)** — PostgreSQL `max_connections`(기본 100)를 전 서비스가 공유
2. **Outbox 폴러** — 2초마다 100건을 **1건씩 동기 발행**(단일 인스턴스 ShedLock) → 이벤트 파이프라인 병목

따라서 "스레드 늘리기"가 아니라 **DB 동시성·이벤트 발행·읽기 오프로드·소비 병렬화**가 핵심 레버였다.

---

## 적용 전략 요약

| # | 항목 | 계층 | 기본값 | 효과 |
|---|------|------|--------|------|
| ① | PgBouncer transaction pooling | 인프라(compose) | compose 적용 | DB 커넥션 멀티플렉싱 → 동시 처리량↑ |
| ② | Read Replica 라우팅 | shared-common | **opt-in (off)** | readOnly 트랜잭션을 레플리카로 오프로드 |
| ③ | JDBC 배치 쓰기 | 양 서비스 | 적용 | 대량 INSERT/UPDATE 라운드트립 감소 |
| ④ | Outbox 비동기 배치 발행 | shared-common | 적용 | Kafka 발행 병렬화 + DB 쓰기 일괄 |
| ⑤ | Outbox SKIP LOCKED 멀티워커 | shared-common | 적용 | 인스턴스 수만큼 발행 병렬 확장 |
| ⑥ | Kafka 컨슈머 병렬화 | settlement-service | 적용(concurrency=3) | 파티션 단위 정산 생성 병렬 |
| ⑦ | Redis 2차 캐시(L1+L2) | shared-common | **opt-in (off)** | 공유 캐시 + 크로스 인스턴스 무효화 |
| (보강) | 로컬 캐시 적용 확대 | order-service | 적용 | 카테고리 트리/조회 캐시 |

---

## ① PgBouncer (transaction pooling)

**문제**: 가상 스레드가 수많은 커넥션을 원하지만 PostgreSQL `max_connections=100` 이 한계.

**변경**: `docker-compose.yml` 에 `pgbouncer` 서비스 추가(POOL_MODE=transaction,
DEFAULT_POOL_SIZE=25, MAX_CLIENT_CONN=1000). 앱이 `postgres:5432` → `pgbouncer:6432` 경유.
많은 앱 커넥션을 적은 PG 백엔드로 멀티플렉싱한다.

- transaction 풀링은 서버사이드 prepared statement 와 충돌 → datasource URL 에 **`prepareThreshold=0`**
- 앱 풀 `DB_POOL_MAX=50` 로 상향(PgBouncer 가 흡수)
- 앱 `depends_on` 을 pgbouncer healthcheck 로 변경

**파일**: `docker-compose.yml`

**검증**: compose 구성 변경(런타임 부하 측정 별도 필요).

---

## ② Read Replica 라우팅 (opt-in)

**문제**: 읽기 TPS(상품/카테고리/주문조회/정산 대시보드)가 프라이머리(쓰기) DB 부하를 키움.

**변경**: `ReadReplicaDataSourceConfig` — `LazyConnectionDataSourceProxy` + `AbstractRoutingDataSource`
로 `@Transactional(readOnly = true)` 트랜잭션을 읽기 레플리카로, 그 외는 프라이머리로 라우팅.
`LazyConnectionDataSourceProxy` 로 첫 쿼리 시점까지 커넥션 획득을 지연시켜 readOnly 플래그가 결정된
뒤 라우팅 키를 평가하는 것이 핵심.

- **기본 비활성**(`app.datasource.read-replica.enabled=false`) → Spring Boot 단일 데이터소스 그대로,
  dev/test/기존 동작 무변경
- 활성화 시 `app.datasource.write.*`, `app.datasource.read.*` 에 Hikari 네이티브 프로퍼티 지정
- Boot 4 autoconfigure 패키지 재편과 무관하도록 Spring 코어/Hikari 타입만 사용

**파일**: `shared-common/.../common/config/ReadReplicaDataSourceConfig.java`,
양 서비스 `application.yml`(주석 예시), `shared-common/build.gradle.kts`(HikariCP compileOnly)

**검증**: 컴파일 ✓ (활성화 시 실제 레플리카 엔드포인트 필요).

---

## ③ JDBC 배치 쓰기

**문제**: 정산/원장 대량 INSERT 가 행마다 라운드트립 → 느림.

**변경**:
- datasource URL 에 `reWriteBatchedInserts=true` (JDBC 배치 INSERT 를 단일 multi-row 문장으로 재작성)
- Hibernate `jdbc.batch_size=50`, `order_inserts=true`, `order_updates=true`,
  `batch_versioned_data=true`(@Version 낙관적 락 엔티티도 배치 포함)

**파일**: `order-service/application.yml`, `settlement-service/application.yml`, `docker-compose.yml`

**검증**: 컴파일·기동 레벨 ✓.

---

## ④ Outbox 비동기 배치 발행

**문제**: `OutboxPublisherScheduler` 가 100건을 for 문으로 **1건씩 동기 `send().get()`** → 직렬 처리.

**변경**:
- `PublishExternalEventPort.publishAsync()` 추가, `KafkaOutboxPublisher` 가 `.get()` 없는 async send 반환
- 신규 **`OutboxBatchEventPublisher`**: 전체 이벤트 **비동기 dispatch → 일괄 await → 한 트랜잭션
  일괄 영속**. Kafka 대기(네트워크)를 트랜잭션 밖으로 빼 DB 커넥션 점유 최소화
- 재시도/DLQ 의미 보존: 실패 시 `markFailed`, retryCount 한계(10) 초과로 FAILED 전이되는 순간
  정확히 한 번 DLQ 발행. 재시도 대상(PENDING 유지)은 claim 리스 해제로 다음 주기 즉시 재시도

**파일**: `shared-common/.../outbox/application/service/OutboxBatchEventPublisher.java`,
`PublishExternalEventPort.java`, `KafkaOutboxPublisher.java`, `SaveOutboxEventPort.java`(saveAll)

**검증**: `OutboxBatchEventPublisherDlqTest` ✓, `KafkaOutboxIntegrationTest`(Testcontainers) ✓.

---

## ⑤ Outbox SKIP LOCKED 멀티워커

**문제**: 단일 인스턴스 ShedLock 으로 폴링이 직렬화 → 수평 확장 불가.

**변경**:
- 마이그레이션 `V20260611110000__outbox_claim_columns.sql`: `claimed_at`/`claimed_by` + 부분 인덱스
- `ClaimOutboxEventPort` + `SELECT ... FOR UPDATE SKIP LOCKED` **claim(리스 방식)**.
  여러 인스턴스가 동시에 폴링해도 서로 겹치지 않는 PENDING 행만 가져간다.
  발행 완료 전 워커가 죽으면 리스(claimed_at) 만료 후 다른 워커가 회수 → **별도 reaper 불필요**
- 스케줄러에서 **ShedLock 제거** → 인스턴스 수만큼 발행 병렬 확장
- 사용처 없어진 `OutboxSingleEventPublisher`/`findPending` 제거(데드코드 정리)

**파일**: `OutboxPublisherScheduler.java`, `ClaimOutboxEventPort.java`,
`OutboxEventPersistenceAdapter.java`, `SpringDataOutboxEventRepository.java`,
`order-service/.../db/migration/V20260611110000__outbox_claim_columns.sql`

**검증**: `KafkaOutboxIntegrationTest`(claim → 발행 → PUBLISHED, countPending=0) ✓.

---

## ⑥ Kafka 컨슈머 병렬화

**문제**: 토픽은 파티션 3개지만 `ConcurrentKafkaListenerContainerFactory` 에 `setConcurrency` 가 없어
**단일 컨슈머 스레드**가 직렬 처리.

**변경**:
- `factory.setConcurrency(concurrency)` 추가 → N 개 컨슈머 스레드가 파티션을 나눠 정산 생성 병렬 처리
- `app.kafka.consumer.concurrency`(기본 3), `app.kafka.topic.partitions`(기본 3) 프로퍼티화 →
  파티션과 동시성을 함께 스케일. 원본·DLT 토픽 파티션 수 일치
- **안전성**: 멱등 3단 방어(`processed_events` PK + `settlements.payment_id` UNIQUE)로 파티션 간 병렬
  안전. `payment_id` 키 해시 파티셔닝으로 같은 결제 이벤트는 한 파티션=순서 보장, 서로 다른 결제만 병렬

**파일**: `settlement-service/.../kafka/KafkaErrorHandlerConfig.java`,
`shared-common/.../config/kafka/KafkaConfig.java`, 양 서비스 `application.yml`

**검증**: 컴파일 ✓, `DlqEndToEndTest`(embedded Kafka, concurrency=3) DLT 재시도/라우팅 정상 ✓.

---

## ⑦ Redis 2차 캐시 (L1 Caffeine + L2 Redis, opt-in)

**문제**: 기존 Caffeine 은 **인스턴스 로컬** → 수평 확장 시 인스턴스마다 중복 미스 + 서로 stale.

**변경**:
```
조회: L1(Caffeine) → miss → L2(Redis) → miss → DB(로더)   (L2 적중분은 L1으로 승격)
쓰기: L1·L2 동시 기록 + Redis Pub/Sub로 他 인스턴스 L1 무효화
```
- `TwoTierCache`(`AbstractValueAdaptingCache`), `TwoTierCacheManager`,
  `CacheInvalidationPublisher`/`CacheInvalidationListener`(Pub/Sub 무효화), `TwoTierCacheConfig`
- **graceful degrade**: L2 호출 전부 try/catch → Redis 장애 시 L1/DB 폴백(fail-soft)
- **직렬화**: 필드 기반 Jackson(setter 부작용/방어적 복사 회피) + `DefaultTyping.EVERYTHING`
  (`List<Long>` 이 Integer 로 변질 방지) + `BasicPolymorphicTypeValidator` 로 안전 패키지 제한
  (gadget 역직렬화 방어)
- **키 문자열 통일**: `products` 캐시는 키가 Long(`#productId`)·String(`'all'`) 혼재 → Pub/Sub 는
  문자열만 전달하므로 L1/L2 키를 문자열로 통일해 크로스 인스턴스 무효화 매칭 보장

**게이팅(비침습)**:
- `app.cache.two-tier.enabled`(기본 **false**) → 켜지면 기존 `CacheConfig`(Caffeine)는 back off
- `@ConditionalOnClass(RedisConnectionFactory)` → Redis 없는 settlement-service 는 자동 스킵
- shared-common 은 redis/Hikari 를 `compileOnly` 로만 보유 → settlement 런타임에 redis 강제 안 함
- compose 의 order-service 만 `APP_CACHE_TWO_TIER_ENABLED=true`(Redis 보유)

**파일**: `shared-common/.../common/config/cache/*` (CacheNames, TwoTierCache, TwoTierCacheManager,
TwoTierCacheConfig, CacheInvalidationPublisher, CacheInvalidationListener),
`common/config/CacheConfig.java`(게이팅), `order-service/application.yml`, `docker-compose.yml`,
`shared-common/build.gradle.kts`

**검증**: `TwoTierCacheIntegrationTest`(실제 Redis Testcontainer, 4 케이스 — 직렬화 round-trip /
`List<Long>` 타입 유지 / evict 양 계층 제거 / **Pub/Sub 크로스 인스턴스 무효화**) ✓.

---

## (보강) 로컬 캐시 적용 확대

기존 캐시(`products`, `categories`, `tags`)에 더해 **`EcommerceCategoryService`** 의 카테고리 트리/단건
조회에 `@Cacheable`(`ecommerce-categories`) 적용, 모든 변경 메서드에 `@CacheEvict(allEntries=true)`.
쓰기 메서드의 내부 `getCategoryById` 자기호출은 프록시를 우회(캐시 미적용)하므로 항상 DB 최신값을
읽어 캐시 오염 위험 없음.

> **제외**: `CouponService`(used_count·사용자별 사용여부가 자주 변해 정합성 위험),
> settlement-service SellerTier 조회(paymentId 키라 적중률 사실상 0).

**파일**: `order-service/.../category/application/service/EcommerceCategoryService.java`,
`common/config/CacheConfig.java`(캐시명 추가)

---

## 활성화 토글 (환경변수)

| 환경변수 | 기본 | 설명 |
|----------|------|------|
| `DB_POOL_MAX` | 20(yml)/50(compose) | 앱 HikariCP 최대 풀 |
| `APP_DATASOURCE_READ_REPLICA_ENABLED` | false | ② Read Replica 라우팅 |
| `APP_OUTBOX`(폴링) `app.outbox.polling-delay-ms` | 2000 | ④⑤ Outbox 폴링 주기 |
| `APP_KAFKA_CONSUMER_CONCURRENCY` | 3 | ⑥ 컨슈머 스레드 수 |
| `APP_KAFKA_TOPIC_PARTITIONS` | 3 | ⑥ 토픽 파티션 수(동시성 상한) |
| `APP_CACHE_TWO_TIER_ENABLED` | false | ⑦ L1+L2 2-tier 캐시 |
| `APP_CACHE_L1_TTL_SECONDS` / `APP_CACHE_L2_TTL_SECONDS` | 60 / 600 | ⑦ 계층별 TTL |

> ⑥ 처리량을 더 올리려면 `APP_KAFKA_TOPIC_PARTITIONS` 와 `APP_KAFKA_CONSUMER_CONCURRENCY` 를
> 함께 상향한다(동시성 ≤ 파티션 수). 기존 토픽 파티션은 자동으로 줄지 않으므로 늘리는 방향만.

---

## 검증 요약

| 검증 | 대상 | 결과 |
|------|------|------|
| 전체 모듈 컴파일(main+test) | 4 모듈 | ✅ |
| `OutboxBatchEventPublisherDlqTest` | ④ DLQ/재시도 의미 | ✅ |
| `KafkaOutboxIntegrationTest` (Testcontainers PG+Kafka) | ④⑤ claim→발행→PUBLISHED | ✅ |
| `DlqEndToEndTest` (embedded Kafka) | ⑥ concurrency=3 DLT | ✅ |
| `TwoTierCacheIntegrationTest` (Redis Testcontainer) | ⑦ 직렬화/무효화 4 케이스 | ✅ |

> ①③② 는 컴파일·기동 레벨까지 검증. 실제 TPS 개선폭은 prod-like 부하 테스트(k6/Gatling)로 측정 필요.

---

## 후속 과제

- **부하 테스트**: k6/Gatling 시나리오로 개선 전/후 TPS·p99 수치화. Hikari `active/pending`,
  `outbox.pending.count`, Kafka consumer lag, PG CPU 중 무엇이 먼저 포화되는지 관측 후 추가 튜닝.
- **문서/런북 갱신**: 멀티워커 outbox(ShedLock 제거)·2-tier 캐시 도입에 맞춰
  `docs/runbook/outbox-backlog.md`, `CLAUDE.md` 의 "2초 단일 폴러" 서술 갱신.
- **Read Replica 실배포**: ② 활성화 + 실제 레플리카 엔드포인트 구성, 라우팅 정확도 모니터링.
