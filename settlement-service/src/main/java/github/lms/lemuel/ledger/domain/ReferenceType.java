package github.lms.lemuel.ledger.domain;

/**
 * LedgerEntry 가 참조하는 원거래 종류.
 *
 * <p>{@code (reference_id, reference_type)} 쌍이 한 비즈니스 거래를 식별한다.
 */
public enum ReferenceType {
    SETTLEMENT,
    REFUND,
    /** 카드사 분쟁(Chargeback) ACCEPTED 에 따른 역분개의 원거래. reference_id = chargeback_id. */
    CHARGEBACK,
    /** PG 대사 차이(Discrepancy) 승인 clawback 에 따른 역분개의 원거래. reference_id = discrepancy_id. */
    PG_RECONCILIATION,
    /** 지급후 회수 채권 발생 (seed-p0-6). reference_id = seller_recovery_id. */
    SELLER_RECOVERY,
    /** 채권 상계 (후속 정산 확정 시 지급액 차감). reference_id = recovery_allocation_id. */
    RECOVERY_OFFSET,
    /** 정산 연계 세무 전표(부가세·원천징수 예수). reference_id = settlement_id (ADR 0029). */
    SETTLEMENT_TAX
}
