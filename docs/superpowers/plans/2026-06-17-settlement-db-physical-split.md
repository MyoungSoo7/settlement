# settlement_db 물리 분리 구현 계획 (ADR 0020 Phase 4)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** settlement-service 를 order 와 공유하던 `opslab` 에서 떼어내, 자체 `settlement_db` 인스턴스를 소유하는 진짜 Database-per-Service 로 만든다.

**Architecture:** settlement-service 가 **자체 Flyway 마이그레이션**을 소유해 settlement 가 쓰는 모든 테이블(정산 코어 + 이벤트 프로젝션 + outbox/processed_events)을 `settlement_db` 에 생성한다. 교차 도메인 FK(settlements→payments/orders 등)는 제거한다(cross-DB 불가). datasource 를 `settlement_db` 로 전환하고, 기존 opslab 데이터를 백필(프로젝션은 이벤트 재발행, 정산 코어는 1회성 데이터 이관)한다. order-service 는 더 이상 settlement 테이블을 만들지 않는다.

**Tech Stack:** Spring Boot 4 standalone(settlement-service), Flyway, PostgreSQL 17, Testcontainers, Kafka(Redpanda), 기존 Outbox/프로젝션 파이프라인(Phase 1~3 완료).

---

## 선행 사실 (실행 전 숙지)

- **Phase 1~3 완료 상태**: settlement 의 코드상 order 테이블 직접 매핑 0. 정산 생성·조회·검색이 모두 settlement 소유 프로젝션(`settlement_payment/order/user/product_view`)과 이벤트로만 동작한다. 즉 **런타임에 settlement 가 order 테이블을 읽지 않는다** — 물리 분리의 코드 선결은 끝났다.
- **남은 결합 = 공유 DB(opslab) 하나뿐.** settlement-service application.yml datasource = `jdbc:postgresql://localhost:5432/opslab` (order 와 동일).
- **settlement 소유 테이블 (settlement_db 로 가야 할 것):**
  | 테이블 | 현재 생성 위치(opslab 마이그레이션) |
  |---|---|
  | settlements, settlement_adjustments | V2(혼재), V4 |
  | settlement_index_queue | V5 |
  | settlement_schedule_config | V6 |
  | pg_reconciliation_runs, pg_reconciliation_discrepancies | V35 |
  | payouts | V43 |
  | chargebacks | V44 |
  | ledger_entries | V45 |
  | ledger_outbox | V49 |
  | settlement_loan_deductions | V20260616120000 |
  | settlement_payment/order/user/product_view | V202606161300~160000 |
  | (인프라) outbox_events, processed_events | V28, V29 (settlement 도 발행/소비) |
- **제거할 교차 도메인 FK** (cross-DB 불가): `settlements.fk_settlement_payment → payments(id)`, `settlements.fk_settlement_order → orders(id)`. V4/V43/V44/V45 등에 추가 교차 FK 있으면 동일 처리(실행 시 grep 으로 전수).
- **운영 데이터 이관은 별개 리스크**: 이 계획은 **신규/Testcontainers 기준 스키마 분리**를 다룬다. 운영 opslab 의 기존 정산 데이터를 settlement_db 로 옮기는 1회성 ETL 은 Chunk 4에서 별도 절차로 명시(자동화 범위 밖).

---

## File Structure

```
settlement-service/
├── src/main/resources/application.yml          # datasource → settlement_db, flyway 자체 소유
├── src/main/resources/db/migration/            # ★ 신설 — settlement 자체 스키마
│   ├── V1__settlement_core.sql                 # settlements, settlement_adjustments (교차 FK 없음)
│   ├── V2__settlement_ops.sql                  # index_queue, schedule_config, pg_reconciliation_*, payouts, chargebacks
│   ├── V3__ledger.sql                          # ledger_entries, ledger_outbox
│   ├── V4__settlement_projections.sql          # payment/order/user/product_view (+ payment_view 확장 필드 포함)
│   ├── V5__settlement_infra.sql                # outbox_events, processed_events, settlement_loan_deductions
│   └── V6__drop_cross_domain_fks.sql           # (기존 opslab 잔재 대비 멱등 드롭 — 신규엔 no-op)
├── src/test/.../SettlementDbBootIT.java         # Testcontainers: settlement_db 단독 부팅 + Flyway 검증
order-service/
├── src/main/.../user|order|product|payment/... # 백필 트리거(기존 데이터 → 이벤트 재발행)
│   └── application/.../SettlementProjectionBackfillService.java
│   └── adapter/in/web/SettlementBackfillAdminController.java
├── src/main/resources/db/migration/             # settlement 테이블 생성 구문 제거(운영 이관 후) — Chunk 4
docker-compose.yml                               # settlement-db(Postgres) 서비스 추가
k8s/base/settlement-*.yaml                       # settlement-db 프로비저닝 + datasource
```

