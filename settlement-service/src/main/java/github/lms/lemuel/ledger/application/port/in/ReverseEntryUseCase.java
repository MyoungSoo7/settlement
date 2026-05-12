package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerEntry;

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
}
