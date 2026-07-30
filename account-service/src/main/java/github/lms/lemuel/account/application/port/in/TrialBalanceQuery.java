package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 전사 시산표 조회 역할 (원장 정합 축).
 *
 * <p>{@link AccountQueryUseCase} 의 응집 축 중 하나. 시산표만 검증하는 소비처는 이 역할만 의존하면
 * 된다(ISP).
 */
public interface TrialBalanceQuery {

    /** 전사 시산표(전체 기간). */
    TrialBalance trialBalance();

    /** 기간 확정 시산표 — occurred_at 반개구간 [from, to). */
    TrialBalance trialBalance(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /**
     * 통제계정 대사(ADR 0026 Option ①) — GL 측 세 통제계정의 정상방향 순잔액을 노출한다. 이 값은 각각
     * 서브원장 Σ 와 일치해야 한다: {@code sellerPayable}=Σ미지급 immediate, {@code holdbackPayable}=Σ미해제
     * holdback, {@code recoveryReceivable}=Σ OPEN SellerRecovery. (서브원장 측 합계는 settlement_db 소관이라
     * MSA 경계상 account 는 GL 측 절반만 제공한다 — 외부 대사가 양측을 대조한다.) {@code balanced} 는 세
     * 순잔액이 모두 0 인 전역 폐루프 여부(방어값).
     */
    ControlRecon controlRecon();

    /**
     * 실체화 잔액 대사(ADR 0030 Phase 3) — {@code account_balances}(파생 캐시)를 원장 재합산(정답지)과
     * 전 (owner, account) 쌍에 대해 대조한다. 드리프트가 있으면 캐시가 오염된 것이고, payout 분할
     * 라우팅이 틀린 잔액으로 판단하고 있다는 신호다 — 정기 배치가 이 값을 Prometheus 게이지로 노출한다.
     */
    BalanceRecon balanceRecon();

    /**
     * 통제계정 GL 순잔액(정상방향) + 실체화 잔액 대사(ADR 0030 Phase 3 확장).
     */
    record ControlRecon(BigDecimal sellerPayable, BigDecimal holdbackPayable, BigDecimal recoveryReceivable,
                        BalanceRecon materializedRecon) {
        /**
         * 세 통제계정 순잔액이 모두 0(전역 완전정산 폐루프) — <b>원장 축만 본다</b>.
         * 파생 캐시 축(실체화 잔액 드리프트)은 {@link #materializedRecon} 이 별도로 판정하므로,
         * 종합 판정에는 {@link #healthy()} 를 써라(감사 MED-2 — balanced 만 보면 캐시 오염을 놓친다).
         */
        public boolean balanced() {
            return sellerPayable.signum() == 0
                    && holdbackPayable.signum() == 0
                    && recoveryReceivable.signum() == 0;
        }

        /** 종합 판정 — 원장 폐루프({@link #balanced()}) ∧ 파생 캐시 정합({@code materializedRecon.consistent()}). */
        public boolean healthy() {
            return balanced() && materializedRecon.consistent();
        }
    }

    /**
     * 실체화 잔액 대사 결과.
     *
     * @param checkedPairs 대조한 (owner, account) 쌍 총수(재합산·실체화 합집합)
     * @param driftCount   드리프트 쌍 수 — 0 이 아니면 파생 캐시 오염
     * @param drifts       드리프트 상위 목록(|델타| 내림차순, 상한 캡) — 전량이 아닐 수 있다
     */
    record BalanceRecon(long checkedPairs, long driftCount, List<BalanceDrift> drifts) {
        /** 실체화 == 원장 재합산 (드리프트 0). */
        public boolean consistent() {
            return driftCount == 0;
        }
    }

    /** 드리프트 1건 — 델타는 저장하지 않고 파생한다(모순된 값 조합의 생성 자체를 차단, money-safety). */
    record BalanceDrift(OwnerType ownerType, String ownerId, GlAccount account,
                        BigDecimal materialized, BigDecimal recomputed) {

        /** 실체화 − 정답지(재합산). 0 이 아니면 캐시 오염. */
        public BigDecimal delta() {
            return materialized.subtract(recomputed);
        }
    }
}
