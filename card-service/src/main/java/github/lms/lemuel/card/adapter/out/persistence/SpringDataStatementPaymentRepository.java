package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** statement_payments 테이블 Spring Data JPA 리포지터리. */
public interface SpringDataStatementPaymentRepository
        extends JpaRepository<StatementPaymentJpaEntity, Long> {

    boolean existsByPaymentId(String paymentId);
}
