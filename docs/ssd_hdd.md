# DB 디스크 I/O 관점 — SSD vs HDD 비교 분석

이 프로젝트(PostgreSQL 17 중심)에서 **DB가 실제로 디스크를 만지는 지점**을 찾아, 각각이
**랜덤 I/O인지 순차 I/O인지** 분류하고 **SSD/HDD 중 무엇이 유리한지**를 코드 근거로 분석한다.

> 핵심 결론: 이 시스템의 주력 워크로드는 **OLTP(랜덤 읽기/쓰기 + 빈번한 커밋 fsync)** 라
> **SSD(특히 NVMe)가 압도적으로 유리**하다. HDD가 그나마 버틸 수 있는 곳은 정산 배치·PG 대사·
> 리포트 같은 **대량 순차 스캔/적재** 경로뿐이다.

---

## 1. 먼저: DB는 언제 디스크를 만지는가

애플리케이션 코드는 디스크를 직접 모른다. 모든 디스크 접근은 PostgreSQL을 통해 일어난다.
디스크를 만지는 순간은 크게 5가지다.

| 디스크 접근 | I/O 성격 | 지연 민감도 |
|-------------|----------|-------------|
| ① 인덱스 탐색 → 힙(heap) 페이지 fetch | **랜덤 읽기** | 매우 높음 |
| ② WAL(트랜잭션 로그) 기록 + commit 시 `fsync` | 순차 쓰기 + **fsync 지연** | 매우 높음 |
| ③ 체크포인트 — 더티 버퍼를 데이터 파일로 flush | **랜덤 쓰기** | 중간(배경) |
| ④ Seq Scan(풀스캔)·대량 적재 | **순차 읽기/쓰기** | 낮음(처리량 관점) |
| ⑤ 임시 파일(대용량 정렬/해시) | 혼합 | 중간 |

> 단, 데이터가 `shared_buffers`/OS 페이지 캐시에 있으면 디스크를 안 만진다. 디스크가 문제되는
> 건 **캐시 미스(작업셋 > RAM)** 와 **모든 commit의 WAL fsync** 다.

### 랜덤 I/O vs 순차 I/O가 SSD/HDD에서 갈리는 이유

- **HDD**: 헤드가 물리적으로 이동(seek ~5~15ms) → **랜덤 IOPS가 100~200 수준**으로 처참. 단
  순차 처리량은 100~200MB/s로 쓸 만함.
- **SATA SSD**: seek 없음 → 랜덤 IOPS 수만, 지연 ~0.1ms.
- **NVMe SSD**: 랜덤 IOPS 수십만, 지연 ~0.02ms, 순차 수 GB/s.

→ **랜덤 I/O가 많을수록 SSD/HDD 격차가 수백 배**로 벌어진다. OLTP DB가 SSD를 깔아야 하는 이유.

---

## 2. SSD vs HDD 특성 비교 (이 프로젝트 관점)

| 항목 | HDD | SATA SSD | NVMe SSD |
|------|-----|----------|----------|
| 랜덤 4K IOPS | 100~200 | 5만~10만 | 수십만 |
| 랜덤 접근 지연 | 5~15ms | ~0.1ms | ~0.02ms |
| 순차 처리량 | 100~200MB/s | ~500MB/s | 3~7GB/s |
| commit `fsync` 지연 | 높음(회전 대기) | 낮음 | 매우 낮음 |
| 동시 요청(QD) 확장 | 나쁨 | 좋음 | 매우 좋음 |
| 가격/용량 | 가장 저렴 | 중간 | 비쌈 |

이 프로젝트는 **가상 스레드 + PgBouncer 트랜잭션 풀링**으로 **다수의 짧은 트랜잭션이 동시에**
몰리는 구조(`docker-compose.yml` PgBouncer `POOL_MODE=transaction`). 동시 commit이 많을수록
**WAL fsync 지연**과 **랜덤 I/O 큐 깊이**가 병목 → HDD면 여기서 먼저 무너진다.

