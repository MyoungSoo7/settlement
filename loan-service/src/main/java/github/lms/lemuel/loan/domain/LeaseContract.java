package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 리스·할부 계약 애그리거트 — 캐피탈 물건금융의 계약 단위.
 *
 * <p>불변식은 셋이다.
 * <ol>
 *   <li><b>상태 전이는 {@link LeaseStatus} 표에만 위임</b>한다. 애그리거트가 자체 판단으로 전이하지 않는다.</li>
 *   <li><b>납입 회차는 계약 기간을 넘지 않는다.</b> 넘는 순간 회차표에 없는 청구가 생긴다.</li>
 *   <li><b>중도해지 정산은 계약이 살아 있을 때만</b> 산정한다 — 종료된 계약에 손해금을 매기지 않는다.</li>
 * </ol>
 *
 * <p>스케줄({@link LeaseSchedule})은 계약 체결 시점에 확정되어 <b>변경되지 않는다</b>. 조건이 바뀌면
 * 재계약이지 같은 계약의 수정이 아니다 — 이미 청구·수납된 회차의 근거가 사라지기 때문이다.
 */
public class LeaseContract {

    private final Long id;
    private final Borrower borrower;
    private final AssetFinanceType type;
    private final String assetDescription;
    private final LeaseSchedule schedule;
    private final OffsetDateTime appliedAt;

    private LeaseStatus status;
    private int paidInstallments;
    private OffsetDateTime activatedAt;
    private OffsetDateTime closedAt;

    private LeaseContract(Long id, Borrower borrower, AssetFinanceType type, String assetDescription,
                          LeaseSchedule schedule, LeaseStatus status, int paidInstallments,
                          OffsetDateTime appliedAt, OffsetDateTime activatedAt, OffsetDateTime closedAt) {
        this.id = id;
        this.borrower = borrower;
        this.type = type;
        this.assetDescription = assetDescription;
        this.schedule = schedule;
        this.status = status;
        this.paidInstallments = paidInstallments;
        this.appliedAt = appliedAt;
        this.activatedAt = activatedAt;
        this.closedAt = closedAt;
    }

    /** 신규 신청 — 스케줄은 이 시점에 확정된다. */
    public static LeaseContract apply(Borrower borrower, String assetDescription, LeaseSchedule schedule,
                                      OffsetDateTime appliedAt) {
        if (borrower == null) throw new LoanInvariantViolationException("차주는 필수입니다");
        if (schedule == null) throw new LoanInvariantViolationException("리스 스케줄은 필수입니다");
        if (assetDescription == null || assetDescription.isBlank()) {
            throw new LoanInvariantViolationException("리스 물건 표시는 필수입니다");
        }
        if (appliedAt == null) throw new LoanInvariantViolationException("신청 시각은 필수입니다");
        return new LeaseContract(null, borrower, schedule.type(), assetDescription, schedule,
                LeaseStatus.APPLIED, 0, appliedAt, null, null);
    }

    /** 영속 복원 전용. */
    public static LeaseContract reconstitute(Long id, Borrower borrower, AssetFinanceType type,
                                             String assetDescription, LeaseSchedule schedule, LeaseStatus status,
                                             int paidInstallments, OffsetDateTime appliedAt,
                                             OffsetDateTime activatedAt, OffsetDateTime closedAt) {
        return new LeaseContract(id, borrower, type, assetDescription, schedule, status, paidInstallments,
                appliedAt, activatedAt, closedAt);
    }

    // ─── 심사·개시 ─────────────────────────────────────────────────────────────

    public void approve() {
        transitionTo(LeaseStatus.APPROVED);
    }

    public void reject() {
        transitionTo(LeaseStatus.REJECTED);
    }

    /** 승인 후 인도 전 취소. */
    public void cancel() {
        transitionTo(LeaseStatus.CANCELLED);
    }

    /** 물건 인도 완료 — 여기서부터 리스료를 청구한다. */
    public void activate(OffsetDateTime deliveredAt) {
        if (deliveredAt == null) throw new LoanInvariantViolationException("인도 시각은 필수입니다");
        transitionTo(LeaseStatus.ACTIVE);
        this.activatedAt = deliveredAt;
    }

