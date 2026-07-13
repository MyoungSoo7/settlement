package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 기업 신용대출 애그리거트 루트. 순수 POJO — 프레임워크 의존 0.
 *
 * <p>상장사(코스피/코스닥)가 재무제표·평판으로 산정된 신용한도 안에서 받는 무담보 신용대출.
 * 신용점수/등급은 신청 시점에 {@code CorporateCreditPolicy} 가 산정한 값을 <b>스냅샷</b>으로 보존한다
 * (정산의 commission_rate 스냅샷과 같은 이력 재현성 철학). 한도 검증·수수료 산정은 정책의 책임이며,
 * 이 애그리거트는 산정된 원금(principal)·수수료(fee)를 받아 미상환잔액(outstanding)과 상태를 강제한다.
 *
 * <pre>
 * REQUESTED → APPROVED → DISBURSED → REPAID
 *           ↘ REJECTED
 * </pre>
 */
public class CorporateLoan {

    private Long id;
    private final String stockCode;
    private final String corpName;
    private final BigDecimal principal;
    private final BigDecimal fee;
    private BigDecimal outstanding;
    private final int termDays;
    private final int creditScore;
    private final String creditGrade;
    private CorporateLoanStatus status;
    private final LocalDateTime createdAt;

    private CorporateLoan(Long id, String stockCode, String corpName, BigDecimal principal, BigDecimal fee,
                          BigDecimal outstanding, int termDays, int creditScore, String creditGrade,
                          CorporateLoanStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.stockCode = stockCode;
        this.corpName = corpName;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.termDays = termDays;
        this.creditScore = creditScore;
        this.creditGrade = creditGrade;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 신규 기업대출 신청. 실행 전이므로 미상환잔액은 0, 상태는 REQUESTED. */
    public static CorporateLoan request(String stockCode, String corpName, BigDecimal principal, BigDecimal fee,
                                        int termDays, int creditScore, String creditGrade) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new IllegalArgumentException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("대출 원금은 양수여야 합니다: " + principal);
        }
        if (fee == null || fee.signum() < 0) {
            throw new IllegalArgumentException("수수료는 음수일 수 없습니다: " + fee);
        }
        if (termDays <= 0) {
            throw new IllegalArgumentException("대출 기간(일)은 양수여야 합니다: " + termDays);
        }
        if (creditScore < 0 || creditScore > 100) {
            throw new IllegalArgumentException("신용점수는 0~100 이어야 합니다: " + creditScore);
        }
        if (creditGrade == null || creditGrade.isBlank()) {
            throw new IllegalArgumentException("신용등급은 필수입니다");
        }
        return new CorporateLoan(null, stockCode, corpName, principal, fee, BigDecimal.ZERO,
                termDays, creditScore, creditGrade, CorporateLoanStatus.REQUESTED, LocalDateTime.now());
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static CorporateLoan reconstitute(Long id, String stockCode, String corpName, BigDecimal principal,
                                             BigDecimal fee, BigDecimal outstanding, int termDays, int creditScore,
                                             String creditGrade, CorporateLoanStatus status, LocalDateTime createdAt) {
        return new CorporateLoan(id, stockCode, corpName, principal, fee, outstanding,
                termDays, creditScore, creditGrade, status, createdAt);
    }

    public void approve() {
        requireStatus(CorporateLoanStatus.REQUESTED, "승인");
        this.status = CorporateLoanStatus.APPROVED;
    }

    public void reject() {
        requireStatus(CorporateLoanStatus.REQUESTED, "거절");
        this.status = CorporateLoanStatus.REJECTED;
    }

    /** 실행(대출금 지급). 미상환잔액 = 원금 + 수수료. */
    public void disburse() {
        requireStatus(CorporateLoanStatus.APPROVED, "실행");
        this.outstanding = Money.of(principal).plus(Money.of(fee)).toBigDecimal();
        this.status = CorporateLoanStatus.DISBURSED;
    }

    /**
     * 부분상환. 미상환잔액에서 차감하되 잔액을 넘어서 차감하지 않는다(clamp).
     * 잔액이 0 이 되면 REPAID 로 전이한다.
     *
     * @param amount 상환액(양수)
     * @return 실제 차감된 금액
     */
    public BigDecimal repay(BigDecimal amount) {
        requireStatus(CorporateLoanStatus.DISBURSED, "상환");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("상환액은 양수여야 합니다: " + amount);
        }
        Money remaining = Money.of(outstanding);
        Money deducted = remaining.min(Money.of(amount));
        remaining = remaining.minus(deducted);
        this.outstanding = remaining.toBigDecimal();
        if (remaining.isZero()) {
            this.status = CorporateLoanStatus.REPAID;
        }
        return deducted.toBigDecimal();
    }

    private void requireStatus(CorporateLoanStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    action + "은(는) " + expected + " 상태에서만 가능합니다. 현재=" + status);
        }
    }

    public Long getId() { return id; }
    public String getStockCode() { return stockCode; }
    public String getCorpName() { return corpName; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public int getTermDays() { return termDays; }
    public int getCreditScore() { return creditScore; }
    public String getCreditGrade() { return creditGrade; }
    public CorporateLoanStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
