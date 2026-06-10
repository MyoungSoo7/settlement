# Lemuel 프로젝트 메모리 사용 분석

이커머스 + 정산 MSA 플랫폼(Java 25 / Spring Boot 4)에서 **메모리(JVM 힙·오프힙·외부 메모리)가 사용되는 지점**을 코드·설정 근거와 함께 분석한 문서. "어디서 메모리를 쓰는가 → 얼마나(상한) → 어떻게 보호하는가" 순으로 정리한다.

> 근거: `Dockerfile`, `docker-compose.yml`, `application.yml`(order/settlement), `CacheConfig`/`TwoTierCacheManager`, `RateLimitRegistry`, `PgRouter`, 커서 페이지네이션·PDF 어댑터·Outbox claim 등 정독.

---

## 1) JVM 힙 / 컨테이너 메모리

**위치**: `Dockerfile:51`, `docker-compose.yml`

```
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"
```

- **고정 `-Xmx` 대신 비율 기반**: 컨테이너 메모리 한도의 75%를 최대 힙으로 사용(`MaxRAMPercentage`). K8s 에서 Pod 메모리 limit 만 조정하면 힙이 따라가므로 운영 친화적.
- `UseContainerSupport` 로 cgroup 메모리 인식 → 호스트 전체 메모리를 잘못 읽는 문제 방지.
- 부속 컴포넌트: Elasticsearch `-Xms512m -Xmx512m`(별도 프로세스), Kafka/Redpanda 별도.
- 나머지 25%는 **비힙 영역**(Metaspace, 스레드 스택, JIT 코드 캐시, Netty/JDBC 다이렉트 버퍼)을 위한 여유.

---

## 2) 온-힙 캐시 (Caffeine L1)

**위치**: `CacheConfig.java`, `TwoTierCacheManager.java`, `application.yml`

```
Caffeine.newBuilder().expireAfterWrite(10분).maximumSize(500)
# application.yml: spec: maximumSize=500,expireAfterWrite=600s
```

- **항목 수 상한(`maximumSize=500`) + TTL(10분)** 으로 캐시가 무한히 자라지 못하게 **경계(bounded)** 설정. 메모리 누수 방지의 핵심.
- 제거 정책은 Caffeine **W-TinyLFU**(빈도+최신성 기반) — LRU보다 적중률 높음.
- 2-tier 모드(opt-in): 각 캐시가 전용 L1(Caffeine, 온힙) + 공유 L2(Redis, 오프프로세스). L2 적중분을 L1 으로 승격(near-cache).
- 캐시 대상: 카테고리·상품 등 조회 빈도 높고 변경 적은 데이터.

---

## 3) DB 커넥션 풀 메모리 (HikariCP)

**위치**: `application.yml:18-27`, `ReadReplicaDataSourceConfig`

```
hikari:
  maximum-pool-size: ${DB_POOL_MAX:20}
  minimum-idle:      ${DB_POOL_MIN_IDLE:5}
  connection-timeout: 10000
  leak-detection-threshold: 30000
```

- **가상 스레드(Virtual Threads) 환경**이라 요청 스레드 수엔 상한이 없음 → 동시 처리량의 실질 천장은 **커넥션 풀**. 명시적 20으로 의도된 스로틀.
- 각 커넥션은 JDBC 버퍼·statement 캐시를 점유하므로 풀 크기가 곧 메모리·DB 부하. PostgreSQL `max_connections`(기본 100)를 서비스들이 공유하므로 과대 설정 금지(주석에 명시).
- **read-replica 분리**(`ReadReplicaDataSourceConfig`) → 읽기 전용 별도 풀. 쓰기/읽기 커넥션 메모리 분리.
- `leak-detection-threshold` 로 커넥션 누수(반납 안 됨) 조기 탐지.

---

## 4) 인메모리 애플리케이션 상태 (힙 상주 컬렉션)

| 위치 | 자료구조 | 메모리 특성 |
|------|----------|-------------|
| `RateLimitRegistry.buckets` | `ConcurrentHashMap<String, Bucket>` | (정책\|키)별 버킷 **lazy 생성, in-memory 상주**. 단일 노드 가정 — 키 카디널리티가 크면 증가. Bucket4j 가 refill 기반 관리 |
| `PgRouter.adaptersByProvider` | `LinkedHashMap<PaymentGateway, Adapter>` | PG 개수만큼(소수, 고정). 부팅 시 1회 구성 |
| `EcommerceCategory.children` | `List<EcommerceCategory>` | 카테고리 트리 조회 시 메모리 적재(최대 깊이 2로 제한, 폭은 데이터 의존) |
| Spring 싱글톤 빈 | — | UseCase/어댑터 등은 싱글톤이라 인스턴스 수 고정 |

> **주의 포인트**: `RateLimitRegistry` 는 IP/사용자 키 카디널리티가 매우 크면 버킷 맵이 커질 수 있다. 코드 주석도 "단일 노드 가정"을 명시 — 다중 노드/대규모는 Redis 기반 분산 rate limit 로 전환 검토 대상.

---

## 5) 쿼리 결과 메모리 — 경계 설정 (가장 중요)

대량 조회가 힙을 터뜨리지 않도록 **결과 행 수를 항상 상한**으로 묶는다.

### 5-1. 커서 기반 페이지네이션
**위치**: `SettlementQueryRepositoryImpl.java`
```java
int fetchSize = Math.min(condition.getSize() > 0 ? condition.getSize() : 20, 100); // 최대 100행
... .limit(fetchSize + 1)   // +1 로 hasNext 판별
```
- 페이지 크기 **최대 100행으로 캡**. offset 방식이 아닌 **커서(cursor)** 방식이라 깊은 페이지에서도 대량 스캔/메모리 적재 없음.