---

## Chunk 1: settlement-service 자체 Flyway 스키마

### Task 1.1: settlement 소유 DDL 추출 → settlement-service 마이그레이션

**Files:**
- Create: `settlement-service/src/main/resources/db/migration/V1__settlement_core.sql` … `V5__settlement_infra.sql`
- Reference(추출 원본): `order-service/.../db/migration/{V2,V4,V5,V6,V35,V43,V44,V45,V49,V20260616120000,...130000~160000,170000,V28,V29}.sql`

- [ ] **Step 1: 원본 DDL 전수 수집**

Run:
```bash
grep -rlE "CREATE TABLE.*(settlements|settlement_adjustments|settlement_index_queue|settlement_schedule_config|pg_reconciliation|payouts|chargebacks|ledger_entries|ledger_outbox|settlement_loan_deductions|settlement_.*_view|outbox_events|processed_events)" order-service/src/main/resources/db/migration/
```
각 파일에서 **settlement 소유 테이블의 CREATE/ALTER/INDEX/COMMENT 구문만** 발췌한다. orders/payments/users/products 등 order 소유 구문은 제외.

- [ ] **Step 2: V1~V5 작성 — 교차 FK 제거**

추출한 DDL 을 settlement-service Flyway 로 옮기되 **다음을 반드시 제거/치환**:
- `CONSTRAINT fk_settlement_payment FOREIGN KEY (payment_id) REFERENCES payments(id)` → 삭제 (payment_id 컬럼은 plain BIGINT 유지)
- `CONSTRAINT fk_settlement_order FOREIGN KEY (order_id) REFERENCES orders(id)` → 삭제
- 그 외 `REFERENCES (orders|payments|users|products)` 전부 삭제 (settlement_db 엔 그 테이블이 없음)
- settlement 내부 FK(예: settlement_adjustments→settlements, ledger 내부)는 **유지**
- payment_view 는 확장 필드(payment_method/refunded_amount/pg_transaction_id) 포함한 **최종 형태**로 한 번에 생성(opslab 의 130000+170000 합본)
- 스키마: settlement_db 는 기본 `public` 사용(또는 `settlement` 스키마 — application.yml 의 default-schema 와 일치시킬 것)

- [ ] **Step 3: Flyway 검증 테스트 (Testcontainers)**

`settlement-service/src/test/.../SettlementDbBootIT.java`: Testcontainers PostgreSQL 로 Flyway clean migrate 가 성공하고, 모든 settlement 엔티티가 `ddl-auto=validate` 를 통과하는지 확인.

```java
@SpringBootTest
@Testcontainers
class SettlementDbBootIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("settlement_db");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("app.kafka.enabled", () -> "false");
        // jwt/기타 필요한 최소 프로퍼티
    }
    @Test void boots_andOwnSchemaValidates() { /* 컨텍스트 로드 = Flyway+validate 통과 */ }
}
```

Run: `./gradlew :settlement-service:test --tests "*SettlementDbBootIT*"`
Expected: PASS (settlement_db 에 settlement 스키마가 order 테이블 없이 완결적으로 선다 = 물리 분리 가능 증거)

- [ ] **Step 4: Commit**
```bash
git add settlement-service/src/main/resources/db/migration settlement-service/src/test/.../SettlementDbBootIT.java
git commit -m "feat(settlement): own Flyway schema for settlement_db (no cross-domain FK)"
```

---

## Chunk 2: datasource 분리

### Task 2.1: settlement-service datasource → settlement_db

**Files:**
- Modify: `settlement-service/src/main/resources/application.yml`

- [ ] **Step 1: datasource/flyway 전환**

```yaml
spring:
  datasource:
    url: ${SETTLEMENT_DATASOURCE_URL:jdbc:postgresql://localhost:5432/settlement_db}
  flyway:
    enabled: true
    locations: classpath:db/migration   # 이제 settlement-service 자체 마이그레이션
    # schemas/default-schema 는 Chunk1 DDL 과 일치
  jpa:
    hibernate:
      ddl-auto: validate
```
(order 와 공유하던 opslab URL 제거. settlement 는 더 이상 order 마이그레이션에 의존하지 않는다.)

- [ ] **Step 2: 부팅 검증** — SettlementDbBootIT 가 새 datasource 로 통과(Chunk1 에서 이미 커버). `./gradlew :settlement-service:build` 그린.

- [ ] **Step 3: Commit** `git commit -m "feat(settlement): point datasource at settlement_db"`

---

## Chunk 3: 프로젝션 백필 (기존 order 데이터 시드)

