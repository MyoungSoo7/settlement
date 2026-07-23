package github.lms.lemuel.payout.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSellerBankAccountRepository
        extends JpaRepository<SellerBankAccountJpaEntity, Long> {
}
