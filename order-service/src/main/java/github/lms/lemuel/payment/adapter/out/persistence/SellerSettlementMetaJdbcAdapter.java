package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * order-service 측 셀러 메타 해석 어댑터.
 *
 * <p>payments→orders→products 로 seller_id 를, users 로 seller_tier·settlement_cycle 을 해석한다.
 * order-service 가 소유한 opslab 테이블만 조회하므로 헥사고날·MSA 경계에 부합한다.
 * (settlement 의 SellerTierJdbcAdapter 가 하던 cross-service 조인을 발행 측으로 옮긴 것.)
 *
 * <p>products→users 는 LEFT JOIN — 셀러 미할당(seller_id NULL)이어도 seller_id=null 로 row 반환.
 * payment/order/product 체인 자체가 없으면 empty.
 */
@Repository
public class SellerSettlementMetaJdbcAdapter implements LoadSellerSettlementMetaPort {

    private final JdbcTemplate jdbcTemplate;

    public SellerSettlementMetaJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SellerSettlementMeta> findByPaymentId(Long paymentId) {
        String sql = """
                SELECT pr.seller_id      AS seller_id,
                       u.seller_tier     AS seller_tier,
                       u.settlement_cycle AS settlement_cycle
                FROM opslab.payments pay
                JOIN opslab.orders o   ON o.id = pay.order_id
                JOIN opslab.products pr ON pr.id = o.product_id
                LEFT JOIN opslab.users u ON u.id = pr.seller_id
                WHERE pay.id = ?
                """;
        try {
            SellerSettlementMeta meta = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                long sellerId = rs.getLong("seller_id");
                Long resolvedSellerId = rs.wasNull() ? null : sellerId;
                return new SellerSettlementMeta(
                        resolvedSellerId,
                        rs.getString("seller_tier"),
                        rs.getString("settlement_cycle"));
            }, paymentId);
            return Optional.ofNullable(meta);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
