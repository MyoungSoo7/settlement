package github.lms.lemuel.payout.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 미생성 Payout 백필용 페이지 쿼리 포트.
 *
 * <p>INV-6 {@code settlementsWithoutPayout} 탐지 SQL 을 재사용하되, 백필을 위해
 * 날짜 범위·커서 기반 페이징·셀러 메타를 함께 조회한다 — 새 탐지 로직을 중복 작성하지 않는다.
 */
public interface PayoutBackfillQueryPort {

    /**
     * DONE 정산 중 아직 IMMEDIATE Payout 이 없는 정산을 페이지 단위로 조회.
     *
     * <p>커서 기반(afterId)으로 중복 없이 순방향 순회한다. 모든 결과를 소진하면 빈 리스트를 반환한다.
     * SQL 은 INV-6 {@code settlementsWithoutPayout} 쿼리에서 가져온다(탐지 로직 재사용).
     *
     * @param from     확정일(confirmed_at) 시작 (inclusive)
     * @param to       확정일 종료 (inclusive)
     * @param afterId  이전 페이지의 마지막 settlement id (0이면 처음부터)
     * @param pageSize 한 페이지 크기
     */
    List<SettlementForPayout> findDoneWithoutImmediatePayoutPage(
            LocalDate from, LocalDate to, long afterId, int pageSize);

    /**
     * DONE 정산 중 HOLDBACK_RELEASE Payout 이 없고 holdback_amount &gt; 0 인 정산을 페이지 단위로 조회.
     *
     * <p>홀드백이 아직 해제되지 않은 정산도 포함 — 미래 해제 시 송금할 HOLDBACK_RELEASE Payout 을
     * 미리 생성해 두는 것이 아니라, 이미 홀드백이 해제됐는데 Payout 이 없는 경우를 보정한다.
     * (holdback_released = true 이고 holdback_amount > 0 이고 HOLDBACK_RELEASE Payout 없음)
     *
     * @param from     확정일(confirmed_at) 시작 (inclusive)
     * @param to       확정일 종료 (inclusive)
     * @param afterId  이전 페이지의 마지막 settlement id (0이면 처음부터)
     * @param pageSize 한 페이지 크기
     */
    List<SettlementForPayout> findDoneWithoutHoldbackReleasePayoutPage(
            LocalDate from, LocalDate to, long afterId, int pageSize);

    /**
     * DONE 정산 중 IMMEDIATE Payout 이 없는 총 건수 (잔여 측정용).
     */
    long countDoneWithoutImmediatePayout(LocalDate from, LocalDate to);

    /**
     * DONE 정산 중 HOLDBACK_RELEASE Payout 이 없고 holdback이 해제된 총 건수 (잔여 측정용).
     */
    long countDoneWithoutHoldbackReleasePayout(LocalDate from, LocalDate to);

    /**
     * 백필 대상 정산 요약 — settlementId, paymentId, sellerId, 금액 정보를 포함한다.
     */
    record SettlementForPayout(
            long settlementId,
            long paymentId,
            Long sellerId,          // settlement_payment_view.seller_id (null 이면 계좌 해석 불가)
            BigDecimal netAmount,
            BigDecimal immediatePayoutAmount, // netAmount - holdbackAmount (holdback 미해제분 차감)
            BigDecimal holdbackAmount,
            boolean holdbackReleased
    ) {}
}
