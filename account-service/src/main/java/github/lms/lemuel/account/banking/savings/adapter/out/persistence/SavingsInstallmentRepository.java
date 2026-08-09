package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SavingsInstallmentRepository extends JpaRepository<SavingsInstallmentJpaEntity, Long> {

    List<SavingsInstallmentJpaEntity> findBySavingsIdOrderByRoundNoAsc(Long savingsId);

    /** 목록 조회의 N+1 방지 — 계약 id 집합을 한 번에 읽어 메모리에서 계약별로 나눈다. */
    List<SavingsInstallmentJpaEntity> findBySavingsIdInOrderByRoundNoAsc(Collection<Long> savingsIds);
}
