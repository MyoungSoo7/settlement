package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionClosingException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * 월 수수료 마감 스냅샷 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p>월 마감 배치가 FC별 당월 지급 실적(paid_at 기준)을 확정한 불변 스냅샷이다.
 * 이후 환수(CLAWBACK_PENDING) 전이가 일어나도 "그 달에 지급된 사실"은 변하지 않으므로
 * 스냅샷은 정정하지 않는다 — 환수는 별도 이벤트 흐름으로 회계 처리된다.
 *
 * <p>append-only: 필드 전체가 final, 상태 전이 없음. (fc, 월) 유일성은 DB UNIQUE 가 최후 방어.
 */
public final class CommissionClosing {

    private final Long id;
    private final String closingId;          // UUID — 멱등성 L1
    private final String fcId;
    private final YearMonth closingMonth;
    private final BigDecimal totalPaidAmount;
    private final long installmentCount;

    private CommissionClosing(Long id, String closingId, String fcId, YearMonth closingMonth,
                              BigDecimal totalPaidAmount, long installmentCount) {
        this.id = id;
        this.closingId = closingId;
        this.fcId = fcId;
        this.closingMonth = closingMonth;
        this.totalPaidAmount = totalPaidAmount;
        this.installmentCount = installmentCount;
    }

    /**
     * 신규 마감 스냅샷 생성 — 월 마감 배치 전용.
     *
     * @throws InvalidCommissionClosingException 금액이 음수이거나 회차 수가 음수면
     */
    public static CommissionClosing close(String fcId, YearMonth closingMonth,
                                          BigDecimal totalPaidAmount, long installmentCount) {
        Objects.requireNonNull(fcId, "fcId");
        Objects.requireNonNull(closingMonth, "closingMonth");
        Objects.requireNonNull(totalPaidAmount, "totalPaidAmount");
        if (totalPaidAmount.signum() < 0) {
            throw new InvalidCommissionClosingException(
                    "마감 합계는 음수일 수 없습니다: " + totalPaidAmount.toPlainString());
        }
        if (installmentCount < 0) {
            throw new InvalidCommissionClosingException(
                    "마감 회차 수는 음수일 수 없습니다: " + installmentCount);
        }
        return new CommissionClosing(null, UUID.randomUUID().toString(),
                fcId, closingMonth, totalPaidAmount, installmentCount);
    }

    /** DB 행 → 도메인 재구성 — 영속성 어댑터 전용. */
    public static CommissionClosing rehydrate(Long id, String closingId, String fcId,
                                              YearMonth closingMonth, BigDecimal totalPaidAmount,
                                              long installmentCount) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(closingId, "closingId");
        Objects.requireNonNull(fcId, "fcId");
        Objects.requireNonNull(closingMonth, "closingMonth");
        Objects.requireNonNull(totalPaidAmount, "totalPaidAmount");
        return new CommissionClosing(id, closingId, fcId, closingMonth, totalPaidAmount, installmentCount);
    }

    public Long getId() {
        return id;
    }

    public String getClosingId() {
        return closingId;
    }

    public String getFcId() {
        return fcId;
    }

    public YearMonth getClosingMonth() {
        return closingMonth;
    }

    public BigDecimal getTotalPaidAmount() {
        return totalPaidAmount;
    }

    public long getInstallmentCount() {
        return installmentCount;
    }
}
