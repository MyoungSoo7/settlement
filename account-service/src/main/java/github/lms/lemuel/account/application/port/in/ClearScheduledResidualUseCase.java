package github.lms.lemuel.account.application.port.in;

import java.math.BigDecimal;

/**
 * cut-over 잔존 정산예정금(SETTLEMENT_SCHEDULED) 청산 백필 인바운드 포트 (관리자 실행) — ADR 0026 Option A.
 *
 * <p>과거 규칙으로 적재된 셀러별 SETTLEMENT_SCHEDULED 순차변 잔액을 CASH 로 재분류하는 마감 조정분개를
 * 전기한다. 전면 재처리가 아니라 잔액만 청산하며, 멱등이라 반복 실행해도 결과가 불변이다(추가 청산 0건).
 */
public interface ClearScheduledResidualUseCase {

    /**
     * 잔존 예정금 청산을 실행하고 결과를 보고한다.
     *
     * @return 이번 실행에서 청산된 셀러 수·총 청산 금액
     */
    ClearingReport clearResidual();

    /**
     * 백필 실행 결과.
     *
     * @param clearedSellers 이번 실행에서 청산분개가 적재된 셀러 수(이미 청산됐거나 잔액 0 인 셀러 제외)
     * @param totalCleared   청산된 총 금액(SETTLEMENT_SCHEDULED → CASH 재분류 합)
     */
    record ClearingReport(int clearedSellers, BigDecimal totalCleared) {
    }
}