### 5-2. 배치/폴링의 LIMIT 적재
- Outbox claim: `selectClaimableIds(limit, ...)` — 한 번에 `limit` 행만 claim → 발행(배치 크기 제한).
- `PayoutPersistenceAdapter`, `SettlementPersistenceAdapter`(holdback), `ChargebackPersistenceAdapter`, `PgReconciliationPersistenceAdapter` 모두 `PageRequest.of(0, limit)` 로 **상한 적재**.
- 효과: "전체 PENDING 을 메모리에 다 올리기" 같은 OOM 패턴 회피.

### 5-3. Hibernate JDBC 배치
**위치**: `application.yml:38-43`
```
jdbc.batch_size: 50, order_inserts: true, order_updates: true
```
- 정산/원장 대량 INSERT/UPDATE 를 50개 단위로 묶어 라운드트립·메모리 churn 감소.

---

## 6) 대용량 객체 버퍼 (PDF / 파일)

| 위치 | 버퍼 | 메모리 특성 |
|------|------|-------------|
| `SettlementPdfAdapter`, `CashflowPdfAdapter` | `ByteArrayOutputStream` → `byte[]` (iText) | **PDF 전체를 힙에 byte[] 로 생성**. 건당 PDF 라 보통 작지만, 대량 동시 생성 시 힙 압박 가능 |
| `CsvPgFileParserAdapter` | `InputStream` 파싱 | PG 대사 CSV 파싱. 스트리밍 파싱으로 행 단위 처리 지향 |

> **주의 포인트**: PDF 생성은 `ByteArrayOutputStream` 으로 전량 메모리 적재. 정산서가 매우 커지거나 동시 다발 생성되면 힙 사용량이 튈 수 있어, 필요 시 스트리밍/임시파일 전환이나 동시 생성 수 제한을 고려.

---

## 7) 오프힙 / 외부 메모리

| 대상 | 위치 | 비고 |
|------|------|------|
| Redis (L2 캐시) | `TwoTierCache` | JVM 힙 밖. graceful degrade(장애 시 L1/DB 폴백) |
| Elasticsearch | 색인/검색 | 별도 프로세스, `-Xmx512m`. 정산 색인 큐(`settlement_index_queue`) 비동기 적재 |
| Kafka/Redpanda | 이벤트 버스 | consumer 버퍼, `read_committed`, manual commit(`enable-auto-commit: false`) |
| JDBC/Netty 다이렉트 버퍼 | 드라이버 | 힙 외 native 메모리 — 컨테이너 RAM 25% 여유분에서 사용 |

---

## 8) 메모리 안전을 위한 설계 결정

- **`open-in-view: false`** (`application.yml:33`): OSIV 비활성화 → 영속성 컨텍스트가 뷰 렌더링까지 유지되지 않음. 요청 종료 시점에 1차 캐시(엔티티)가 빨리 해제돼 힙 점유 시간 단축 + LazyInit 남용 방지.
- **커서 페이지네이션 + 결과 캡(100)**: 무제한 조회 차단.
- **bounded 캐시(maximumSize+TTL)**: 캐시 누수 방지.
- **가상 스레드 + 커넥션 풀 스로틀**: 스레드는 가볍게, 진짜 자원(DB 커넥션)은 풀로 제한.
- **DB-backed 큐(Outbox/색인 큐)**: 대량 미발행 이벤트를 **메모리 큐가 아닌 테이블**에 보관 → 재기동/백프레셔에도 힙 안전.

---

## 9) 잠재적 메모리 리스크 & 점검 포인트

| 영역 | 리스크 | 권장 점검 |
|------|--------|-----------|
| `RateLimitRegistry` | 키 카디널리티 폭증 시 버킷 맵 증가(단일 노드 가정) | 키 수 메트릭(`size()`) 모니터링, 대규모 시 분산 rate limit |
| PDF 생성 | `ByteArrayOutputStream` 전량 적재 | 동시 생성 수 제한 / 스트리밍 전환 |
| Caffeine L1 | 값 객체가 크면 500개라도 무거움 | 캐시 값 크기·`recordStats` 적중률 점검 |
| 커넥션 풀 | 풀 고갈 시 가상 스레드 대기 | `connection-timeout`(10s) 빠른 실패 유지, 풀 사용률 모니터링 |
| 다이렉트 버퍼 | native 메모리 누수 | 컨테이너 RSS vs 힙 모니터링(`MaxRAMPercentage 75%`) |

---

## 정리

- **힙 사이징**: 컨테이너 비율 기반(`MaxRAMPercentage=75`) — 고정 `-Xmx` 미사용, K8s 친화.
- **온힙 캐시**: Caffeine `maximumSize=500` + 10분 TTL + W-TinyLFU → 경계된 메모리.
- **결과 메모리**: 커서 페이지네이션(최대 100행) + 배치 LIMIT 적재 + Hibernate JDBC batch(50)로 대량 조회 OOM 차단.
- **인메모리 상태**: rate limit 버킷 맵·PG 라우팅 맵·카테고리 트리 — 대부분 작거나 경계됨(버킷 맵만 주의).
- **외부로 분산**: Redis(L2)·Elasticsearch·Kafka·DB-backed 큐로 힙 부담을 오프힙/외부로 이전.
- **안전 장치**: `open-in-view: false`, 커넥션 풀 스로틀, bounded 캐시, 트랜잭셔널 Outbox.
