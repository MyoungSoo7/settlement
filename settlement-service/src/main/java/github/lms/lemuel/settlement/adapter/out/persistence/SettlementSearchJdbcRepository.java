package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementAggregationsResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementSearchItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SettlementSearchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementPageResponse search(
            String ordererName, String productName, Boolean isRefunded,
            String status, String startDate, String endDate,
            int page, int size, String sortBy, String sortDirection
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            where.append(" AND s.status = ?");
            params.add(status);
        }
        if (startDate != null && !startDate.isBlank()) {
            where.append(" AND s.settlement_date >= CAST(? AS DATE)");
            params.add(startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            where.append(" AND s.settlement_date <= CAST(? AS DATE)");
            params.add(endDate);
        }
        if (ordererName != null && !ordererName.isBlank()) {
            where.append(" AND u.email LIKE ?");
            params.add("%" + ordererName + "%");
        }
        if (productName != null && !productName.isBlank()) {
            where.append(" AND pr.name LIKE ?");
            params.add("%" + productName + "%");
        }
        if (Boolean.TRUE.equals(isRefunded)) {
            where.append(" AND py.refunded_amount > 0");
        } else if (Boolean.FALSE.equals(isRefunded)) {
            where.append(" AND py.refunded_amount = 0");
        }

        String fromClause =
                " FROM settlements s" +
                " JOIN orders o ON s.order_id = o.id" +
                " JOIN payments py ON s.payment_id = py.id" +
                " JOIN users u ON o.user_id = u.id" +
                " LEFT JOIN products pr ON o.product_id = pr.id" +
                where;

        Object[] baseArgs = params.toArray();

        // 총 건수
        Long totalElements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + fromClause, Long.class, baseArgs);
        if (totalElements == null) totalElements = 0L;

        // 합계 집계
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalRefundedAmount = BigDecimal.ZERO;
        BigDecimal totalFinalAmount = BigDecimal.ZERO;
        List<BigDecimal[]> aggRows = jdbcTemplate.query(
                "SELECT COALESCE(SUM(s.payment_amount),0), COALESCE(SUM(py.refunded_amount),0), COALESCE(SUM(s.net_amount),0)" + fromClause,
                (rs, n) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)},
                baseArgs);
        if (!aggRows.isEmpty()) {
            totalAmount = aggRows.get(0)[0];
            totalRefundedAmount = aggRows.get(0)[1];
            totalFinalAmount = aggRows.get(0)[2];
        }

        // 상태별 건수
        Map<String, Long> statusCounts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT s.status, COUNT(*) AS cnt" + fromClause + " GROUP BY s.status",
                (RowCallbackHandler) rs -> statusCounts.put(rs.getString("status"), rs.getLong("cnt")),
                baseArgs);

        // 정렬 컬럼
        String orderColumn = switch (sortBy != null ? sortBy : "createdAt") {
            case "amount"         -> "s.payment_amount";
            case "settlementDate" -> "s.settlement_date";
            case "settlementId"   -> "s.id";
            default               -> "s.created_at";
        };
        String orderDir = "ASC".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";

        // 페이지 조회
        String selectSql =
                "SELECT s.id, s.order_id, s.payment_id," +
                " u.email AS orderer_name," +
                " COALESCE(pr.name, '') AS product_name," +
                " s.payment_amount, py.refunded_amount, s.net_amount," +
                " s.status, s.settlement_date" +
                fromClause +
                " ORDER BY " + orderColumn + " " + orderDir +
                " LIMIT ? OFFSET ?";

        List<Object> pageArgs = new ArrayList<>(params);
        pageArgs.add(size);
        pageArgs.add((long) page * size);

        List<SettlementSearchItemResponse> items = jdbcTemplate.query(
                selectSql,
                (rs, n) -> new SettlementSearchItemResponse(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getLong("payment_id"),
                        rs.getString("orderer_name"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("payment_amount"),
                        rs.getBigDecimal("refunded_amount"),
                        rs.getBigDecimal("net_amount"),
                        rs.getString("status"),
                        rs.getBigDecimal("refunded_amount").compareTo(BigDecimal.ZERO) > 0,
                        rs.getObject("settlement_date", Date.class) != null
                                ? rs.getObject("settlement_date", Date.class).toLocalDate()
                                : null
                ),
                pageArgs.toArray());

        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new SettlementPageResponse(
                items,
                totalElements,
                totalPages,
                page,
                size,
                new SettlementAggregationsResponse(totalAmount, totalRefundedAmount, totalFinalAmount, statusCounts)
        );
    }
}