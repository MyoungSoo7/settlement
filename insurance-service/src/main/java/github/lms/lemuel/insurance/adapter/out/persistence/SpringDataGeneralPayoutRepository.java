package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataGeneralPayoutRepository extends JpaRepository<GeneralPayoutJpaEntity, Long> {

    /** 일반지급 실행 배치 스캔 — idx_general_payout_requested 부분 인덱스와 일치. */
    List<GeneralPayoutJpaEntity> findByStatusOrderByIdAsc(GeneralPayoutStatus status);

    /** 계약별 내역 — 생성 순서 정렬. */
    List<GeneralPayoutJpaEntity> findByPolicyIdOrderByIdAsc(UUID policyId);
}
