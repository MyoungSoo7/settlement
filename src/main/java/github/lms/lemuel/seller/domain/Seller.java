package github.lms.lemuel.seller.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 판매자 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class Seller {

    private Long id;
    private Long userId;
    private String businessName;
    private String businessNumber;
    private String representativeName;
    private String phone;
    private String email;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountHolder;
    private BigDecimal commissionRate;
    private SellerStatus status;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private SettlementCycle settlementCycle;
    private java.time.DayOfWeek weeklySettlementDay;
    private Integer monthlySettlementDay;
    private BigDecimal minimumWithdrawalAmount;

    public Seller() {}

    public static Seller create(Long userId, String businessName, String businessNumber,
                                String representativeName, String phone, String email) {
        Seller seller = new Seller();
        seller.userId = userId;
        seller.businessName = businessName;
        seller.businessNumber = businessNumber;
        seller.representativeName = representativeName;
        seller.phone = phone;
        seller.email = email;
        seller.commissionRate = new BigDecimal("0.03");
        seller.status = SellerStatus.PENDING;
        seller.createdAt = LocalDateTime.now();
        seller.updatedAt = LocalDateTime.now();
        seller.settlementCycle = SettlementCycle.DAILY;
        seller.minimumWithdrawalAmount = new BigDecimal("1000");
        return seller;
    }

    public void approve() {
        if (this.status != SellerStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태에서만 승인할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void reject() {
        if (this.status != SellerStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태에서만 거부할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerStatus.REJECTED;
        this.updatedAt = LocalDateTime.now();
    }

    public void suspend() {
        if (this.status != SellerStatus.APPROVED) {
            throw new IllegalStateException("승인된 상태에서만 정지할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerStatus.SUSPENDED;
        this.updatedAt = LocalDateTime.now();
    }

    public void reactivate() {
        if (this.status != SellerStatus.SUSPENDED) {
            throw new IllegalStateException("정지된 상태에서만 재활성화할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateBankInfo(String bankName, String accountNumber, String accountHolder) {
        this.bankName = bankName;
        this.bankAccountNumber = accountNumber;
        this.bankAccountHolder = accountHolder;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateCommissionRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("수수료율은 0보다 크고 1보다 작아야 합니다.");
        }
        this.commissionRate = rate;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateBusinessInfo(String businessName, String representativeName, String phone, String email) {
        this.businessName = businessName;
        this.representativeName = representativeName;
        this.phone = phone;
        this.email = email;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isApproved() {
        return this.status == SellerStatus.APPROVED;
    }

    public boolean isPending() {
        return this.status == SellerStatus.PENDING;
    }

    public void updateSettlementCycle(SettlementCycle cycle, java.time.DayOfWeek weeklyDay, Integer monthlyDay) {
        if (cycle == null) {
            throw new IllegalArgumentException("정산 주기는 필수입니다.");
        }
        if (cycle == SettlementCycle.MONTHLY) {
            if (monthlyDay == null || monthlyDay < 1 || monthlyDay > 28) {
                throw new IllegalArgumentException("월 정산일은 1~28 사이여야 합니다.");
            }
        }
        this.settlementCycle = cycle;
        this.weeklySettlementDay = weeklyDay;
        this.monthlySettlementDay = monthlyDay;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isSettlementDueOn(java.time.LocalDate date) {
        if (settlementCycle == null) return true; // default DAILY
        return switch (settlementCycle) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == weeklySettlementDay;
            case MONTHLY -> date.getDayOfMonth() == monthlySettlementDay;
        };
    }

    // ── Getters & Setters ──────────────────────────────────────────────

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }

    public Long getUserId()                          { return userId; }
    public void setUserId(Long userId)               { this.userId = userId; }

    public String getBusinessName()                  { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getBusinessNumber()                { return businessNumber; }
    public void setBusinessNumber(String businessNumber) { this.businessNumber = businessNumber; }

    public String getRepresentativeName()            { return representativeName; }
    public void setRepresentativeName(String representativeName) { this.representativeName = representativeName; }

    public String getPhone()                         { return phone; }
    public void setPhone(String phone)               { this.phone = phone; }

    public String getEmail()                         { return email; }
    public void setEmail(String email)               { this.email = email; }

    public String getBankName()                      { return bankName; }
    public void setBankName(String bankName)         { this.bankName = bankName; }

    public String getBankAccountNumber()             { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }

    public String getBankAccountHolder()             { return bankAccountHolder; }
    public void setBankAccountHolder(String bankAccountHolder) { this.bankAccountHolder = bankAccountHolder; }

    public BigDecimal getCommissionRate()            { return commissionRate; }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }

    public SellerStatus getStatus()                  { return status; }
    public void setStatus(SellerStatus status)       { this.status = status; }

    public LocalDateTime getApprovedAt()             { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public SettlementCycle getSettlementCycle()          { return settlementCycle; }
    public void setSettlementCycle(SettlementCycle c)    { this.settlementCycle = c; }

    public java.time.DayOfWeek getWeeklySettlementDay()  { return weeklySettlementDay; }
    public void setWeeklySettlementDay(java.time.DayOfWeek d) { this.weeklySettlementDay = d; }

    public Integer getMonthlySettlementDay()             { return monthlySettlementDay; }
    public void setMonthlySettlementDay(Integer d)       { this.monthlySettlementDay = d; }

    public BigDecimal getMinimumWithdrawalAmount()       { return minimumWithdrawalAmount; }
    public void setMinimumWithdrawalAmount(BigDecimal a) { this.minimumWithdrawalAmount = a; }
}
