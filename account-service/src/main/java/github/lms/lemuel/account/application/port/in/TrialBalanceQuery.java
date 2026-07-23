package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.TrialBalance;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    /** 통제계정 GL 순잔액(정상방향). */
    record ControlRecon(BigDecimal sellerPayable, BigDecimal holdbackPayable, BigDecimal recoveryReceivable) {
        /** 세 통제계정 순잔액이 모두 0(전역 완전정산 폐루프). */
        public boolean balanced() {
            return sellerPayable.signum() == 0
                    && holdbackPayable.signum() == 0
                    && recoveryReceivable.signum() == 0;
        }
    }
}
