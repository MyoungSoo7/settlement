package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionTransitionException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 수수료 선지급 스케줄 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p><b>D4 — 선지급 12회</b>: 초년도 총액을 정확히 12행으로 분할한다.
 * installment_no = 1..12, 12회 합계 == first_year_total (1원 오차 없음).
 *
 * <p><b>D5 — 계층 수수료 여지</b>: recipient_type, parent_commission_id 는 nullable.
 * 이번 범위에선 recipient_type = 'FC' 만 사용, 계층 계산 로직 미구현.
 *
 * <p><b>D6 — 환수(clawback)</b>: 정책 상태 변화 시 환수액 계산.
 * 계약 효력일부터 종료일까지 경과 개월 m:
 * <ul>
 *   <li>m >= W → 환수액 0 (W = {@link CommissionConstants#CLAWBACK_WINDOW_MONTHS})</li>
 *   <li>m < W → 환수액 = 기지급 합계 × (W - m) / W</li>
 *   <li>CANCELLED → 전액 환수 (m 무관)</li>
 * </ul>
 *
 * <p><b>상태머신</b>: {@link CommissionStatus} 전이표 4개만 허용 —
 * 지급({@link #markPaid}), 미지급 소멸({@link #cancelRemaining}),
 * 환수 트리거({@link #flagClawbackPending}), 환수 수납({@link #confirmClawedBack}).
 *
 * @see CommissionScheduleFactory 초년도 스케줄 생성
 */
public class CommissionSchedule {

    private Long id;
    private final String commissionId;           // UUID — 3단 멱등성 L1
    private final String policyId;               // UUID — 대상 계약
    private final String fcId;                   // 설계사 식별자
    private final String recipientType;          // D5: nullable, 이번 범위는 'FC' 만
    private final String parentCommissionId;     // D5: 계층 수수료 상위 행 (nullable, 계산 미구현)
    private final int installmentNo;             // 1~12
    private final BigDecimal installmentAmount;  // 회차 지급액
    private final BigDecimal firstYearTotal;     // 초년도 총액 기준
    private final LocalDate dueDate;             // 지급 예정일
    private LocalDate paidAt;                    // 지급일 (nullable)
    private BigDecimal paidAmount;               // 실제 지급액 (nullable)
    private CommissionStatus status;             // 전이표는 CommissionStatus 가 강제

    private CommissionSchedule(Builder b) {
        this.id                  = b.id;
        this.commissionId        = Objects.requireNonNull(b.commissionId, "commissionId");
        this.policyId            = Objects.requireNonNull(b.policyId, "policyId");
        this.fcId                = Objects.requireNonNull(b.fcId, "fcId");
        this.recipientType       = b.recipientType;  // nullable
        this.parentCommissionId  = b.parentCommissionId;  // nullable
        this.installmentNo       = b.installmentNo;
        this.installmentAmount   = Objects.requireNonNull(b.installmentAmount, "installmentAmount");
        this.firstYearTotal      = Objects.requireNonNull(b.firstYearTotal, "firstYearTotal");
        this.dueDate             = b.dueDate;  // nullable — 서비스 레이어에서 설정
        this.paidAt              = b.paidAt;
        this.paidAmount          = b.paidAmount;
        this.status              = b.status != null ? b.status : CommissionStatus.SCHEDULED;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 전이 메서드 — CommissionStatus 전이표 4개만 허용
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 지급 — SCHEDULED → PAID.
     *
     * <p>지급 배치가 due_date 도래 회차를 지급 처리한다.
     * 지급액은 회차액(installmentAmount) 전액 — 부분 지급 없음.
     *
     * @param paidAt 지급일
     */
    public void markPaid(LocalDate paidAt) {
        Objects.requireNonNull(paidAt, "paidAt");
        transitionTo(CommissionStatus.PAID);
        this.paidAt = paidAt;
        this.paidAmount = installmentAmount;
    }

    /**
     * 미지급 회차 소멸 — SCHEDULED → CANCELLED.
     *
     * <p>계약 종료(해지·철회·소멸) 시 아직 지급되지 않은 회차를 소멸시킨다.
     * 이미 지급된 회차는 이 경로가 아니라 환수({@link #flagClawbackPending})로만 회수한다.
     */
    public void cancelRemaining() {
        transitionTo(CommissionStatus.CANCELLED);
    }

    /**
     * 환수 트리거 — PAID → CLAWBACK_PENDING.
     *
     * <p>D6: 계약이 SURRENDERED·CANCELLED·LAPSED→EXPIRED 로 종료되면
     * 기지급 회차가 환수 대기로 전이된다. 환수액 산정은 {@link ClawbackCalculator} 몫.
     */
    public void flagClawbackPending() {
        transitionTo(CommissionStatus.CLAWBACK_PENDING);
    }

    /**
     * 환수 수납 확인 — CLAWBACK_PENDING → CLAWED_BACK (terminal).
     */
    public void confirmClawedBack() {
        transitionTo(CommissionStatus.CLAWED_BACK);
    }

    private void transitionTo(CommissionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidCommissionTransitionException(status, target);
        }
        this.status = target;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Getters (setter 없음 — guard.mjs OO 규칙)
    // ─────────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getCommissionId() {
        return commissionId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getFcId() {
        return fcId;
    }

    public String getRecipientType() {
        return recipientType;
    }

    public String getParentCommissionId() {
        return parentCommissionId;
    }

    public int getInstallmentNo() {
        return installmentNo;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public BigDecimal getFirstYearTotal() {
        return firstYearTotal;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getPaidAt() {
        return paidAt;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public CommissionStatus getStatus() {
        return status;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder (영속성 어댑터 재구성 + 정적 팩토리 공용)
    // ─────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String commissionId;
        private String policyId;
        private String fcId;
        private String recipientType;
        private String parentCommissionId;
        private int installmentNo;
        private BigDecimal installmentAmount;
        private BigDecimal firstYearTotal;
        private LocalDate dueDate;
        private LocalDate paidAt;
        private BigDecimal paidAmount;
        private CommissionStatus status;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder commissionId(String v) {
            this.commissionId = v;
            return this;
        }

        public Builder policyId(String v) {
            this.policyId = v;
            return this;
        }

        public Builder fcId(String v) {
            this.fcId = v;
            return this;
        }

        public Builder recipientType(String v) {
            this.recipientType = v;
            return this;
        }

        public Builder parentCommissionId(String v) {
            this.parentCommissionId = v;
            return this;
        }

        public Builder installmentNo(int v) {
            this.installmentNo = v;
            return this;
        }

        public Builder installmentAmount(BigDecimal v) {
            this.installmentAmount = v;
            return this;
        }

        public Builder firstYearTotal(BigDecimal v) {
            this.firstYearTotal = v;
            return this;
        }

        public Builder dueDate(LocalDate v) {
            this.dueDate = v;
            return this;
        }

        public Builder paidAt(LocalDate v) {
            this.paidAt = v;
            return this;
        }

        public Builder paidAmount(BigDecimal v) {
            this.paidAmount = v;
            return this;
        }

        public Builder status(CommissionStatus v) {
            this.status = v;
            return this;
        }

        public CommissionSchedule build() {
            return new CommissionSchedule(this);
        }
    }
}