---

## 3. 이 프로젝트의 디스크 접근 패턴 분류 (코드 근거)

### 3.1 🔴 랜덤 I/O 핫스팟 — SSD 강력 권장

#### (1) 인덱스 기반 조회 = 인덱스 페이지 + 힙 페이지 랜덤 읽기

마이그레이션이 거의 모든 조회 경로에 B-tree 인덱스를 깐다. 인덱스 탐색 자체가 랜덤이고,
인덱스로 찾은 행을 힙에서 가져오는 것도 랜덤이다.

- 로그인: `idx_users_email` (`V22__performance_indexes.sql:26`) — 매 로그인마다 랜덤 probe
- 주문↔결제 조인: `idx_payments_order_id` (`V22:14`)
- 주문 조회: `idx_orders_user_id_status` (`V22:6`)
- 정산 조회/배치 필터: `idx_settlements_date_status` (`V22:18`)

> 풀스캔을 일부러 인덱스로 바꾼 흔적이 명시적이다 — `V20260611100000__add_missing_query_indexes.sql`
> 의 주석 *"인덱스가 없어 Seq Scan 또는 불필요한 정렬이 발생하는 4개 지점 보강"*. 즉 설계가
> **순차 스캔 → 랜덤 인덱스 스캔으로 전환**하는 방향이라, 그만큼 디스크는 SSD를 전제로 한다.

#### (2) ★ Outbox 폴러 — 2초마다 도는 랜덤 읽기 + 갱신 (가장 디스크 압박이 꾸준한 곳)

```sql
-- V20260611110000__outbox_claim_columns.sql
CREATE INDEX idx_outbox_pending_claim
    ON opslab.outbox_events (created_at, claimed_at) WHERE status = 'PENDING';
```

- 폴러가 2초 주기(`app.outbox.polling-delay-ms:2000`)로 `SELECT ... FOR UPDATE SKIP LOCKED`
  → 부분 인덱스로 PENDING 행을 랜덤하게 집어온다.
- claim 시 `claimed_at` UPDATE → **힙 페이지 랜덤 쓰기 + WAL append**.
- 발행 후 `PUBLISHED` UPDATE → 또 랜덤 쓰기. 이 테이블은 **삽입·갱신·삭제가 계속 도는 핫 테이블**
  이라 더티 페이지·VACUUM·인덱스 갱신이 끊임없다 → 랜덤 I/O 지속 발생.
- 멀티워커(`OutboxPublisherScheduler`)로 여러 인스턴스가 동시에 폴링 → 동시 랜덤 I/O 큐 깊이↑
  → **SSD의 동시성 이점이 그대로 처리량으로 직결**.

> `ledger`도 동일하게 별도 폴러(5초 주기, `LedgerOutboxPoller`)가 있어 같은 압박이 한 벌 더 있다.

#### (3) 멱등성 3단 방어 = 삽입마다 UNIQUE 인덱스 랜덤 probe

결제→정산 파이프라인의 중복 방지(아래 3곳)는 **모두 B-tree 유니크 인덱스 조회**다. INSERT 때마다
키 위치를 랜덤 탐색해 충돌 검사한다.

1. `uq_outbox_event_id` (`V28__create_outbox_events.sql:30`)
2. `processed_events PK (consumer_group, event_id)` (`V29`)
3. `settlements.payment_id UNIQUE`

#### (4) 환불 비관적 락 — 행 잠금 위한 랜덤 읽기

환불은 Pessimistic Lock(`SELECT ... FOR UPDATE`) + Idempotency-Key (CLAUDE.md 보안 표).
대상 행을 랜덤하게 읽고 잠근 뒤 갱신 → 랜덤 읽기+쓰기, 지연 민감.

#### (5) 체크포인트 — 더티 버퍼 flush (배경 랜덤 쓰기)

