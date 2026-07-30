package github.lms.lemuel.loan.adapter.out.external;

import github.lms.lemuel.loan.application.port.out.CollateralValuationPort.ValuationClaim;
import github.lms.lemuel.loan.domain.CollateralType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 위성 담보평가 클라이언트({@link SatelliteCollateralValuationAdapter}) 단위 테스트.
 *
 * <p>핵심은 <b>폴백 경로가 넓다</b>는 것(기준금리 어댑터와 동일 원칙) — 시세 미수집·서비스 미기동·
 * 조회 키 부재·파싱 실패 어느 경우든 평가 조달 실패가 대출 신청을 막으면 안 되므로 신청자 제시값으로
 * 폴백한다. 실측값은 정상 조회 시에만 담보 평가 스냅샷의 출처가 된다.
 */
class SatelliteCollateralValuationAdapterTest {

    private static final String MARKET = "http://localhost:8094";
    private static final String COMMON = "http://localhost:8098";
    private static final BigDecimal DECLARED = new BigDecimal("50000000");

    private record Fixture(SatelliteCollateralValuationAdapter adapter, MockRestServiceServer server,
                           io.micrometer.core.instrument.MeterRegistry registry) {
        /** 폴백 계량 총합 — 제시값 폴백은 담보를 신청인 주장대로 인정한다는 뜻이라 지표로 드러나야 한다. */
        double fallbackCount() {
            return registry.find("loan.collateral.valuation.fallback").counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        }
    }

