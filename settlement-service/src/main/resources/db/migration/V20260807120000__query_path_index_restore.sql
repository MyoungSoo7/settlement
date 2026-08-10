-- V20260807120000: 핫패스 쿼리 인덱스 복원 — settlement_db 베이스라인 유실분 (DB 성능 감사 ①)
--
-- 배경: V1 베이스라인은 Hibernate DDL export 라 opslab(order-service)이 누적한 성능 인덱스가
--   통째로 유실된 채 출발했다. 그 결과 리포지토리 Javadoc(SettlementQueryRepositoryImpl :45·:109·:220)이
--   "활용한다"고 명시한 인덱스가 settlement_db 에는 존재하지 않았다 — 커서 페이징·커버링 집계 구현은
--   완벽한데 받쳐 줄 인덱스가 없어 전부 Seq Scan 이던 상태. 각 인덱스는 실존 쿼리 경로와 1:1 대응이며,
--   존재 계약은 QueryPathIndexContractIT 가 pg_indexes 로 강제한다(재유실 시 빌드 실패).
--
-- 정본 대조: order-service V23__add_settlement_query_indexes / V43__payouts /
--   V20260611100000__add_missing_query_indexes / V20260715200004__prune_redundant_indexes.
--   단순 이식이 아니라 settlement-service 의 실제 쿼리 컬럼으로 재검증했고, 집계가 읽는 컬럼을
--   INCLUDE 에 실어 Index-Only Scan 이 성립하도록 개선했다(아래 각 주석).
--
-- 의도적 미복원:
--   · settlements(payment_id) INCLUDE(...) — uk_settlements_payment_id(UNIQUE)가 등가 조회를 이미 커버.
--   · pg_reconciliation_runs(started_at) — 일 단위 run 은 수백 행/년, 인덱스 유지비용 > 효익.

-- ───────────────────────── settlements ─────────────────────────

-- 커서 페이징 (SettlementQueryRepositoryImpl.searchSettlements 기본 정렬
--   ORDER BY settlement_date DESC, id DESC + cursorCondition 튜플 비교,
--   findReconciliationMismatches 동일 정렬). ASC 방향은 역방향 스캔으로 커버.
CREATE INDEX IF NOT EXISTS idx_settlements_date_id_desc
    ON public.settlements (settlement_date DESC, id DESC);

-- 일별/월별 요약·기간 집계 커버링 (findDailySummary/findMonthlySummary/getPaymentRefundAggregation,
--   CashflowAggregateQueryAdapter.aggregate — 모두 settlement_date 범위 + status 분기 + 4개 금액 SUM).
--   order V23 대비 refunded_amount 를 INCLUDE 에 추가 — 요약 쿼리가 refunded_amount 도 합산하므로
--   V23 정의로는 힙 방문이 남는다. 여기서는 4개 금액 전부 실어 Index-Only Scan 을 완성한다.
CREATE INDEX IF NOT EXISTS idx_settlements_date_status_amounts
    ON public.settlements (settlement_date, status)
    INCLUDE (payment_amount, refunded_amount, commission, net_amount);

-- 승인 상태 추적 (findByApprovalStatus — status IN 3종 필터 + ORDER BY id DESC 커서).
--   order 쪽은 V26 상태 정규화로 승인 partial 을 폐기했지만, settlement_db 는 승인 워크플로 읽기
--   경로가 실존한다(V20260715110100 status CHECK 3계층 주석 ②). 신규 기록은 코어 5종만 생성하므로
--   이 partial 은 사실상 크기 0 으로 유지된다.
CREATE INDEX IF NOT EXISTS idx_settlements_approval_id
    ON public.settlements (id)
    WHERE status IN ('WAITING_APPROVAL', 'APPROVED', 'REJECTED');

-- 생성일 기준 일일 대사 (DailyTotalsJdbcAdapter 3쿼리 + PeriodReconciliationJdbcAdapter
--   countSettlementsCreated — created_at 하루/기간 범위에서 COUNT·SUM(payment_amount)·
--   SUM(refunded_amount)). 두 금액을 INCLUDE 에 실어 대사 3쿼리 모두 Index-Only Scan.
--   ※ created_at::date 캐스트 술어는 인덱스를 못 타므로 범위 술어로의 재작성과 세트다(감사 ②).
CREATE INDEX IF NOT EXISTS idx_settlements_created_at
    ON public.settlements (created_at)
    INCLUDE (payment_amount, refunded_amount);

