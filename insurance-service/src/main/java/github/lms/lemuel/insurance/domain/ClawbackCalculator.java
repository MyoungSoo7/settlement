package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidClawbackStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * D6 — 환수(clawback) 계산 도메인 서비스.
 *
 * <p><b>정책</b>:
 * <ul>
 *   <li>계약 효력일부터 종료일까지 완료된 개월 수 m (내림 기준)</li>
 *   <li>m >= 24 → 환수액 0 (24개월 창구 종료)</li>
 *   <li>m < 24 → 환수액 = 기지급 합계 × (24 - m) / 24, 통화 최소단위 절사(DOWN)</li>
 *   <li>CANCELLED 상태 → m 무관 전액 환수</li>
 *   <li>환수 트리거 상태: SURRENDERED, CANCELLED, EXPIRED(부활기간 도과 소멸)</li>
 * </ul>
 *
 * <p><b>상수 규율 (D6)</b>: 공식의 "24"는 {@link CommissionConstants#CLAWBACK_WINDOW_MONTHS} 로부터만 참조.
 * 매직넘버 금지.
 */
public final class ClawbackCalculator {

    private ClawbackCalculator() {
        // static utility
    }

    /**
     * 환수액 계산.
     *
     * @param paidTotal 기지급 합계 (BigDecimal, null 불허)
     * @param effectiveDate 계약 효력일 (LocalDate, null 불허)
     * @param endDate 계약 종료일 — lapsedAt, surrenderedAt, expiredAt 등 (LocalDate, null 불허)
     * @param status 계약 상태 — SURRENDERED|CANCELLED|EXPIRED(소멸) 만 허용
     * @return 환수액 (BigDecimal, >= 0)
     * @throws NullPointerException 입력값이 null 이면
     * @throws InvalidClawbackStateException 상태가 환수 대상이 아니면
     */
    public static BigDecimal calculate(
            BigDecimal paidTotal,
            LocalDate effectiveDate,
            LocalDate endDate,
            PolicyStatus status) {

        Objects.requireNonNull(paidTotal, "paidTotal");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(status, "status");

        // 환수 트리거 상태 검증
        if (!isClawbackTriggered(status)) {
            throw new InvalidClawbackStateException(
                    "환수 대상이 아닌 상태: " + status,
                    status);
        }

        // CANCELLED 상태는 전액 환수
        if (status == PolicyStatus.CANCELLED) {
            return paidTotal;
        }

        // SURRENDERED, EXPIRED 상태: m 기반 계산
        long elapsedMonths = ChronoUnit.MONTHS.between(effectiveDate, endDate);
        int clawbackWindow = CommissionConstants.CLAWBACK_WINDOW_MONTHS;

        if (elapsedMonths >= clawbackWindow) {
            // m >= 24 → 환수액 0
            return BigDecimal.ZERO;
        }

        // m < 24 → 환수액 = paidTotal × (24 - m) / 24
        BigDecimal remainingMonths = BigDecimal.valueOf(clawbackWindow - elapsedMonths);
        BigDecimal totalMonths = BigDecimal.valueOf(clawbackWindow);

        return paidTotal
                .multiply(remainingMonths)
                .divide(totalMonths, CommissionConstants.AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /**
     * 환수 트리거 상태 판별.
     *
     * <p>D6: SURRENDERED(해지), CANCELLED(철회), EXPIRED(소멸) 3개 상태만 환수 대상.
     * ACTIVE, LAPSED 는 환수 트리거가 아니다.
     *
     * @param status 검사 상태
     * @return true if status is SURRENDERED | CANCELLED | EXPIRED
     */
    private static boolean isClawbackTriggered(PolicyStatus status) {
        return status == PolicyStatus.SURRENDERED
                || status == PolicyStatus.CANCELLED
                || status == PolicyStatus.EXPIRED;
    }
}
