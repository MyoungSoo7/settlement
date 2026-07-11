package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CompanyReputation;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.LedgerAccount;
import github.lms.lemuel.loan.domain.LoanStatus;
import github.lms.lemuel.loan.domain.SettlementViewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 엔티티 생성자·getter·toDomain 매핑 단위 테스트 — 모든 컬럼 접근자 라인을 커버한다.
 */
class JpaEntityGettersTest {

    @Test
    @DisplayName("CorporateLoanJpaEntity 생성자·getter")
    void corporateLoan() {
        LocalDateTime now = LocalDateTime.now();
        CorporateLoanJpaEntity e = new CorporateLoanJpaEntity(7L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                30, 82, "A", CorporateLoanStatus.DISBURSED, now);

        assertThat(e.getId()).isEqualTo(7L);
        assertThat(e.getStockCode()).isEqualTo("005930");
        assertThat(e.getCorpName()).isEqualTo("삼성전자");
        assertThat(e.getPrincipal()).isEqualByComparingTo("1000000");
        assertThat(e.getFee()).isEqualByComparingTo("6600");
        assertThat(e.getOutstanding()).isEqualByComparingTo("1006600");
        assertThat(e.getTermDays()).isEqualTo(30);
        assertThat(e.getCreditScore()).isEqualTo(82);
        assertThat(e.getCreditGrade()).isEqualTo("A");
        assertThat(e.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
        assertThat(e.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("CompanyReputationJpaEntity 생성자·getter·toDomain")
    void companyReputation() {
        LocalDateTime now = LocalDateTime.now();
        CompanyReputationJpaEntity e = new CompanyReputationJpaEntity(
                "005930", 55, "C", "B", LocalDate.of(2026, 7, 7), now);

        assertThat(e.getStockCode()).isEqualTo("005930");
        assertThat(e.getScore()).isEqualTo(55);
        assertThat(e.getGrade()).isEqualTo("C");
        assertThat(e.getPreviousGrade()).isEqualTo("B");
        assertThat(e.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(e.getUpdatedAt()).isEqualTo(now);

        CompanyReputation domain = e.toDomain();
        assertThat(domain.getStockCode()).isEqualTo("005930");
        assertThat(domain.getScore()).isEqualTo(55);
        assertThat(domain.getGrade()).isEqualTo("C");
        assertThat(domain.getPreviousGrade()).isEqualTo("B");
        assertThat(domain.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 7, 7));
    }

    @Test
    @DisplayName("SellerReputationJpaEntity 생성자·getter")
    void sellerReputation() {
        LocalDateTime now = LocalDateTime.now();
        SellerReputationJpaEntity e = new SellerReputationJpaEntity(9L, "005930", 70, "B", now);

        assertThat(e.getSellerId()).isEqualTo(9L);
        assertThat(e.getStockCode()).isEqualTo("005930");
        assertThat(e.getScore()).isEqualTo(70);
        assertThat(e.getGrade()).isEqualTo("B");
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("SellerSettlementViewJpaEntity 생성자·getter")
    void sellerSettlementView() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate due = LocalDate.of(2026, 7, 20);
        SellerSettlementViewJpaEntity e = new SellerSettlementViewJpaEntity(
                100L, 7L, new BigDecimal("500000"), due, SettlementViewStatus.PENDING, now);

        assertThat(e.getSettlementId()).isEqualTo(100L);
        assertThat(e.getSellerId()).isEqualTo(7L);
        assertThat(e.getAmount()).isEqualByComparingTo("500000");
        assertThat(e.getDueDate()).isEqualTo(due);
        assertThat(e.getStatus()).isEqualTo(SettlementViewStatus.PENDING);
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("LoanRepaymentJpaEntity 생성자·getter")
    void loanRepayment() {
        LoanRepaymentJpaEntity e = new LoanRepaymentJpaEntity(100L, 7L, new BigDecimal("800000"));

        assertThat(e.getId()).isNull();
        assertThat(e.getSettlementId()).isEqualTo(100L);
        assertThat(e.getSellerId()).isEqualTo(7L);
        assertThat(e.getDeducted()).isEqualByComparingTo("800000");
    }

    @Test
    @DisplayName("LoanAdvanceJpaEntity 생성자·getter")
    void loanAdvance() {
        LocalDateTime now = LocalDateTime.now();
        LoanAdvanceJpaEntity e = new LoanAdvanceJpaEntity(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.DISBURSED, now);

        assertThat(e.getId()).isEqualTo(1L);
        assertThat(e.getSellerId()).isEqualTo(7L);
        assertThat(e.getPrincipal()).isEqualByComparingTo("800000");
        assertThat(e.getFee()).isEqualByComparingTo("800");
        assertThat(e.getOutstanding()).isEqualByComparingTo("800800");
        assertThat(e.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("LoanLedgerEntryJpaEntity 생성자·getter")
    void loanLedgerEntry() {
        LoanLedgerEntryJpaEntity e = new LoanLedgerEntryJpaEntity(
                LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE, new BigDecimal("800800"),
                "REPAYMENT", 100L);

        assertThat(e.getId()).isNull();
        assertThat(e.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(e.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("800800");
        assertThat(e.getRefType()).isEqualTo("REPAYMENT");
        assertThat(e.getRefId()).isEqualTo(100L);
    }
}
