# 레거시 → 이벤트 기반 MSA 전환 케이스 스터디

> 같은 **주문·결제·정산** 도메인을 두 방식으로 구현한 before/after 비교.
> **Before**: ssgb2e (Shared-DB · MyBatis 모놀리식 3-앱) — 실무 레거시.
> **After**: Lemuel settlement (헥사고날 · 이벤트 기반 MSA) — 본 프로젝트.
>
> Before 측 수치는 ssgb2e 3개 앱(`api` / `backoffice` / `quartz`)의 MyBatis 매퍼 93개를
> 정적 파싱해 산출했다(스냅샷: `ssgb2e-api_20250721`, `ssgb2e-backoffice-final`, `ssgb2e-quartz-final`).

---

## 1. 한눈에 보는 대조

| 축 | ssgb2e (레거시) | Lemuel settlement (현재) |
|----|----------------|--------------------------|
| 통합 방식 | **Shared-DB** — 3앱이 211개 테이블 직접 R/W | **Bounded Context** — DB·코드 경계 분리 |
| 서비스 간 데이터 | 같은 `tbl_*`에 직접 SQL | **Read-only Projection**(`@Immutable`) + **Kafka 이벤트** |
| 결합도 | 19테이블 3앱 공유, 67테이블 다중 WRITE | settlement→order import **0** (ArchUnit 강제) |
| 동시성 방어 | ❌ `FOR UPDATE` **0건**, read-then-write 이중지불 창 | ✅ Pessimistic Lock + Idempotency-Key |
| 멱등성 | ❌ 없음 (배치 재실행 시 중복 차감 가능) | ✅ **3단 방어** (outbox·processed_events·UNIQUE) |
| 잔액 정합성 | 원장 + 집계 **수기 이중관리** → 드리프트 | 복식부기 원장 (PENDING→POSTED→REVERSED) |
| 아키텍처 | MyBatis DAO, 레이어 혼재 | 헥사고날 (Ports & Adapters), 도메인 POJO |
| 데이터 접근 | SQL 매퍼 직접 | JPA + 도메인 모델 |

---

## 2. Before — ssgb2e의 구조적 통증

### 2.1 Shared-DB 강결합
3개 애플리케이션이 **동일한 `kr.co.dodoom` 패키지 + 단일 Oracle DB**를 공유하고,
각 앱이 자기 MyBatis 매퍼로 같은 물리 테이블을 직접 SQL로 찌른다.

| 모듈 | 성격 | 접근 테이블 수 |
|------|------|---------------|
| `api` | 외부 연동/주문수집 (Sabangnet·GMS·EasyPay) | 68 |
| `backoffice` | 관리자 마스터 (사실상 전 도메인) | 202 |
| `quartz` | 배치/스케줄러 | 50 |

- **총 물리 테이블 211개**
- **3개 앱 공유 핵심 19개** — `tbl_orderinfo`, `tbl_orderplist`, `tbl_order_history`,
  `tbl_order_optlist`, `tbl_order_gms`, `tbl_offlinemall_orderinfo`, `tbl_member`,
  `tbl_sellmember`, `tbl_ptnmember`, `tbl_product`, `tbl_couponbank` 등.
  → 컬럼 하나만 바꿔도 3개 앱이 동시에 깨지는 구조.
- **2개 이상 앱이 동시 WRITE: 67개** — 쓰기 정합성/락 충돌 위험 구간.

> 전체 카탈로그와 R/W 매트릭스는 분석 산출물 `ssgb2e-db-catalog.md` 참조.

### 2.2 락 없는 이중지불(double-spend) 창
포인트/마일리지 잔액이 **두 곳에 중복 저장**된다.

```
tbl_pointbank      : 원장(ledger). 적립/사용 건마다 1 row, balancepoint 컬럼 (FIFO 만료)
tbl_pointbankv     : 집계 스냅샷. 회원+파트너당 1 row, point 컬럼에 잔액 총합 (수기 비정규화)
tbl_pointbank_use_log : 사용 이력
(마일리지도 mileagebank / mileagebankv / mileagebank_use_log 로 동일 구조)
```

차감 흐름:
```
1. selectBalancePointList  → 만료순(FIFO) 잔액 row들을 락 없이 SELECT
2. (앱 계층) 각 row에서 차감할 금액 계산
3. updatePoint            → row별로 balancepoint 차감
```

문제:
- **`FOR UPDATE` 전무** — 93개 매퍼 전수 검사 결과 비관적 락 0건 (`default_lock`은 컬럼명 오탐).
- **낙관적 락도 없음** — version/`WHERE balance=old` 조건 부재.
- 단일 UPDATE는 `SET balancepoint = balancepoint - #{x}` 로 컬럼 산술은 원자적이지만,
  **"잔액 이상 못 쓴다"는 비즈니스 불변식은 보호하지 못함** (음수 가드 `balancepoint >= x` 없음).
