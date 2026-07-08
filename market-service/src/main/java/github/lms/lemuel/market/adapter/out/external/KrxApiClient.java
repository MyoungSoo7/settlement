package github.lms.lemuel.market.adapter.out.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import github.lms.lemuel.market.domain.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 공공데이터포털 금융위원회 주식시세정보(getStockPriceInfo) HTTP 클라이언트.
 *
 * <p>URL: {@code {baseUrl}/getStockPriceInfo?serviceKey=..&resultType=json&basDt=yyyyMMdd&numOfRows=..&pageNo=..}
 *
 * <ul>
 *   <li>정상 응답: {@code {"response":{"header":{"resultCode":"00"},"body":{"totalCount":n,"items":{"item":[..]}}}}}</li>
 *   <li>데이터 없음: resultCode {@code "03"}(NODATA) → 빈 리스트(휴장일/미래일). 그 외 코드는 예외.</li>
 *   <li>페이지네이션: totalCount 를 pageSize 로 나눠 전 페이지를 순회 — 하루치 전 종목(≈2800)을 완주한다.</li>
 *   <li>파싱 불가/결측 필드가 있는 행은 그 행만 skip(한 종목의 이상값으로 하루 수집을 죽이지 않는다).</li>
 * </ul>
 */
@Component
public class KrxApiClient implements KrxClientPort {

    private static final Logger log = LoggerFactory.getLogger(KrxApiClient.class);
    private static final String OK_CODE = "00";
    private static final String NO_DATA_CODE = "03";
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KrxProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public KrxApiClient(KrxProperties properties, RestClient.Builder restClientBuilder,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public List<StockPrice> fetchQuotes(LocalDate baseDate) {
        String basDt = BAS_DT.format(baseDate);
        List<StockPrice> all = new ArrayList<>();
        int pageNo = 1;
        int totalCount = Integer.MAX_VALUE;   // 첫 페이지 응답에서 실제 값으로 갱신
        while ((long) (pageNo - 1) * properties.pageSize() < totalCount) {
            JsonNode body = getBody(basDt, pageNo);
            if (body == null) {
                break;   // NODATA — 휴장일/미래일
            }
            totalCount = body.path("totalCount").asInt(0);
            if (totalCount == 0) {
                break;
            }
            JsonNode items = body.path("items").path("item");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                StockPrice price = parseItem(item, baseDate);
                if (price != null) {
                    all.add(price);
                }
            }
            pageNo++;
        }
        return all;
    }

    // ---- 내부 구현 ----

    /** 한 페이지 body 노드. NODATA(03)면 null, 그 외 오류 코드는 예외. */
    private JsonNode getBody(String basDt, int pageNo) {
        // serviceKey(Decoding 키)에 '+','/','=' 등이 들어갈 수 있어 queryParam 으로 한 번만 인코딩되게 한다.
        URI uri = UriComponentsBuilder.fromPath("/getStockPriceInfo")
                .queryParam("serviceKey", properties.apiKey())
                .queryParam("resultType", "json")
                .queryParam("numOfRows", properties.pageSize())
                .queryParam("pageNo", pageNo)
                .queryParam("basDt", basDt)
                .build()
                .toUri();
        String rawBody = restClient.get().uri(uri).retrieve().body(String.class);

        JsonNode root = readTree(rawBody, basDt);
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText("");
        if (NO_DATA_CODE.equals(code)) {
            return null;
        }
        if (!OK_CODE.equals(code)) {
            throw new IllegalStateException("금융위 API 오류 resultCode=%s resultMsg=%s (basDt=%s)"
                    .formatted(code, header.path("resultMsg").asText(""), basDt));
        }
        return root.path("response").path("body");
    }

    private JsonNode readTree(String rawBody, String basDt) {
        try {
            return objectMapper.readTree(rawBody == null ? "{}" : rawBody);
        } catch (JsonProcessingException e) {
            // XML 오류 응답(인증키 오류 등)이 오면 JSON 파싱이 깨진다 — basDt 와 함께 원인을 남긴다.
            throw new IllegalStateException("금융위 응답 JSON 파싱 실패 (basDt=" + basDt
                    + ") — 인증키/쿼터를 확인하세요", e);
        }
    }

    /** 1행 → StockPrice. 종목코드/종가/시장이 없으면 skip(null). */
    private StockPrice parseItem(JsonNode item, LocalDate baseDate) {
        String stockCode = item.path("srtnCd").asText("").strip();
        Market market = Market.fromCode(item.path("mrktCtg").asText(""));
        BigDecimal close = parseDecimal(item.path("clpr").asText(""));
        if (stockCode.isEmpty() || market == null || close == null) {
            return null;
        }
        return new StockPrice(
                stockCode,
                blankToNull(item.path("isinCd").asText("")),
                item.path("itmsNm").asText("").strip(),
                market,
                baseDate,
                close,
                parseDecimal(item.path("mkp").asText("")),
                parseDecimal(item.path("hipr").asText("")),
                parseDecimal(item.path("lopr").asText("")),
                parseDecimal(item.path("vs").asText("")),
                parseDecimal(item.path("fltRt").asText("")),
                parseInteger(item.path("trqu").asText("")),
                parseInteger(item.path("trPrc").asText("")),
                parseInteger(item.path("lstgStCnt").asText("")),
                parseInteger(item.path("mrktTotAmt").asText("")));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.strip();
    }

    private static BigDecimal parseDecimal(String raw) {
        String cleaned = raw.replace(",", "").strip();
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("금융위 숫자 파싱 실패, skip: '{}'", raw);
            return null;
        }
    }

    private static BigInteger parseInteger(String raw) {
        BigDecimal decimal = parseDecimal(raw);
        return decimal == null ? null : decimal.toBigInteger();
    }
}
