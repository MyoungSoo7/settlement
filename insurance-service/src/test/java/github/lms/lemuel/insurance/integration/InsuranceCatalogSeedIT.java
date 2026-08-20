package github.lms.lemuel.insurance.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GA 플랫폼이 <b>기동 직후 상담 이후를 진행할 수 있는지</b> 검증한다.
 *
 * <p>insurance-service 에는 상품을 등록하는 API 도 유스케이스도 없다 — 조회(상품설명서 교부)만
 * 있다. 즉 {@code insurance_products} 에 행을 넣을 수 있는 경로가 애플리케이션에 존재하지 않으며,
 * 마이그레이션 시드가 유일한 투입 경로다. 그런데 시드가 없어서 상품 0행으로 뜬다.
 *
 * <p>상품이 0행이면 가입설계({@code CreateProposalQuoteService} → {@code LoadInsuranceProductPort})가
 * 시작부터 막히고, 설계가 없으면 청약·계약·수수료까지 전 체인이 성립하지 않는다. 화면이 비어 보이는
 * 정도가 아니라 <b>도메인 전체가 실행 불가</b>다.
 *
 * <p>요율도 함께 본다. {@code PremiumRater} 는 요율이 없으면 {@code RateNotFoundException} 을 던지고
 * 임의 기본값으로 메꾸지 않는다(그게 정책이다). 그래서 상품만 넣고 요율을 빼면 설계는 여전히 불가능하다
 * — 시드가 "절반만" 채워지는 흔한 실패를 여기서 막는다.
 */
class InsuranceCatalogSeedIT extends InsuranceIntegrationTestSupport {

    /**
     * V12 시드가 넣는 상품 코드. 통합테스트는 컨테이너·DB 를 공유하고 다른 IT
     * ({@code UnderwritingFlowIT}·{@code InsuranceBatchFlowIT})가 자기 픽스처 상품을 직접
     * INSERT 하므로, "활성 상품 전부"를 대상으로 삼으면 남의 픽스처까지 끌어와 판정이 흔들린다.
     * 여기서는 시드가 책임지는 범위만 본다.
     */
    private static final String SEEDED_CODES =
            "('LIFE-TERM-20','LIFE-WHOLE-01','HEALTH-CI-01','AUTO-STD-01','FIRE-HOME-01')";

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("판매 가능한 보험 상품이 시드돼 있다")
    void activeProductsAreSeeded() throws Exception {
        long active = scalar("SELECT COUNT(*) FROM opslab.insurance_products"
                + " WHERE active = TRUE AND product_code IN " + SEEDED_CODES);

        assertThat(active)
                .as("상품 등록 API 가 없으므로 시드가 없으면 영원히 0행이고, 가입설계가 시작조차 못 한다")
                .isGreaterThanOrEqualTo(4L);
    }

    @Test
    @DisplayName("여러 상품 유형을 덮어 유형별 분기가 실제로 실행된다")
    void seededProductsCoverMultipleTypes() throws Exception {
        List<String> types = strings("SELECT DISTINCT product_type FROM opslab.insurance_products"
                + " WHERE active = TRUE AND product_code IN " + SEEDED_CODES);

        assertThat(types)
                .as("한 유형만 있으면 나머지 유형의 설계·수수료 경로는 한 번도 밟히지 않는다")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(types).allSatisfy(t -> assertThat(t)
                .isIn("LIFE", "HEALTH", "AUTO", "FIRE", "TRAVEL", "PENSION"));
    }

    @Test
    @DisplayName("시드된 모든 상품에 요율이 있다 (상품만 있고 요율이 없으면 설계는 여전히 불가)")
    void everySeededProductHasRates() throws Exception {
        List<String> withoutRates = strings("""
                SELECT p.product_code
                  FROM opslab.insurance_products p
                 WHERE p.active = TRUE
                   AND p.product_code IN %s
                   AND NOT EXISTS (
                        SELECT 1 FROM opslab.premium_rate_tables r WHERE r.product_code = p.product_code
                   )
                """.formatted(SEEDED_CODES));

        assertThat(withoutRates)
                .as("PremiumRater 는 요율이 없으면 RateNotFoundException 을 던진다 — 폴백이 없는 게 정책이다")
                .isEmpty();
    }

    @Test
    @DisplayName("요율이 남녀 모두와 실제 가입 연령대를 덮는다")
    void ratesCoverBothGendersAndCommonAges() throws Exception {
        List<String> genders = strings("SELECT DISTINCT gender FROM opslab.premium_rate_tables"
                + " WHERE product_code IN " + SEEDED_CODES);
        assertThat(genders).as("한쪽 성별만 있으면 나머지 피보험자는 설계 자체가 실패한다")
                .containsExactlyInAnyOrder("M", "F");

        // 30·45·60세는 어느 요율표에서나 있어야 할 대표 연령이다.
        for (int age : new int[]{30, 45, 60}) {
            long bands = scalar("SELECT COUNT(*) FROM opslab.premium_rate_tables"
                    + " WHERE product_code IN " + SEEDED_CODES
                    + " AND age_from <= " + age + " AND age_to >= " + age);
            assertThat(bands).as("보험나이 %d 를 덮는 요율 구간", age).isGreaterThan(0L);
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> strings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