- 1~3 사이 락이 없어, 동시에 들어온 두 주문/환불이 같은 잔액을 읽고 둘 다 "충분" 판단 →
  **둘 다 차감 = 초과 사용**.

### 2.3 원장↔집계 드리프트
`pointbank`(원장) · `pointbankv`(집계) · `use_log`가 **별개 SQL**로 갱신된다.
한 트랜잭션으로 안 묶이면 중간 실패 시 `SUM(balancepoint) ≠ pointbankv.point`.
(MyBatis 매퍼엔 트랜잭션 경계가 없어 서비스 계층 `@Transactional` 여부에 전적으로 의존)

### 2.4 배치 vs 온라인 경쟁
자정 `quartz` 만료 배치가 잔액을 차감하는 동안 `backoffice` 관리자가 같은 회원 잔액을
조정 → 인터리브. 쿠폰 만료도 `insertCouponNotUseLog`(스냅샷) → `deleteCouponNotUse`(삭제)
2-스텝 사이에 사용자가 쿠폰을 쓰면 로그/삭제 대상 불일치.

---

## 3. After — Lemuel settlement의 해법

### 3.1 코드 경계 분리 (Shared-DB → Bounded Context)
settlement-service는 order-service를 **import 하지 않고** Order/Payment/User/Product
데이터를 조회한다. `@Immutable` JPA 엔티티로 같은 테이블을 read-only 매핑하는
**Read-only Projection 패턴**.

```
settlement-service/.../adapter/out/readmodel/
├── SettlementPaymentReadModel    (payments 테이블 read-only)
├── SettlementOrderReadModel      (orders 테이블)
├── SettlementUserReadModel       (users 테이블, email만)
└── SettlementProductReadModel    (products 테이블, name만)
```

→ `settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` **없음**.
→ ArchUnit 으로 패키지 의존 방향 검증, MSA 코드 경계 100% 확립.
→ ssgb2e의 "67개 테이블 다중 WRITE" 강결합과 정반대.

### 3.2 이벤트 기반 비동기 정산 (Outbox + Kafka)
```
[order-service] Payment.capture() (DB tx)
    ├─ payments.status = CAPTURED
    └─ outbox_events INSERT (PaymentCaptured)   ← 도메인 tx와 이벤트 발행 원자성
                     ↓ (poller 주기)
                 Kafka: lemuel.payment.captured
                     ↓
[settlement-service] PaymentEventKafkaConsumer → Settlement.createFromPayment() (DB tx)
```

### 3.3 동시성·멱등성 3단 방어 (ssgb2e의 이중지불 창에 대한 직접 응답)
- **환불 동시성**: Pessimistic Lock + Idempotency-Key (`refunds` unique).
- **3단 멱등**:
  1. `outbox_events.event_id` UUID UNIQUE — 프로듀서 중복 발행 방지
  2. `processed_events (consumer_group, event_id)` PK — 컨슈머 재수신 방지
  3. `settlements.payment_id` UNIQUE — 스키마 최종 방어
- ssgb2e의 "배치 재실행 시 중복 차감"을 구조적으로 차단.

### 3.4 복식부기 원장 (수기 집계 → 검증 가능한 원장)
`ledger` 도메인이 PENDING → POSTED → REVERSED 상태로 차변/대변을 기록.
ssgb2e의 `pointbank`+`pointbankv` 수기 이중관리 드리프트를, 차대 합계로 검증 가능한
복식부기로 대체.

---

## 4. 포트폴리오 핵심 메시지

1. **Shared-DB 강결합 → 이벤트 기반 경계 분리**
   "211개 테이블 중 19개를 3개 앱이 공유, 67개에 동시 쓰기, `FOR UPDATE` 0건"이라는
   구체적 통증 지표 → Read-only Projection + Outbox/Kafka로 **서비스 간 코드 의존 0** 달성.

2. **락 없는 이중지불 → 비관적 락 + 멱등키 + 복식부기**
   레거시 `pointbank` read-then-write 창을 정확히 짚고, settlement 환불 흐름에서
   Pessimistic Lock + 3단 멱등으로 차단.

3. **수기 집계 드리프트 → 검증 가능한 복식부기 원장**
   `SUM(ledger) ≠ snapshot` 위험을 차대 일치로 구조적으로 제거.

---

## 부록 — 분석 방법

- 대상 리포: `ssgb2e-api_20250721`, `ssgb2e-backoffice-final`, `ssgb2e-quartz-final` (private).
- 방법: GitHub API로 매퍼 XML 93개 수집 → 정규식으로 `from/join`(=READ),
  `insert into/update/delete from`(=WRITE) 뒤 `tbl_*` 토큰 추출 → 테이블×앱 R/W 매트릭스 구성.
- 한계: 정적 SQL 파싱이므로 동적 `<sql>` 조각/문자열 연결 테이블명은 일부 누락 가능.
  트랜잭션 경계·락은 매퍼가 아닌 서비스 계층 코드 확인이 필요(본 분석은 매퍼 레벨 근거).