위 (2)(3)(4)가 만든 더티 페이지를 PostgreSQL이 주기적으로 데이터 파일에 흩어 쓴다(랜덤 쓰기).
쓰기량이 많은 워크로드라 SSD가 체크포인트 spike를 잘 흡수한다.

### 3.2 🟢 순차 I/O 경로 — HDD도 그나마 버티는 곳

#### (1) 일일 정산 배치 — 대량 INSERT (순차 append)

매일 새벽 2시(`SettlementScheduler`) 전일 결제 전 건을 정산으로 적재. compose에서
`reWriteBatchedInserts=true`(`docker-compose.yml:138`)로 **배치 INSERT를 단일 multi-row 문장
으로 재작성** → 힙 끝에 순차 append + WAL 순차 기록. 처리량 관점이라 HDD도 어느 정도 소화.

> 단, 같은 트랜잭션에서 위 3.1(3)의 `payment_id UNIQUE` 랜덤 probe가 건마다 끼므로 순수 순차는
> 아니다. 대량이면 결국 SSD가 안전.

#### (2) PG 대사 — 대량 행 적재/조회

`InternalPaymentsForReconJdbcAdapter`가 기간 내부 결제를 대량 조회(범위 스캔), 불일치
(`pg_reconciliation_discrepancies`) 대량 INSERT. 대사 실행은 가끔(운영자 트리거)이고 대량 순차라
HDD 허용 범위.

#### (3) Cashflow 리포트 집계 — SUM 범위 스캔

`GenerateCashflowReportService`의 대사 3종은 `LoadPeriodReconciliationPort.sum*()`로 **DB SUM
집계에 위임**(인메모리 아님). 기간이 넓으면 인덱스 범위 스캔 또는 Seq Scan + 집계 → 순차성↑.
드물게 실행되므로 HDD여도 치명적이지 않음.

#### (4) WAL 기록 — 순차이지만 fsync 지연이 변수

WAL은 append-only 순차 쓰기지만, **모든 commit이 fsync로 디스크 동기화를 기다린다.** OLTP라
commit이 초당 수백~수천 건이면, "순차"임에도 **fsync 지연이 곧 commit 처리량 상한**이 된다.
→ 이 지점은 순차여도 **SSD(낮은 fsync 지연)가 결정적**. HDD면 commit TPS가 회전 지연에 묶인다.

#### (5) Flyway 마이그레이션 / 시드 데이터

`V1~V50` + timestamp 마이그레이션, `V17/V21` 시드는 일회성 순차 작업 → 디스크 종류 영향 미미.

---

## 4. 시나리오별 SSD vs HDD 영향

| 시나리오 | 지배적 I/O | HDD 영향 | SSD 효과 |
|----------|-----------|----------|----------|
| 로그인(이메일 조회 + BCrypt) | 인덱스 랜덤 읽기 | 동시 로그인 시 seek 병목 | 랜덤 IOPS로 해소 |
| 주문→결제→Outbox INSERT | 랜덤 쓰기 + UNIQUE probe + WAL fsync | **commit 지연 심각** | fsync·랜덤 모두 유리 |
| Outbox 폴링(2초·멀티워커) | 부분 인덱스 랜덤 읽기 + UPDATE | 상시 seek 부하 | 동시성·지연 모두 유리 |
| 정산 일일 배치 | 대량 순차 INSERT(+UNIQUE probe) | 처리량은 버팀, probe가 발목 | 안정적 |
| PG 대사 / 캐시플로우 리포트 | 범위 스캔 + 대량 INSERT | **HDD 허용 가능** | 더 빠르지만 필수까진 아님 |
| 환불(비관적 락) | 행 단위 랜덤 읽기/쓰기 | 락 보유시간↑ → 경합 악화 | 락 보유시간↓ |

---

## 5. 권장 — 어디에 SSD를 깔 것인가

### 5.1 우선순위

