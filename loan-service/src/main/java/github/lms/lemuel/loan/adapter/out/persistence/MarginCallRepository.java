package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.MarginCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarginCallRepository extends JpaRepository<MarginCallJpaEntity, Long> {

    /** 대출의 활성 마진콜 — uq_margin_call_open_per_loan 이 유일성을 보장하므로 Optional. */
    Optional<MarginCallJpaEntity> findByLoanIdAndStatus(Long loanId, MarginCallStatus status);
}
