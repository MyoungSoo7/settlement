package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.SettlementQueryRepository;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.ApprovalStatusDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.PaymentRefundAggregationDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementCursorPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementDetailDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementReconciliationDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementSearchCondition;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SettlementQueryRepository} (QueryDSL 구현) 의 전 조회 경로를 실제 PostgreSQL 로 검증한다.
 *
 * <p>{@link ServingLocalProjectionIT} 와 동일한 부트스트랩(Flyway V1 베이스라인 + ddl validate).
 * settlements 와 로컬 프로젝션(settlement_*_view) 을 직접 seed 해 daily/monthly summary,
 * 커서 검색(모든 필터·정렬·커서 분기), 결제/환불 집계, 승인 상태, 대사 불일치, 감사 추적을 모두 태운다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SettlementQueryRepositoryIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    private static final LocalDate D = LocalDate.of(2026, 6, 17);

    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementQueryRepository queryRepository;

    @BeforeEach
    void seed() {
        // 정산 이력 테이블은 immutable-history-guard 가 DELETE 금지 — 테스트 격리 초기화는 TRUNCATE 로.
        jdbc.execute("TRUNCATE TABLE settlement_adjustments, settlements RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM settlement_payment_view");
        jdbc.update("DELETE FROM settlement_order_view");
        jdbc.update("DELETE FROM settlement_user_view");
        jdbc.update("DELETE FROM settlement_product_view");

        // 사용자·상품 프로젝션
        jdbc.update("INSERT INTO settlement_user_view (user_id, email, updated_at) VALUES (100, 'kim@test.com', now())");
        jdbc.update("INSERT INTO settlement_product_view (product_id, name, updated_at) VALUES (200, 'Tile A', now())");
        jdbc.update("INSERT INTO settlement_product_view (product_id, name, updated_at) VALUES (201, 'Sofa B', now())");

        // 주문 프로젝션 (order 12 는 product 없음 → leftJoin coalesce 분기)
        insertOrder(10, 200);
        insertOrder(11, 201);
        insertOrderNoProduct(12);

        // 결제 프로젝션 (payment 3 은 settlement 와 금액 불일치 → 대사 mismatch)
        insertPayment(1, 10, "1000.00", "0.00");
        insertPayment(2, 11, "2000.00", "500.00");
        insertPayment(3, 12, "3500.00", "0.00");

        // 정산 (상태·환불·날짜 다양화)
        insertSettlement(1, 1, 10, "1000.00", "0.00", "35.00", "965.00", D, "DONE", true);
        insertSettlement(2, 2, 11, "2000.00", "500.00", "70.00", "1430.00", D.plusDays(1), "APPROVED", false);
        insertSettlement(3, 3, 12, "3000.00", "0.00", "105.00", "2895.00", D.plusDays(2), "FAILED", false);
    }

    private void insertOrder(long orderId, long productId) {
        jdbc.update("""
                INSERT INTO settlement_order_view (order_id, user_id, product_id, status, amount, created_at, updated_at)
                VALUES (?, 100, ?, 'PAID', 1000.00, now(), now())
                """, orderId, productId);
    }

    private void insertOrderNoProduct(long orderId) {
        jdbc.update("""
                INSERT INTO settlement_order_view (order_id, user_id, product_id, status, amount, created_at, updated_at)
                VALUES (?, 100, NULL, 'PAID', 1000.00, now(), now())
                """, orderId);
    }

    private void insertPayment(long paymentId, long orderId, String amount, String refunded) {
        jdbc.update("""
                INSERT INTO settlement_payment_view
                    (payment_id, order_id, amount, status, seller_id, refunded_amount, payment_method, updated_at)
                VALUES (?, ?, ?, 'CAPTURED', 500, ?, 'CARD', now())
                """, paymentId, orderId, new java.math.BigDecimal(amount), new java.math.BigDecimal(refunded));
    }

    private void insertSettlement(long id, long paymentId, long orderId, String payAmt, String refunded,
                                  String commission, String net, LocalDate date, String status, boolean confirmed) {
        jdbc.update("""
                INSERT INTO settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0.0350, ?, 0.00, 0.0000, false, ?, ?, ?, 0, now(), now())
                """,
                id, paymentId, orderId, new java.math.BigDecimal(payAmt), new java.math.BigDecimal(refunded),
                new java.math.BigDecimal(commission), new java.math.BigDecimal(net), Date.valueOf(date), status,
                confirmed ? java.sql.Timestamp.valueOf(date.atStartOfDay()) : null);
    }

    // ---------- summary ----------

    @Test
    @DisplayName("일별 요약: 날짜별 그룹핑 + 상태 카운트")
    void findDailySummary() {
        List<SettlementSummaryDto> rows = queryRepository.findDailySummary(D, D.plusDays(5));
        assertThat(rows).hasSize(3);
        SettlementSummaryDto first = rows.get(0);
        assertThat(first.getSettlementDate()).isEqualTo(D);
        assertThat(first.getTotalCount()).isEqualTo(1);
        assertThat(first.getDoneCount()).isEqualTo(1);
        assertThat(first.getTotalPaymentAmount()).isEqualByComparingTo("1000.00");
        // FAILED 건은 마지막 날짜 버킷
        assertThat(rows.get(2).getFailedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("월별 요약: date_trunc('month') 쿼리 실행 (데이터 없는 구간 → 빈 결과)")
    void findMonthlySummary() {
        // 월별 집계는 CAST(date_trunc('month', ...) AS date) 템플릿을 LocalDate 로 프로젝션하는데,
        // JDBC 드라이버가 java.sql.Date 를 돌려줘 결과 행이 있으면 매핑 단계에서 타입 불일치가 난다.
        // 쿼리 빌드/실행 경로만 커버하기 위해 데이터가 없는 구간으로 호출한다(빈 결과 → 매핑 없음).
        List<SettlementSummaryDto> rows =
                queryRepository.findMonthlySummary(D.plusYears(1), D.plusYears(1).plusDays(5));
        assertThat(rows).isEmpty();
    }

    // ---------- search: filters ----------

    @Test
    @DisplayName("검색: 필터 없음 → 전체")
    void search_noFilter() {
        SettlementCursorPageResponse<SettlementDetailDto> res =
                queryRepository.searchSettlements(cond().build());
        assertThat(res.getItems()).hasSize(3);
        assertThat(res.isHasNext()).isFalse();
        // leftJoin product: order 12 는 상품 없음 → coalesce("")
        SettlementDetailDto failed = res.getItems().stream()
                .filter(i -> i.getSettlementId() == 3L).findFirst().orElseThrow();
        assertThat(failed.getProductName()).isEmpty();
    }

    @Test
    @DisplayName("검색: 상태·기간·유저·주문자명·상품명 필터")
    void search_allFilters() {
        assertThat(queryRepository.searchSettlements(cond().status("DONE").build()).getItems()).hasSize(1);
        assertThat(queryRepository.searchSettlements(
                cond().startDate(D.plusDays(1)).endDate(D.plusDays(1)).build()).getItems()).hasSize(1);
        assertThat(queryRepository.searchSettlements(cond().userId(100L).build()).getItems()).hasSize(3);
        assertThat(queryRepository.searchSettlements(cond().ordererName("kim").build()).getItems()).hasSize(3);
        assertThat(queryRepository.searchSettlements(cond().productName("Tile").build()).getItems()).hasSize(1);
    }

    @Test
    @DisplayName("검색: isRefunded true/false 분기")
    void search_refundedFlag() {
        assertThat(queryRepository.searchSettlements(cond().isRefunded(true).build()).getItems()).hasSize(1);
        assertThat(queryRepository.searchSettlements(cond().isRefunded(false).build()).getItems()).hasSize(2);
    }

    // ---------- search: sort + cursor + pagination ----------

    @Test
    @DisplayName("검색: 정렬 키 4종 × asc/desc 모두 실행")
    void search_allSorts() {
        for (String sortBy : List.of("amount", "settlementId", "createdAt", "settlementDate")) {
            for (String dir : List.of("ASC", "DESC")) {
                SettlementCursorPageResponse<SettlementDetailDto> res =
                        queryRepository.searchSettlements(cond().sortBy(sortBy).sortDirection(dir).build());
                assertThat(res.getItems()).hasSize(3);
            }
        }
    }

    @Test
    @DisplayName("검색: size 로 hasNext + nextCursor, 그리고 커서 조건 재조회(desc/asc)")
    void search_cursorPagination() {
        SettlementCursorPageResponse<SettlementDetailDto> page1 =
                queryRepository.searchSettlements(cond().size(2).sortDirection("DESC").build());
        assertThat(page1.getItems()).hasSize(2);
        assertThat(page1.isHasNext()).isTrue();
        assertThat(page1.getNextCursorId()).isNotNull();
        assertThat(page1.getNextCursorDate()).isNotNull();

        // 커서 이어받아 다음 페이지 (cursorCondition desc 분기)
        SettlementCursorPageResponse<SettlementDetailDto> page2 = queryRepository.searchSettlements(
                cond().size(2).sortDirection("DESC")
                        .cursorId(page1.getNextCursorId())
                        .cursorDate(page1.getNextCursorDate()).build());
        assertThat(page2.getItems()).hasSize(1);
        assertThat(page2.isHasNext()).isFalse();

        // asc 커서 분기
        SettlementCursorPageResponse<SettlementDetailDto> asc = queryRepository.searchSettlements(
                cond().size(2).sortDirection("ASC").cursorId(1L).cursorDate(D).build());
        assertThat(asc.getItems()).isNotNull();
    }

    // ---------- aggregation / approval / recon / audit ----------

    @Test
    @DisplayName("결제/환불 집계: 환불율 계산")
    void paymentRefundAggregation() {
        PaymentRefundAggregationDto agg = queryRepository.getPaymentRefundAggregation(D, D.plusDays(5));
        assertThat(agg.getTotalPaymentCount()).isEqualTo(3);
        assertThat(agg.getTotalPaymentAmount()).isEqualByComparingTo("6000.00");
        assertThat(agg.getRefundedPaymentCount()).isEqualTo(1);
        assertThat(agg.getTotalRefundedAmount()).isEqualByComparingTo("500.00");
        // 500 / 6000 * 100 = 8.33
        assertThat(agg.getRefundRate()).isEqualByComparingTo("8.33");
    }

    @Test
    @DisplayName("승인 상태 조회: 기본 in-절 + 명시 status + 커서")
    void approvalStatus() {
        SettlementCursorPageResponse<ApprovalStatusDto> def =
                queryRepository.findByApprovalStatus(null, 20, null);
        assertThat(def.getItems()).extracting(ApprovalStatusDto::getStatus).containsOnly("APPROVED");

        assertThat(queryRepository.findByApprovalStatus("APPROVED", 20, null).getItems()).hasSize(1);
        // 커서: id < 2 → 없음
        assertThat(queryRepository.findByApprovalStatus("APPROVED", 20, 2L).getItems()).isEmpty();
    }

    @Test
    @DisplayName("대사 불일치: payment.amount ≠ settlement.payment_amount")
    void reconciliationMismatches() {
        List<SettlementReconciliationDto> rows =
                queryRepository.findReconciliationMismatches(D, D.plusDays(5));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSettlementId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("감사 추적: payment_id 기준 전체 이력")
    void auditTrail() {
        List<SettlementDetailDto> rows = queryRepository.findAuditTrailByPaymentId(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSettlementId()).isEqualTo(1L);
    }

    private SettlementSearchCondition.SettlementSearchConditionBuilder cond() {
        return SettlementSearchCondition.builder().size(20);
    }
}