1. **WAL(`pg_wal`)을 가장 빠른 디스크에** — 모든 commit의 fsync가 여기 묶인다. NVMe 1순위.
2. **데이터 파일(주요 OLTP 테이블)도 SSD** — `payments`, `orders`, `settlements`,
   `outbox_events`, `processed_events`는 랜덤 I/O 핫스팟이라 SSD 필수.
3. **HDD 허용 후보(비용 절감 시)**: 콜드 데이터 — 오래된 `audit_logs`, 종료된
   `pg_reconciliation_*`, 보관용 정산 이력. 접근 빈도가 낮고 순차적.

### 5.2 테이블스페이스 분리 아이디어 (선택)

PostgreSQL `TABLESPACE`로 핫/콜드를 물리 분리 가능:
- 핫(SSD): outbox_events, payments, orders, settlements, ledger_entries, users
- 콜드(HDD): audit_logs(과거 파티션), 종료된 대사 런/불일치, 아카이브

> 단 운영 복잡도가 오르므로, 단일 SSD로 충분하면 굳이 나누지 않는 게 낫다. 본 프로젝트 규모(포트
> 폴리오)에선 **전체 SSD(가능하면 NVMe) 단일 볼륨**이 가장 단순하고 안전한 선택.

### 5.3 디스크 외 완충 장치 (이미 적용/적용 가능)

디스크 부담 자체를 줄이는 레버도 함께 본다.

- **메모리 캐시**: 작업셋 < RAM이면 디스크를 거의 안 만짐. `shared_buffers` 충분히.
- **애플리케이션 캐시**: Caffeine L1 / 2-tier(L2 Redis) — 상품·카테고리 조회를 DB에서 흡수
  (`docs/tps.md` ⑦). 읽기 랜덤 I/O를 줄임.
- **Read Replica 라우팅**(opt-in, `tps.md` ②): 읽기를 레플리카로 오프로드 → 프라이머리 디스크
  랜덤 읽기 분산.
- **배치 쓰기**(`reWriteBatchedInserts`, `jdbc.batch_size`): 쓰기를 순차화해 WAL/힙 효율↑.

---

## 6. 검증 방법 (정적 분석이므로 실측 권장)

본 문서는 코드/마이그레이션 기반 추론이다. 실제 디스크 병목은 아래로 측정:

- **PostgreSQL**: `EXPLAIN (ANALYZE, BUFFERS)` 로 쿼리별 `shared read`(디스크 읽기) 확인,
  `pg_stat_io`(PG16+)·`pg_statio_user_tables`로 힙/인덱스 디스크 읽기 비율,
  `pg_stat_bgwriter`로 체크포인트 쓰기량.
- **WAL/commit**: `pg_stat_wal`, commit 지연(`pg_stat_database.xact_commit` 추이) 관찰.
- **OS**: `iostat -x` 의 `r_await`/`w_await`(지연), `%util`, `aqu-sz`(큐 깊이) — HDD면 await가
  ms 단위로 치솟음.
- **부하 시나리오**: 로그인 폭주, 결제→Outbox 발행, Outbox 폴링 동시성 위주로 구성하면 랜덤
  I/O 병목이 드러난다.

---

## 7. 한 줄 요약

> 이 프로젝트는 **인덱스 랜덤 조회 + 빈번한 Outbox/멱등 UNIQUE probe + 모든 commit의 WAL
> fsync** 가 디스크를 지배하는 **전형적 OLTP**다. → **데이터/ WAL은 SSD(가급적 NVMe) 필수**,
> HDD는 정산 배치·대사·리포트·아카이브 같은 **순차/콜드 경로에만 제한적으로 허용**한다.

> 관련 문서: 처리량 레버는 `docs/tps.md`, CPU 핫스팟은 `docs/cpu.md`, 인덱스 보강 근거는
> `V22__performance_indexes.sql` · `V20260611100000__add_missing_query_indexes.sql`.
