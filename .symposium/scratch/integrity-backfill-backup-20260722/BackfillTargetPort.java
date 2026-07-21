package github.lms.lemuel.integrity.application.port.out;

import github.lms.lemuel.ledger.domain.ReferenceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 백필 대상 상세 로드 포트 — 탐지가 반환한 id 로부터 정정에 필요한 필드만 읽는다.
 *
 * <p>탐지 자체는 {@link IntegrityQueryPort} 가 정본이고, 이 포트는 탐지 결과의 상세 조회만 담당한다
 * (새 탐지 로직 중복 금지 제약).
 */
public interface BackfillTargetPort {

    /** settlement_adjustments 행을 역분개 입력으로 변환해 반환한다. amount 는 양수로 정규화. */
    List<AdjustmentReversalTarget> loadAdjustmentTargets(Collection<Long> adjustmentIds);

    /**
     * 역분개 백필 입력 — {@code referenceType} 은 refund_id/chargeback_id/reconciliation_discrepancy_id
     * 중 채워진 축에서 유도하고, {@code amount} 는 조정 음수 금액의 절대값(원거래 금액)이다.
     */
    record AdjustmentReversalTarget(Long adjustmentId, Long settlementId, Long referenceId,
                                    ReferenceType referenceType, BigDecimal amount, LocalDate adjustmentDate) {
    }
}
