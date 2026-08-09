package github.lms.lemuel.account.banking.timedeposit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimeDepositRepository extends JpaRepository<TimeDepositJpaEntity, Long> {

    /** 예금주별 계좌 — 최신 개설 순(id DESC, {@code idx_time_deposits_depositor} 가 커버). */
    List<TimeDepositJpaEntity> findByDepositorIdOrderByIdDesc(String depositorId);
}
