package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstallmentSavingsRepository extends JpaRepository<InstallmentSavingsJpaEntity, Long> {

    /** 예금주의 계약 목록 — 최근 개설 순. */
    List<InstallmentSavingsJpaEntity> findByDepositorIdOrderByOpenedOnDescIdDesc(String depositorId);
}