> 프로젝션(payment/order/user/product_view)은 **신규 이벤트만** 채운다. 분리 컷오버 시점에 기존 order 데이터가 settlement_db 에 없으므로, order-service 가 기존 행을 이벤트로 **재발행**해 시드한다(reservation 의 기사 프로젝션 백필과 동형, 멱등).

### Task 3.1: order-service 백필 엔드포인트

**Files:**
- Create: `order-service/.../user|order|product|payment` 도메인에 `findAll`/스트리밍 조회 포트(없으면)
- Create: `order-service/.../application/service/SettlementProjectionBackfillService.java`
- Create: `order-service/.../adapter/in/web/SettlementBackfillAdminController.java` (`POST /admin/settlement-projection/backfill`, ADMIN)
- Modify: `shared-common/.../SecurityConfig.java` (`/admin/settlement-projection/**` hasRole ADMIN)

- [ ] **Step 1: 실패 테스트** — 백필 서비스가 전 payments/orders/users/products 에 대해 각 Publish*EventPort 를 호출하는지(건수) 단위테스트(mock).
- [ ] **Step 2: 구현** — 각 도메인 findAll → publishPaymentCaptured/publishOrderCreated/publishUserRegistered/publishProductChanged 재발행. 멱등(컨슈머 processed_events + upsert)이라 재실행 안전. 대량 시 페이지네이션.
- [ ] **Step 3: 테스트 통과 + Commit**

### Task 3.2: 컷오버 순서 문서화 (운영 절차, 코드 아님)
- [ ] settlement-service 를 settlement_db 로 배포 → 백필 호출(프로젝션 시드) → 신규 이벤트 정상 흐름 확인. 백필 전 트래픽 컷오버 금지(빈 프로젝션 → 조회 공백).

---

## Chunk 4: 인프라 + order 정리 + 운영 데이터 이관

### Task 4.1: docker-compose / k8s settlement-db
**Files:** `docker-compose.yml`, `k8s/base/settlement-*.yaml`
- [ ] settlement-db(Postgres) 서비스 + `SETTLEMENT_DATASOURCE_URL` 주입 (reservation/loan compose 패턴 복제). 검증: `docker compose config -q`.

### Task 4.2: order-service 에서 settlement DDL 제거 (운영 데이터 이관 **후**)
- [ ] 운영 opslab → settlement_db 정산 데이터 1회성 ETL(pg_dump 특정 테이블 / INSERT SELECT dblink) 완료 후, order-service 의 settlement-소유 테이블 CREATE 구문을 신규 마이그레이션으로 DROP 하거나 더 이상 적용되지 않게 정리. **빅뱅 금지**: 병렬 운영 + 대사 일치 확인 후 진행.
- [ ] LemuelApplication/PersistenceConfig 에 settlement 잔재 스캔 없는지 재확인(이미 standalone).

---

## 리스크 & 주의

- **운영 데이터 이관(ETL)이 최대 난관**: 신규 스키마 분리는 자동화되나, 기존 정산/원장 데이터를 settlement_db 로 무손실 이관하는 것은 금융 데이터라 병렬 운영 + 대사(ADR 0007 3불변식) 통과를 게이트로 둔다. 빅뱅 컷오버 금지.
- **outbox/processed_events 이중화**: settlement_db 에도 outbox_events(SettlementCreated 발행)·processed_events(payment/order/user/product 소비 멱등) 테이블이 필요. order 의 것과 별개 인스턴스. 누락 시 발행/멱등 깨짐.
- **백필 누락 시 조회 공백**: 프로젝션은 신규 이벤트만 채우므로, 컷오버 전 백필 필수. 순서: 배포→백필→트래픽 전환.
- **교차 FK 잔재**: V2 외 다른 마이그레이션의 settlements/payouts/chargebacks/ledger 가 order 테이블을 REFERENCES 하면 모두 드롭. 실행 시 `grep REFERENCES` 전수.
- **settlement_loan_deductions 소유권**: loan 연동 테이블 — settlement 와 loan 중 누가 소유하는지 확인(현재 opslab). 잘못 이관 시 loan 깨짐.
- **Flyway 히스토리**: settlement 가 자체 히스토리(settlement_db.flyway_schema_history)를 갖게 됨. opslab 의 기존 settlement 마이그레이션 적용 기록과 무관.

---

## 실행 핸드오프

Chunk 경계(1~4)마다 빌드 그린 확인 + 중간 점검. Chunk 1(자체 스키마)·2(datasource)는 Testcontainers 로 완전 검증 가능. Chunk 3(백필)은 단위테스트. Chunk 4(인프라/운영 이관)는 실 인프라 + 데이터가 있어야 e2e 검증되므로, 코드/설정까지 작성하고 운영 적용은 절차로 인계한다. subagent 가능 시 superpowers:subagent-driven-development 로 Task 단위 실행.