-- 프루닝: 단일컬럼 settlement_date 는 위 두 복합 인덱스의 선두열에 완전 포섭된다
--   (판단 기준은 order V20260715200004 · r4 팩과 동일 — "정확히 동일하거나 상위 복합에 완전
--   포함되는 것만 DROP". 선두열이 같으므로 단일컬럼 조회도 복합이 커버, 회귀 없음).
DROP INDEX IF EXISTS public.idx_settlements_settlement_date;

-- ───────────────────────── payouts ─────────────────────────

-- 셀러별 완료 지급 집계 (SpringDataPayoutRepository.sumCompletedBySellerBetween —
--   seller_id 등가 + completed_at 범위, COMPLETED 만). order V43 idx_payouts_seller_date 동형에
--   INCLUDE(amount) 추가 — SUM(amount) 이 유일한 읽기 컬럼이라 Index-Only Scan 성립.
CREATE INDEX IF NOT EXISTS idx_payouts_seller_date
    ON public.payouts (seller_id, completed_at)
    INCLUDE (amount)
    WHERE status = 'COMPLETED';

-- 기간 전체 완료 지급 집계 (sumCompletedBetween — seller 무관이라 위 인덱스의 선두 seller_id 를
--   못 쓴다. order V20260611100000 과 동일 근거의 별도 partial + INCLUDE(amount)).
CREATE INDEX IF NOT EXISTS idx_payouts_completed_at
    ON public.payouts (completed_at)
    INCLUDE (amount)
    WHERE status = 'COMPLETED';

-- ───────────────────────── ledger_entries ─────────────────────────

-- 시산표 (SpringDataLedgerJpaRepository.sumPostedDebitByAccount/sumPostedCreditByAccount —
--   settlement_date 범위 + status='POSTED' 필터 + 계정별 SUM(amount), findBySettlementDateBetween
--   도 선두열로 커버). order V20260715200004 (settlement_date, 계정) 동형에 INCLUDE(amount, status)
--   추가 — 시산표가 읽는 전 컬럼이 인덱스에 있어 Index-Only Scan. partial(WHERE POSTED)로 좁히지
--   않는 이유: findBySettlementDateBetween 은 상태 무관 조회라 partial 이면 못 탄다.
CREATE INDEX IF NOT EXISTS idx_ledger_date_debit
    ON public.ledger_entries (settlement_date, debit_account)
    INCLUDE (amount, status);
CREATE INDEX IF NOT EXISTS idx_ledger_date_credit
    ON public.ledger_entries (settlement_date, credit_account)
    INCLUDE (amount, status);

-- ─────────────────── settlement_adjustments ───────────────────

-- 조정일 스코프 (PeriodReconciliationJdbcAdapter.sumAdjustmentsAbsolute/sumRefundsLinkedToAdjustments,
--   IntegrityQueryJdbcAdapter missingReverse — adjustment_date 등가/범위). 조정은 예외 이벤트라
--   저볼륨이지만 대사·정합성 경로가 매일 전체 스캔하던 것을 범위 스캔으로 전환.
CREATE INDEX IF NOT EXISTS idx_adjustments_adjustment_date
    ON public.settlement_adjustments (adjustment_date);

-- ─────────────── settlement_payment_view (프로젝션) ───────────────

-- 셀러별 캐시플로 집계 (CashflowAggregateQueryAdapter.aggregateBySeller —
--   pv.seller_id 등가 필터 후 payment_id 로 settlements 조인). payment_id 를 두 번째 키로 실어
--   조인 피드가 Index-Only Scan 으로 나온다(힙 테이블이라 PK 도 인덱스에 자동 포함되지 않음).
CREATE INDEX IF NOT EXISTS idx_settlement_payment_view_seller
    ON public.settlement_payment_view (seller_id, payment_id);
