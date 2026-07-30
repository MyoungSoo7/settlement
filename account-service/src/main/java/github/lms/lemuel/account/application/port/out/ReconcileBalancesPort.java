package github.lms.lemuel.account.application.port.out;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 실체화 잔액(account_balances) ↔ 원장 재합산 대조 아웃바운드 포트 (ADR 0030 Phase 3).
 *
 * <p>정본은 원장(account_entries)이고 실체화 테이블은 파생 캐시다 — 캐시가 원장과 어긋나면
 * payout 분할 라우팅이 조용히 틀린 잔액으로 판단하므로, 이 대조가 이중 정본 드리프트의 유일한
 * 검출 수단이다. 재합산 식은 Phase 1 백필과 동일한 credit-positive(Σcredit − Σdebit)다.
 *
 * <p><b>스냅샷 일관성</b>: 드리프트 건수와 상세 목록은 단일 SQL 문장에서 나와야 한다 —
 * read_committed 에서는 문장마다 스냅샷이 갱신되므로, 쿼리를 쪼개면 "driftCount=1 인데
 * drifts=[]" 같은 자기모순 보고가 가능해진다(감사 MED-3).
 */
public interface ReconcileBalancesPort {

    /**
     * 전 (owner, account) 쌍 대조 스냅샷 — 드리프트 관련 값은 모두 같은 문장의 스냅샷에서 나온다.
     *
     * @param driftLimit 상세 목록 상한(|델타| 내림차순) — 건수 정본은 {@code driftCount}
     */
    BalanceReconSnapshot reconcileBalances(int driftLimit);

    /**
     * 대조 결과 스냅샷.
     *
     * @param checkedPairs 대조 대상 (owner, account) 쌍 총수(재합산·실체화 합집합)
     * @param driftCount   드리프트(실체화 ≠ 재합산) 쌍 수
     * @param drifts       드리프트 상위 목록(상한 캡) — {@code driftCount} 와 같은 스냅샷
     */
    record BalanceReconSnapshot(long checkedPairs, long driftCount, List<BalanceDriftRow> drifts) {
    }

    /**
     * 드리프트 1건 — 어느 owner 의 어느 계정이 얼마나 어긋났는지.
     *
     * @param materialized 실체화 잔액(행 부재 시 0)
     * @param recomputed   원장 재합산 잔액(정답지)
     */
    record BalanceDriftRow(OwnerType ownerType, String ownerId, GlAccount account,
                           BigDecimal materialized, BigDecimal recomputed) {

        /** 실체화 − 정답지. 0 이 아니면 캐시가 오염된 것이다(파생값 — 저장하지 않는다). */
        public BigDecimal delta() {
            return materialized.subtract(recomputed);
        }
    }
}
