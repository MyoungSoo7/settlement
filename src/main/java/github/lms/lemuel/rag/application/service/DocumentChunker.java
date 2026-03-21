package github.lms.lemuel.rag.application.service;

import github.lms.lemuel.rag.domain.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentChunker {

    private final JdbcTemplate jdbcTemplate;

    public record ChunkData(EntityType entityType, Long entityId, String content) {}

    public List<ChunkData> chunkAll() {
        List<ChunkData> chunks = new ArrayList<>();
        chunks.addAll(chunkProducts());
        chunks.addAll(chunkReviews());
        chunks.addAll(chunkOrders());
        chunks.addAll(chunkSettlements());
        return chunks;
    }

    private List<ChunkData> chunkProducts() {
        String sql = "SELECT id, name, description, price, stock_quantity, status FROM opslab.products";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.PRODUCT, rs.getLong("id"),
                        String.format("[상품] 이름: %s, 설명: %s, 가격: %d원, 재고: %d, 상태: %s",
                                rs.getString("name"),
                                rs.getString("description") != null ? rs.getString("description") : "없음",
                                rs.getLong("price"),
                                rs.getInt("stock_quantity"),
                                rs.getString("status"))));
    }

    private List<ChunkData> chunkReviews() {
        String sql = """
            SELECT r.id, r.rating, r.content, p.name AS product_name
            FROM opslab.reviews r
            JOIN opslab.products p ON r.product_id = p.id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.REVIEW, rs.getLong("id"),
                        String.format("[리뷰] 상품: %s, 평점: %d/5, 내용: %s",
                                rs.getString("product_name"),
                                rs.getInt("rating"),
                                rs.getString("content") != null ? rs.getString("content") : "없음")));
    }

    private List<ChunkData> chunkOrders() {
        String sql = """
            SELECT o.id, o.amount, o.status, o.created_at, p.name AS product_name
            FROM opslab.orders o
            LEFT JOIN opslab.products p ON o.product_id = p.id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.ORDER, rs.getLong("id"),
                        String.format("[주문] 주문번호: %d, 상품: %s, 금액: %d원, 상태: %s, 일시: %s",
                                rs.getLong("id"),
                                rs.getString("product_name") != null ? rs.getString("product_name") : "미지정",
                                rs.getLong("amount"),
                                rs.getString("status"),
                                rs.getTimestamp("created_at"))));
    }

    private List<ChunkData> chunkSettlements() {
        String sql = """
            SELECT s.id, s.amount, s.status, s.settlement_date, s.created_at
            FROM opslab.settlements s
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.SETTLEMENT, rs.getLong("id"),
                        String.format("[정산] 정산번호: %d, 금액: %d원, 상태: %s, 정산일: %s",
                                rs.getLong("id"),
                                rs.getLong("amount"),
                                rs.getString("status"),
                                rs.getDate("settlement_date"))));
    }
}
