package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 환불 발생 시 SALES_REFUND 차변의 역분개를 작성한다.
 *
 * <p>원장 정신: 이미 작성된 LedgerEntry 자체는 수정·삭제하지 않고, 새로운
 * 역방향 분개 row 를 추가하는 방식으로 환불을 표현한다.
 *
 * <p>입력 refundAmount 는 환불 금액(원). settlement 의 commission rate 에 비례해
 * (refundedNet, refundedCommission) 으로 분해되어 두 row 가 생성된다.
 */
public interface ReverseEntryUseCase {

    /**
     * 환불 1건에 대한 역분개 row 들을 생성한다.
     *
     * @param settlementId   환불 대상 settlement
     * @param refundId       refund 엔티티 PK (LedgerEntry.referenceId 가 됨)
     * @param refundAmount   환불 총액 (원). 양수.
     * @param adjustmentDate 조정 일자
     * @return 생성된 row 리스트. 이미 같은 refundId 로 작성됐다면 빈 리스트.
     */
    List<LedgerEntry> reverseForRefund(Long settlementId, Long refundId,
                                       BigDecimal refundAmount, LocalDate adjustmentDate);

    /**
     * 출처별 역분개 row 들을 생성한다(환불·차지백·PG 대사 공통). 계정 매핑·비율 분해는 환불 역분개와 동일하고
     * {@code referenceType}·entry 유형·메모만 출처에 맞춰 달라진다.
     *
     * @param referenceType 원거래 종류 (REFUND / CHARGEBACK / PG_RECONCILIATION)
     * @param referenceId   출처 식별자 (refundId / chargebackId / discrepancyId) — LedgerEntry.referenceId 가 됨
     * @return 생성된 row 리스트. 이미 같은 (referenceId, referenceType) 로 작성됐다면 빈 리스트.
     */
    List<LedgerEntry> reverseForReference(Long settlementId, Long referenceId, ReferenceType referenceType,
                                          BigDecimal amount, LocalDate adjustmentDate);
}
