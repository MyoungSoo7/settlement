package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;

/**
 * 판매자 Domain <-> JpaEntity 수동 매퍼
 */
public class SellerPersistenceMapper {

    private SellerPersistenceMapper() {}

    public static Seller toDomain(SellerJpaEntity entity) {
        Seller seller = new Seller();
        seller.setId(entity.getId());
        seller.setUserId(entity.getUserId());
        seller.setBusinessName(entity.getBusinessName());
        seller.setBusinessNumber(entity.getBusinessNumber());
        seller.setRepresentativeName(entity.getRepresentativeName());
        seller.setPhone(entity.getPhone());
        seller.setEmail(entity.getEmail());
        seller.setBankName(entity.getBankName());
        seller.setBankAccountNumber(entity.getBankAccountNumber());
        seller.setBankAccountHolder(entity.getBankAccountHolder());
        seller.setCommissionRate(entity.getCommissionRate());
        seller.setStatus(SellerStatus.fromString(entity.getStatus()));
        seller.setApprovedAt(entity.getApprovedAt());
        seller.setCreatedAt(entity.getCreatedAt());
        seller.setUpdatedAt(entity.getUpdatedAt());
        seller.setSettlementCycle(
            entity.getSettlementCycle() != null
                ? github.lms.lemuel.seller.domain.SettlementCycle.valueOf(entity.getSettlementCycle())
                : github.lms.lemuel.seller.domain.SettlementCycle.DAILY);
        if (entity.getWeeklySettlementDay() != null) {
            seller.setWeeklySettlementDay(java.time.DayOfWeek.valueOf(entity.getWeeklySettlementDay()));
        }
        seller.setMonthlySettlementDay(entity.getMonthlySettlementDay());
        seller.setMinimumWithdrawalAmount(entity.getMinimumWithdrawalAmount());
        return seller;
    }

    public static SellerJpaEntity toEntity(Seller domain) {
        SellerJpaEntity entity = new SellerJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setBusinessName(domain.getBusinessName());
        entity.setBusinessNumber(domain.getBusinessNumber());
        entity.setRepresentativeName(domain.getRepresentativeName());
        entity.setPhone(domain.getPhone());
        entity.setEmail(domain.getEmail());
        entity.setBankName(domain.getBankName());
        entity.setBankAccountNumber(domain.getBankAccountNumber());
        entity.setBankAccountHolder(domain.getBankAccountHolder());
        entity.setCommissionRate(domain.getCommissionRate());
        entity.setStatus(domain.getStatus().name());
        entity.setApprovedAt(domain.getApprovedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setSettlementCycle(
            domain.getSettlementCycle() != null ? domain.getSettlementCycle().name() : "DAILY");
        entity.setWeeklySettlementDay(
            domain.getWeeklySettlementDay() != null ? domain.getWeeklySettlementDay().name() : null);
        entity.setMonthlySettlementDay(domain.getMonthlySettlementDay());
        entity.setMinimumWithdrawalAmount(domain.getMinimumWithdrawalAmount());
        return entity;
    }
}
