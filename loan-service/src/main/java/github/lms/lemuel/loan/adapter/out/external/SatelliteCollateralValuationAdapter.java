package github.lms.lemuel.loan.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import github.lms.lemuel.loan.application.port.out.CollateralValuationPort;
import github.lms.lemuel.loan.domain.CollateralType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 담보 평가액 Phase 2 실연동 — 위성 서비스 시세·실거래가를 조회하고, 실패 시 제시값으로 폴백한다.
 *
 * <ul>
 *   <li><b>EQUITY(주식)</b>: market-service {@code GET /api/market/stocks/{code}/latest} 의
 *       종가 × 수량. 시세는 T+1 종가라 당일 변동은 다음 영업일에 반영된다 — 신청 시점 스냅샷 원칙과
 *       정합(마진콜 감시는 별도 재평가 경로 소관).</li>
 *   <li><b>REAL_ESTATE(주택)</b>: common-data-service {@code GET /api/common-data/sources/{source}/records}
 *       에서 recordKey 가 조회 키(단지 식별자)로 시작하는 <b>최신 수집 레코드</b>의 거래금액 필드.
 *       국토부 실거래가는 만원 단위 문자열("84,000")이라 콤마 제거 후 단위 배수를 곱한다(설정).
 *       수집 소스 코드가 미설정이면 이 경로는 비활성이다(DATA_GO_KR_API_KEY 미보유 환경).</li>
 *   <li><b>DEPOSIT·BOND·GUARANTEE</b>: 서류값이 정본이다 — 예금액·보증서상 보증금액은 시세가 없고,
 *       채권은 market-service 가 서빙하지 않는다(KRX 주식 시세·시총만). 제시값을 그대로 쓴다.</li>
 * </ul>
 *
 * <p><b>폴백 경로가 넓다</b>(기준금리 {@link EconomicsBaseRateAdapter} 와 동일 원칙): 위성 미기동·
 * 404·데이터 부재·조회 키 부재·파싱 실패 어느 경우든 <b>평가 조달 실패가 대출 신청을 막으면 안 되므로</b>
 * 신청자 제시값으로 폴백하고 경고만 남긴다. 폴백 사용 사실은 로그로 추적한다.
 */
@Component
public class SatelliteCollateralValuationAdapter implements CollateralValuationPort {

    private static final Logger log = LoggerFactory.getLogger(SatelliteCollateralValuationAdapter.class);
    private static final int SCALE = 2;

    private final RestClient marketClient;
    private final RestClient commonDataClient;
    private final String realEstateSourceCode;
    private final String realEstatePriceField;
    private final BigDecimal realEstatePriceUnitMultiplier;
    private final int realEstateRecordsLimit;

    public SatelliteCollateralValuationAdapter(
            RestClient.Builder loanRestClientBuilder,
            @Value("${app.loan.market.base-url:http://localhost:8094}") String marketBaseUrl,
            @Value("${app.loan.commondata.base-url:http://localhost:8098}") String commonDataBaseUrl,
            @Value("${app.loan.commondata.real-estate-source:}") String realEstateSourceCode,
            @Value("${app.loan.commondata.price-field:dealAmount}") String realEstatePriceField,
            @Value("${app.loan.commondata.price-unit-multiplier:10000}") BigDecimal realEstatePriceUnitMultiplier,
            @Value("${app.loan.commondata.records-limit:100}") int realEstateRecordsLimit) {
        this.marketClient = loanRestClientBuilder.clone().baseUrl(marketBaseUrl).build();
        this.commonDataClient = loanRestClientBuilder.clone().baseUrl(commonDataBaseUrl).build();
        this.realEstateSourceCode = realEstateSourceCode;
        this.realEstatePriceField = realEstatePriceField;
        this.realEstatePriceUnitMultiplier = realEstatePriceUnitMultiplier;
        this.realEstateRecordsLimit = realEstateRecordsLimit;
    }

    @Override
    public BigDecimal appraise(ValuationClaim claim) {
        if (claim.marketRef() == null || claim.marketRef().isBlank()) {
            return claim.declaredValue();
        }
        return switch (claim.type()) {
            case EQUITY -> appraiseEquity(claim);
            case REAL_ESTATE -> appraiseRealEstate(claim);
            // 예금·채권·보증서는 서류값이 정본 — 위성 조회 대상이 아니다.
            default -> claim.declaredValue();
        };
    }

