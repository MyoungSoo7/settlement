package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AccountEntryJpaEntityTest {

    @Test
    void 생성자로_넣은_모든_필드가_게터로_노출된다() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 10, 9, 30);
        AccountEntryJpaEntity entity = new AccountEntryJpaEntity(
                OwnerType.CORPORATE, "005930",
                GlAccount.CORPORATE_LOAN_RECEIVABLE, GlAccount.CASH, new BigDecimal("5000000"),
                "CORP_LOAN_DISBURSED", "9", "lemuel.loan.corporate_loan_disbursed", occurredAt);

        assertThat(entity.getId()).isNull(); // IDENTITY — 영속 전이라 미할당
        assertThat(entity.getOwnerType()).isEqualTo(OwnerType.CORPORATE);
        assertThat(entity.getOwnerId()).isEqualTo("005930");
        assertThat(entity.getDebitAccount()).isEqualTo(GlAccount.CORPORATE_LOAN_RECEIVABLE);
        assertThat(entity.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entity.getAmount()).isEqualByComparingTo("5000000");
        assertThat(entity.getRefType()).isEqualTo("CORP_LOAN_DISBURSED");
        assertThat(entity.getRefId()).isEqualTo("9");
        assertThat(entity.getSourceTopic()).isEqualTo("lemuel.loan.corporate_loan_disbursed");
        assertThat(entity.getOccurredAt()).isEqualTo(occurredAt);
    }
}
