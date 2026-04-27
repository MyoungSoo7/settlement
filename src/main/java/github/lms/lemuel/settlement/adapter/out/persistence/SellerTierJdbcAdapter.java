package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * payment → order → product → user 경로를 단일 쿼리로 해석해 판매자 메타(등급·주기)를 반환.
 * 판매자 미할당(seller_id NULL) 이거나 매핑 실패 시 empty 반환.
 */
@Repository
public class SellerTierJdbcAdapter implements LoadSellerTierPort, LoadSellerSettlementCyclePort {

    private final JdbcTemplate jdbcTemplate;

    public SellerTierJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SellerTier> findTierByPaymentId(Long paymentId) {
        String sql = """
                SELECT u.seller_tier
                FROM opslab.payments pay
                JOIN opslab.orders o ON o.id = pay.order_id
                JOIN opslab.products pr ON pr.id = o.product_id
                JOIN opslab.users u ON u.id = pr.seller_id
                WHERE pay.id = ?
                """;
        try {
            String tier = jdbcTemplate.queryForObject(sql, String.class, paymentId);
            return Optional.of(SellerTier.fromStringOrDefault(tier));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<SettlementCycle> findCycleByPaymentId(Long paymentId) {
        String sql = """
                SELECT u.settlement_cycle
                FROM opslab.payments pay
                JOIN opslab.orders o ON o.id = pay.order_id
                JOIN opslab.products pr ON pr.id = o.product_id
                JOIN opslab.users u ON u.id = pr.seller_id
                WHERE pay.id = ?
                """;
        try {
            String cycle = jdbcTemplate.queryForObject(sql, String.class, paymentId);
            return Optional.of(SettlementCycle.fromStringOrDefault(cycle));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