    /** 주식: market-service 최신 종가 × 수량. */
    private BigDecimal appraiseEquity(ValuationClaim claim) {
        if (claim.quantity() == null || claim.quantity() <= 0) {
            log.warn("주식 담보 수량 부재 — 제시값 폴백. marketRef={}", claim.marketRef());
            return claim.declaredValue();
        }
        try {
            StockSnapshotDto snapshot = marketClient.get()
                    .uri("/api/market/stocks/{code}/latest", claim.marketRef())
                    .retrieve()
                    .body(StockSnapshotDto.class);
            if (snapshot == null || snapshot.latest() == null || snapshot.latest().closePrice() == null
                    || snapshot.latest().closePrice().signum() <= 0) {
                log.warn("market 시세 관측치 없음(latest=null) — 제시값 폴백. code={}", claim.marketRef());
                return claim.declaredValue();
            }
            BigDecimal appraised = snapshot.latest().closePrice()
                    .multiply(BigDecimal.valueOf(claim.quantity()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            log.info("주식 담보 시가 평가. code={}, closePrice={}, quantity={}, appraised={}",
                    claim.marketRef(), snapshot.latest().closePrice(), claim.quantity(), appraised);
            return appraised;
        } catch (RestClientException e) {
            log.warn("market 시세 조회 실패 — 제시값 폴백. code={}: {}", claim.marketRef(), e.getMessage());
            return claim.declaredValue();
        }
    }

    /** 주택: commondata 실거래가 최신 레코드의 거래금액 × 단위 배수. */
    private BigDecimal appraiseRealEstate(ValuationClaim claim) {
        if (realEstateSourceCode == null || realEstateSourceCode.isBlank()) {
            log.warn("실거래가 수집 소스 미설정(app.loan.commondata.real-estate-source) — 제시값 폴백");
            return claim.declaredValue();
        }
        try {
            RecordsDto records = commonDataClient.get()
                    .uri("/api/common-data/sources/{code}/records?limit={limit}",
                            realEstateSourceCode, realEstateRecordsLimit)
                    .retrieve()
                    .body(RecordsDto.class);
            if (records == null || records.records() == null) {
                log.warn("commondata 실거래 레코드 없음 — 제시값 폴백. source={}", realEstateSourceCode);
                return claim.declaredValue();
            }
            return records.records().stream()
                    .filter(r -> r.recordKey() != null && r.recordKey().startsWith(claim.marketRef()))
                    .max(Comparator.comparing(RecordDto::collectedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(this::dealPrice)
                    .filter(price -> price != null && price.signum() > 0)
                    .map(price -> {
                        log.info("주택 담보 실거래가 평가. key={}, appraised={}", claim.marketRef(), price);
                        return price;
                    })
                    .orElseGet(() -> {
                        log.warn("실거래 레코드 매칭 실패 — 제시값 폴백. key={}", claim.marketRef());
                        return claim.declaredValue();
                    });
        } catch (RestClientException e) {
            log.warn("commondata 실거래가 조회 실패 — 제시값 폴백. key={}: {}",
                    claim.marketRef(), e.getMessage());
            return claim.declaredValue();
        }
    }

    /** 레코드 payload 의 거래금액 필드 파싱 — 만원 단위 콤마 문자열("84,000")·숫자 모두 수용. */
    private BigDecimal dealPrice(RecordDto record) {
        if (record.payload() == null) {
            return null;
        }
        Object raw = record.payload().get(realEstatePriceField);
        try {
            BigDecimal amount = switch (raw) {
                case null -> null;
                case Number n -> new BigDecimal(n.toString());
                case String s -> new BigDecimal(s.replace(",", "").trim());
                default -> null;
            };
            return amount == null ? null
                    : amount.multiply(realEstatePriceUnitMultiplier).setScale(SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            log.warn("실거래 금액 파싱 실패 — 레코드 건너뜀. recordKey={}, raw={}", record.recordKey(), raw);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StockSnapshotDto(String stockCode, QuoteDto latest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteDto(BigDecimal closePrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecordsDto(String code, List<RecordDto> records) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecordDto(String recordKey, LocalDateTime collectedAt, Map<String, Object> payload) {
    }
}
