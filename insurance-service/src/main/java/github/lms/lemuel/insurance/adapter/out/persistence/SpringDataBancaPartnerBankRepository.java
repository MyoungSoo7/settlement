package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 방카 파트너 은행 자산 레지스트리 조회 — 25%룰 모니터링 배치가 전량 로드한다
 * (파트너 은행은 수십 건 규모의 마스터 데이터).
 */
public interface SpringDataBancaPartnerBankRepository
        extends JpaRepository<BancaPartnerBankJpaEntity, String> {
}