    // ─── 회차 수납·연체 ─────────────────────────────────────────────────────────

    /**
     * 회차 수납 — 납입 회차를 1 늘린다. 마지막 회차를 받으면 만기 종료 조건이 갖춰지지만,
     * 종료 자체는 {@link #mature()} 로 <b>명시</b>한다(인수·반환 처리가 남아 있기 때문).
     */
    public void payInstallment() {
        requireBillable("회차 수납");
        if (paidInstallments >= schedule.termMonths()) {
            throw new LoanInvariantViolationException(
                    "계약 기간을 넘는 회차는 수납할 수 없습니다: " + (paidInstallments + 1) + " > " + schedule.termMonths());
        }
        paidInstallments++;
        if (status == LeaseStatus.OVERDUE) {
            transitionTo(LeaseStatus.ACTIVE);   // 미납 해소 — 회차 상품의 정상 흐름
        }
    }

    public void markOverdue() {
        transitionTo(LeaseStatus.OVERDUE);
    }

    /** 기한이익상실 — 연체를 거쳐야만 도달한다(상태표가 강제). */
    public void markDefaulted() {
        transitionTo(LeaseStatus.DEFAULTED);
    }

    // ─── 종료 ─────────────────────────────────────────────────────────────────

    /** 만기 종료 — 전 회차 수납이 끝나야 한다. */
    public void mature(OffsetDateTime closedAt) {
        if (paidInstallments < schedule.termMonths()) {
            throw new LoanInvariantViolationException(
                    "전 회차 수납 전에는 만기 종료할 수 없습니다: " + paidInstallments + "/" + schedule.termMonths());
        }
        transitionTo(LeaseStatus.MATURED);
        this.closedAt = requireTime(closedAt);
    }

    /**
     * 중도해지 정산 — 규정손해금을 산정해 계약을 종결한다.
     *
     * @return 정산서(청구액·보증금 상계 결과)
     */
    public EarlyTerminationQuote terminateEarly(BigDecimal penaltyRatePercent, OffsetDateTime closedAt) {
        if (status.isTerminal()) {
            throw new LoanInvariantViolationException("이미 종료된 계약은 중도해지할 수 없습니다: " + status);
        }
        EarlyTerminationQuote quote = EarlyTerminationQuote.of(schedule, paidInstallments, penaltyRatePercent);
        transitionTo(LeaseStatus.EARLY_TERMINATED);
        this.closedAt = requireTime(closedAt);
        return quote;
    }

    /** 해지하지 않고 정산액만 조회한다(고객 안내용) — 상태를 바꾸지 않는다. */
    public EarlyTerminationQuote quoteEarlyTermination(BigDecimal penaltyRatePercent) {
        requireBillable("중도해지 정산 조회");
        return EarlyTerminationQuote.of(schedule, paidInstallments, penaltyRatePercent);
    }

    // ─── 조회 ─────────────────────────────────────────────────────────────────

    /** 미회수 잔액(잔존가치 포함). */
    public BigDecimal outstandingBalance() {
        return schedule.balanceAfter(paidInstallments);
    }

    public Long getId() { return id; }

    public Borrower getBorrower() { return borrower; }

    public AssetFinanceType getType() { return type; }

    public String getAssetDescription() { return assetDescription; }

    public LeaseSchedule getSchedule() { return schedule; }

    public LeaseStatus getStatus() { return status; }

    public int getPaidInstallments() { return paidInstallments; }

    public OffsetDateTime getAppliedAt() { return appliedAt; }

    public OffsetDateTime getActivatedAt() { return activatedAt; }

    public OffsetDateTime getClosedAt() { return closedAt; }

    // ─── 내부 ─────────────────────────────────────────────────────────────────

    private void transitionTo(LeaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new LoanInvariantViolationException("허용되지 않는 상태 전이입니다: " + status + " → " + target);
        }
        status = target;
    }

    private void requireBillable(String action) {
        if (!status.isBillable()) {
            throw new LoanInvariantViolationException(action + "은(는) 계약이 유효할 때만 가능합니다: " + status);
        }
    }

    private static OffsetDateTime requireTime(OffsetDateTime time) {
        if (time == null) throw new LoanInvariantViolationException("종료 시각은 필수입니다");
        return time;
    }
}