    private Fixture newAdapter(String realEstateSource) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        io.micrometer.core.instrument.MeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        SatelliteCollateralValuationAdapter adapter = new SatelliteCollateralValuationAdapter(
                builder, registry,
                MARKET, COMMON, realEstateSource, "dealAmount", new BigDecimal("10000"), 100);
        return new Fixture(adapter, server, registry);
    }

    /**
     * 서류값이 정본인 담보(예금·채권·보증서)는 marketRef 가 없는 게 정상이다 — 폴백이 아니다.
     * 여기서 계량하면 정상 트래픽이 폴백 지표를 부풀려 알림을 오탐으로 만든다(코드리뷰 지적).
     */
    @Test
    @DisplayName("서류값 정본 담보는 조회 키가 없어도 폴백으로 계량하지 않는다")
    void documentValuedTypes_doNotCountAsFallback() {
        Fixture f = newAdapter("");

        for (CollateralType type : new CollateralType[]{
                CollateralType.DEPOSIT, CollateralType.BOND, CollateralType.GUARANTEE}) {
            BigDecimal appraised = f.adapter().appraise(
                    new ValuationClaim(type, "서류 담보", DECLARED, null, null));
            assertThat(appraised).isEqualByComparingTo(DECLARED);
        }
        assertThat(f.fallbackCount()).isZero();
    }

    private static ValuationClaim equityClaim(Long quantity) {
        return new ValuationClaim(CollateralType.EQUITY, "삼성전자 1,000주", DECLARED, "005930", quantity);
    }

    // ─── EQUITY: market-service 시가 ─────────────────────────────────────────

    @Test
    @DisplayName("주식 담보는 최신 종가 × 수량으로 평가한다")
    void equity_appraisedByClosePriceTimesQuantity() {
        Fixture f = newAdapter("");
        f.server().expect(requestTo(MARKET + "/api/market/stocks/005930/latest"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"stockCode":"005930","name":"삼성전자","market":"KOSPI",
                         "latest":{"baseDate":"2026-07-29","closePrice":71000,"volume":12345678}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.adapter().appraise(equityClaim(1000L))).isEqualByComparingTo("71000000.00");
        f.server().verify();
    }

    @Test
    @DisplayName("시세 관측치가 없으면(latest=null) 제시값으로 폴백한다")
    void equity_nullLatest_fallsBack() {
        Fixture f = newAdapter("");
        f.server().expect(requestTo(MARKET + "/api/market/stocks/005930/latest"))
                .andRespond(withSuccess("""
                        {"stockCode":"005930","name":"삼성전자","market":"KOSPI","latest":null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.adapter().appraise(equityClaim(1000L))).isEqualByComparingTo(DECLARED);
    }

    @Test
    @DisplayName("market 장애(5xx)도 제시값으로 폴백한다 — 시세 장애가 대출 신청을 막지 않는다")
    void equity_serverError_fallsBack() {
        Fixture f = newAdapter("");
        f.server().expect(requestTo(MARKET + "/api/market/stocks/005930/latest"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThat(f.adapter().appraise(equityClaim(1000L))).isEqualByComparingTo(DECLARED);
    }

    @Test
    @DisplayName("수량이 없으면 시세를 조회하지 않고 제시값을 쓴다")
    void equity_withoutQuantity_usesDeclared() {
        Fixture f = newAdapter("");

        assertThat(f.adapter().appraise(equityClaim(null))).isEqualByComparingTo(DECLARED);
        f.server().verify();   // HTTP 호출 0건
    }

    @Test
    @DisplayName("조회 키가 없으면 위성 조회 없이 제시값이 확정된다")
    void withoutMarketRef_usesDeclared() {
        Fixture f = newAdapter("");
        ValuationClaim claim = ValuationClaim.declared(CollateralType.EQUITY, "비상장 주식", DECLARED);

        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo(DECLARED);
        f.server().verify();
    }

    // ─── REAL_ESTATE: commondata 실거래가 ────────────────────────────────────

    @Test
    @DisplayName("주택 담보는 조회 키로 매칭된 최신 실거래가(만원 콤마 문자열 × 단위 배수)로 평가한다")
    void realEstate_appraisedByLatestDealAmount() {
        Fixture f = newAdapter("molit-apt-trade");
        f.server().expect(requestTo(COMMON + "/api/common-data/sources/molit-apt-trade/records?limit=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"sourceCode":"molit-apt-trade","count":3,"records":[
                          {"recordKey":"11680-래미안-2026-05","collectedAt":"2026-06-01T09:00:00Z","data":{"dealAmount":"79,000"}},
                          {"recordKey":"11680-래미안-2026-06","collectedAt":"2026-07-01T09:00:00Z","data":{"dealAmount":"84,000"}},
                          {"recordKey":"11500-다른단지-2026-06","collectedAt":"2026-07-02T09:00:00Z","data":{"dealAmount":"120,000"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        ValuationClaim claim = new ValuationClaim(CollateralType.REAL_ESTATE, "서울시 강남구 래미안",
                DECLARED, "11680-래미안", null);

        // 매칭 2건 중 최신(2026-07-01) 의 84,000만원 × 10000 = 840,000,000
        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo("840000000.00");
        f.server().verify();
    }

    @Test
    @DisplayName("수집 소스가 미설정이면 실거래 조회 없이 제시값을 쓴다 — API 키 미보유 환경")
    void realEstate_withoutSource_usesDeclared() {
        Fixture f = newAdapter("");
        ValuationClaim claim = new ValuationClaim(CollateralType.REAL_ESTATE, "서울시 강남구 래미안",
                DECLARED, "11680-래미안", null);

        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo(DECLARED);
        f.server().verify();
    }

    @Test
    @DisplayName("매칭 레코드가 없으면 제시값으로 폴백한다")
    void realEstate_noMatch_fallsBack() {
        Fixture f = newAdapter("molit-apt-trade");
        f.server().expect(requestTo(COMMON + "/api/common-data/sources/molit-apt-trade/records?limit=100"))
                .andRespond(withSuccess("""
                        {"sourceCode":"molit-apt-trade","count":1,"records":[
                          {"recordKey":"11500-다른단지-2026-06","collectedAt":"2026-07-02T09:00:00Z","data":{"dealAmount":"120,000"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        ValuationClaim claim = new ValuationClaim(CollateralType.REAL_ESTATE, "서울시 강남구 래미안",
                DECLARED, "11680-래미안", null);

        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo(DECLARED);
    }

    @Test
    @DisplayName("거래금액 파싱 실패 레코드는 건너뛰고 제시값으로 폴백한다")
    void realEstate_unparsableAmount_fallsBack() {
        Fixture f = newAdapter("molit-apt-trade");
        f.server().expect(requestTo(COMMON + "/api/common-data/sources/molit-apt-trade/records?limit=100"))
                .andRespond(withSuccess("""
                        {"sourceCode":"molit-apt-trade","count":1,"records":[
                          {"recordKey":"11680-래미안-2026-06","collectedAt":"2026-07-01T09:00:00Z","data":{"dealAmount":"비공개"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        ValuationClaim claim = new ValuationClaim(CollateralType.REAL_ESTATE, "서울시 강남구 래미안",
                DECLARED, "11680-래미안", null);

        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo(DECLARED);
    }

    // ─── 서류값 담보 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("예금·보증서 담보는 조회 키가 있어도 서류값이 정본이다")
    void deposit_alwaysUsesDeclared() {
        Fixture f = newAdapter("molit-apt-trade");
        ValuationClaim claim = new ValuationClaim(CollateralType.DEPOSIT, "정기예금", DECLARED, "any-ref", null);

        assertThat(f.adapter().appraise(claim)).isEqualByComparingTo(DECLARED);
        f.server().verify();   // HTTP 호출 0건
    }
}
