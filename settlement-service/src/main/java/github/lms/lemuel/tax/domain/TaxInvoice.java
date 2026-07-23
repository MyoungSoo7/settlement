package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 세금계산서 도메인 — 플랫폼 → 셀러 수수료 세금계산서(공급가액=수수료, 세액=부가세) (ADR 0027).
 *
 * <p>발행번호({@link #issueNumber})는 정산 1건에서 <b>결정적으로</b> 파생한다(같은 정산 → 같은 번호).
 * 영속 UNIQUE 와 결합해 재발행이 자연히 멱등(중복 발행 차단)이 되게 한다 — 발행일이 달라도 번호는 불변.
 *
 * <p>순수 POJO·팩토리 전용·불변(발행 후 금액·번호 재할당 불가). public setter 없음.
 */
public class TaxInvoice {

    private static final String NUMBER_PREFIX = "TI-";

    private Long id;
    private final Long settlementId;
    private final Long sellerId;
    private final BigDecimal supplyAmount;   // 공급가액 = 수수료
    private final BigDecimal taxAmount;      // 세액 = 부가세
    private final BigDecimal totalAmount;    // 합계 = 공급가액 + 세액
    private final LocalDate issueDate;
    private final String issueNumber;
    private final LocalDateTime createdAt;

    private TaxInvoice(Long id, Long settlementId, Long sellerId, BigDecimal supplyAmount,
                       BigDecimal taxAmount, BigDecimal totalAmount, LocalDate issueDate,
                       String issueNumber, LocalDateTime createdAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.issueDate = issueDate;
        this.issueNumber = issueNumber;
        this.createdAt = createdAt;
    }

    /**
     * 세무 계산으로부터 세금계산서를 발행한다. 공급가액=수수료, 세액=부가세, 합계=공급가액+세액.
     */
    public static TaxInvoice issue(Long settlementId, Long sellerId, TaxCalculation calc, LocalDate issueDate) {
        if (calc == null) {
            throw new TaxInvariantViolationException("TaxCalculation 은 필수입니다");
        }
        return create(settlementId, sellerId, calc.supplyAmount(), calc.vatAmount(), issueDate);
    }

    private static TaxInvoice create(Long settlementId, Long sellerId, BigDecimal supplyAmount,
                                     BigDecimal taxAmount, LocalDate issueDate) {
        if (settlementId == null || settlementId <= 0) {
            throw new TaxInvariantViolationException("settlementId 는 양수여야 합니다: " + settlementId);
        }
        if (sellerId == null || sellerId <= 0) {
            throw new TaxInvariantViolationException("sellerId 는 양수여야 합니다: " + sellerId);
        }
        if (supplyAmount == null || supplyAmount.signum() < 0) {
            throw new TaxInvariantViolationException("공급가액은 음수일 수 없습니다: " + supplyAmount);
        }
        if (taxAmount == null || taxAmount.signum() < 0) {
            throw new TaxInvariantViolationException("세액은 음수일 수 없습니다: " + taxAmount);
        }
        if (issueDate == null) {
            throw new TaxInvariantViolationException("발행일은 필수입니다");
        }
        BigDecimal total = supplyAmount.add(taxAmount);
        String number = numberFor(settlementId);
        return new TaxInvoice(null, settlementId, sellerId, supplyAmount, taxAmount, total,
                issueDate, number, LocalDateTime.now());
    }

    /** 영속 복원 전용. */
    public static TaxInvoice rehydrate(Long id, Long settlementId, Long sellerId, BigDecimal supplyAmount,
                                       BigDecimal taxAmount, BigDecimal totalAmount, LocalDate issueDate,
                                       String issueNumber, LocalDateTime createdAt) {
        return new TaxInvoice(id, settlementId, sellerId, supplyAmount, taxAmount, totalAmount,
                issueDate, issueNumber, createdAt);
    }

    /** 정산 식별자에서 결정적으로 파생한 발행번호(멱등 키). */
    public static String numberFor(Long settlementId) {
        return NUMBER_PREFIX + String.format("%010d", settlementId);
    }

    /** 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public BigDecimal getSupplyAmount() {
        return supplyAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public String getIssueNumber() {
        return issueNumber;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
