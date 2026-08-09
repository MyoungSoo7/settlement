package github.lms.lemuel.account.banking.pension.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PensionTransactionRepository extends JpaRepository<PensionTransactionJpaEntity, Long> {

    /** 계약의 거래 이력 — seq 오름차순이 곧 발생 순서다. */
    List<PensionTransactionJpaEntity> findByPensionIdOrderBySeqAsc(Long pensionId);

    List<PensionTransactionJpaEntity> findByPensionIdInOrderBySeqAsc(List<Long> pensionIds);
}
