package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.PayoutBackfillQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 미생성 Payout 백필용 JDBC 어댑터.
 *
 * <p>INV-6 {@code settlementsWithoutPayout} 쿼리를 재사용(탐지 로직 중복 없음)해
 * 날짜 범위·커서 기반 페이지로 확장한다. settlement_payment_view 와 JOIN 해 seller_id 와
 * 지급 금액 정보를 함께 가져온다. CANCELED 상태 payout 은 활성 payout 으로 보지 않는다
 * (INV-6 기준과 동일).
 *
 * <p>읽기 전용 — 데이터 변경 없음. 페이지 단위 커밋은 서비스 레이어에서 처리한다.
 */
@Component
@Transactional(readOnly = true)
public class PayoutBackfillQueryJdbcAdapter implements PayoutBackfillQueryPort {

    private final JdbcClient jdbc;

    public PayoutBackfillQueryJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * INV-6 settlementsWithoutPayout SQL 의 페이지 확장판.
     * DONE 정산 중 IMMEDIATE Payout 이 없는 건을 커서 기반 페이지로 조회한다.
     * settlement_payment_view JOIN 으로 seller_id 및 holdback 정보를 함께 가져온다.
     */
    @Override
    public List<SettlementForPayout> findDoneWithoutImmediatePayoutPage(
            LocalDate from, LocalDate to, long afterId, int pageSize) {
        return jdbc.sql("""
                SELECT s.id                       AS settlement_id,
                       s.payment_id,
                       spv.seller_id,
                       s.net_amount,
                       GREATEST(
                           s.net_amount - CASE WHEN s.holdback_released = false
                                              THEN s.holdback_amount ELSE 0 END,
                           0
                       )                          AS immediate_payout_amount,
                       s.holdback_amount,
                       s.holdback_released
                FROM settlements s
                LEFT JOIN settlement_payment_view spv ON spv.payment_id = s.payment_id
                WHERE s.status = 'DONE'
                  AND s.confirmed_at >= :fromDt
                  AND s.confirmed_at  < :toDt
                  AND s.id > :afterId
                  AND NOT EXISTS (
                        SELECT 1 FROM payouts p
                        WHERE p.settlement_id = s.id
                          AND p.payout_type = 'IMMEDIATE'
                          AND p.status <> 'CANCELED'
                  )
                ORDER BY s.id
                LIMIT :pageSize
                """)
                .param("fromDt", from.atStartOfDay())
                .param("toDt", to.plusDays(1).atStartOfDay())
                .param("afterId", afterId)
                .param("pageSize", pageSize)
                .query(this::mapRow)
                .list();
    }

    /**
     * 홀드백이 해제됐지만 HOLDBACK_RELEASE Payout 이 없는 건을 커서 기반 페이지로 조회.
     */
    @Override
    public List<SettlementForPayout> findDoneWithoutHoldbackReleasePayoutPage(
            LocalDate from, LocalDate to, long afterId, int pageSize) {
        return jdbc.sql("""
                SELECT s.id                       AS settlement_id,
                       s.payment_id,
                       spv.seller_id,
                       s.net_amount,
                       0                          AS immediate_payout_amount,
                       s.holdback_amount,
                       s.holdback_released
                FROM settlements s
                LEFT JOIN settlement_payment_view spv ON spv.payment_id = s.payment_id
                WHERE s.status = 'DONE'
                  AND s.confirmed_at >= :fromDt
                  AND s.confirmed_at  < :toDt
                  AND s.id > :afterId
                  AND s.holdback_released = true
                  AND s.holdback_amount > 0
                  AND NOT EXISTS (
                        SELECT 1 FROM payouts p
                        WHERE p.settlement_id = s.id
                          AND p.payout_type = 'HOLDBACK_RELEASE'
                          AND p.status <> 'CANCELED'
                  )
                ORDER BY s.id
                LIMIT :pageSize
                """)
                .param("fromDt", from.atStartOfDay())
                .param("toDt", to.plusDays(1).atStartOfDay())
                .param("afterId", afterId)
                .param("pageSize", pageSize)
                .query(this::mapRow)
                .list();
    }

    @Override
    public long countDoneWithoutImmediatePayout(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT count(*)
                FROM settlements s
                WHERE s.status = 'DONE'
                  AND s.confirmed_at >= :fromDt
                  AND s.confirmed_at  < :toDt
                  AND NOT EXISTS (
                        SELECT 1 FROM payouts p
                        WHERE p.settlement_id = s.id
                          AND p.payout_type = 'IMMEDIATE'
                          AND p.status <> 'CANCELED'
                  )
                """)
                .param("fromDt", from.atStartOfDay())
                .param("toDt", to.plusDays(1).atStartOfDay())
                .query(Long.class)
                .single();
    }

    @Override
    public long countDoneWithoutHoldbackReleasePayout(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT count(*)
                FROM settlements s
                WHERE s.status = 'DONE'
                  AND s.confirmed_at >= :fromDt
                  AND s.confirmed_at  < :toDt
                  AND s.holdback_released = true
                  AND s.holdback_amount > 0
                  AND NOT EXISTS (
                        SELECT 1 FROM payouts p
                        WHERE p.settlement_id = s.id
                          AND p.payout_type = 'HOLDBACK_RELEASE'
                          AND p.status <> 'CANCELED'
                  )
                """)
                .param("fromDt", from.atStartOfDay())
                .param("toDt", to.plusDays(1).atStartOfDay())
                .query(Long.class)
                .single();
    }

    private SettlementForPayout mapRow(ResultSet rs, int i) throws SQLException {
        BigDecimal immediateAmt = rs.getBigDecimal("immediate_payout_amount");
        if (immediateAmt == null) immediateAmt = BigDecimal.ZERO;
        BigDecimal holdbackAmt = rs.getBigDecimal("holdback_amount");
        if (holdbackAmt == null) holdbackAmt = BigDecimal.ZERO;
        Long sellerId = rs.getLong("seller_id");
        if (rs.wasNull()) sellerId = null;

        return new SettlementForPayout(
                rs.getLong("settlement_id"),
                rs.getLong("payment_id"),
                sellerId,
                rs.getBigDecimal("net_amount"),
                immediateAmt,
                holdbackAmt,
                rs.getBoolean("holdback_released")
        );
    }
}
