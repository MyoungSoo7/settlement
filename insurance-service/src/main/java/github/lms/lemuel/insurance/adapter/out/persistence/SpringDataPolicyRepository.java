package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.CommissionStatus;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPolicyRepository extends JpaRepository<PolicyJpaEntity, Long> {

    /** 만기 판정 스캔 — idx_policy_maturity_active 부분 인덱스와 일치. */
    List<PolicyJpaEntity> findByStatusAndMaturityDateLessThanEqual(PolicyStatus status, LocalDate date);

    /** 실효 소멸 판정 스캔 — idx_policy_lapsed_at 부분 인덱스와 일치. */
    List<PolicyJpaEntity> findByStatusAndLapsedAtLessThanEqual(PolicyStatus status, LocalDate date);

    Optional<PolicyJpaEntity> findByPolicyNumber(String policyNumber);

    Optional<PolicyJpaEntity> findByPolicyId(UUID policyId);

    /**
     * 환수 스윕 스캔 — terminal 상태로 종료됐지만 기지급(PAID) 회차가 남은 계약.
     *
     * <p>D6: 환수 트리거 상태(SURRENDERED·CANCELLED·EXPIRED)의 기지급 수수료는
     * CLAWBACK_PENDING 으로 전이돼야 한다. PAID 가 남아 있다는 것 자체가 스윕 대상 신호다.
     */
    @Query("""
            select p from PolicyJpaEntity p
            where p.status in :terminalStatuses
              and exists (
                  select 1 from CommissionScheduleJpaEntity c
                  where c.policyId = p.policyId and c.status = :paidStatus
              )
            """)
    List<PolicyJpaEntity> findTerminalWithCommissionsInStatus(
            @Param("terminalStatuses") Collection<PolicyStatus> terminalStatuses,
            @Param("paidStatus") CommissionStatus paidStatus);

    /**
     * 방카 25%룰 집계 — [from, to) 효력 개시 BANCA 신계약 보험료를 (은행, 부문, 원수사)로 합산.
     * 원수사(insurer_code)·부문(insurer_sector) 미지정 상품은 제외 (카탈로그 정비 대상).
     */
    @Query("""
            select new github.lms.lemuel.insurance.domain.BancaRuleEvaluator$BankInsurerPremium(
                p.partnerBankCode, pr.insurerSector, pr.insurerCode, sum(p.premiumAmount))
            from PolicyJpaEntity p, InsuranceProductJpaEntity pr
            where p.productCode = pr.productCode
              and p.salesChannel = github.lms.lemuel.insurance.domain.SalesChannel.BANCA
              and p.effectiveDate >= :fromInclusive and p.effectiveDate < :toExclusive
              and pr.insurerCode is not null
              and pr.insurerSector is not null
            group by p.partnerBankCode, pr.insurerSector, pr.insurerCode
            order by p.partnerBankCode, pr.insurerSector, pr.insurerCode
            """)
    List<github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium>
    aggregateBancaPremiumsByBankAndInsurer(
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toExclusive") LocalDate toExclusive);
}
