package github.lms.lemuel.market.adapter.out.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import github.lms.lemuel.market.domain.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final long requestIntervalMs;

    public KrxApiClient(KrxProperties properties, RestClient.Builder restClientBuilder,
                        ObjectMapper objectMapper,
                        @Value("${app.market.sync.request-interval-ms:150}") long requestIntervalMs) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.requestIntervalMs = requestIntervalMs;
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
            if (pageNo > 1) {
                pause();   // 첫 호출은 지연시키지 않는다 — 간격은 "페이지 사이" 에만 필요하다
            }
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

    /**
     * 페이지 호출 사이 간격. data.go.kr 쿼터를 보호한다.
     *
     * <p>설정 키({@code app.market.sync.request-interval-ms})는 예전부터 yml 에 "쿼터 보호용 페이지
     * 호출 간격" 이라는 주석과 함께 있었지만 <b>읽는 코드가 없었다</b>(2026-08-20 고아 파라미터 감사).
     * 즉 market 만 형제 서비스(company·economics·financial-statements) 와 달리 무간격으로 전 페이지를
     * 연타하고 있었다. 값을 낮춰도 아무 일이 일어나지 않으니 아무도 눈치채지 못하는 종류의 결함이다.
     *
     * <p>0 이하면 간격 없음 — 형제 서비스들과 같은 규약이다.
     */
    private void pause() {
        if (requestIntervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KRX 수집 스레드 인터럽트", e);
        }
    }

    /** 한 페이지 body 노드. NODATA(03)면 null, 그 외 오류 코드는 예외. */
    private JsonNode getBody(String basDt, int pageNo) {
        // serviceKey(Decoding 키)에 '+','/','=' 등이 들어갈 수 있어 queryParam 으로 한 번만 인코딩되게 한다.
        // ★ baseUrl 을 포함한 절대 URI 여야 한다 — 상대 URI(fromPath)를 RestClient.uri(URI) 에 넘기면
        //   RFC 3986 해석으로 baseUrl 경로(/1160100/service/...)가 통째로 대체되어
        //   https://apis.data.go.kr/getStockPriceInfo 로 나가고, 게이트웨이가 500 "Unexpected errors" 를
        //   반환한다 (KRX 키 승인 후 첫 라이브 수집에서 실측으로 드러난 잠복 버그).
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/getStockPriceInfo")
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
        Market market = toMarket(item.path("mrktCtg").asText(""));
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

    /**
     * 금융위 피드의 시장구분 코드({@code mrktCtg}) → 도메인 {@link Market}. 미지/결측이면 null(그 행 skip).
     *
     * <p>이 번역은 어댑터의 책임이다. 도메인 enum 이 공급자의 코드 문자열을 알면, 공급자가 바뀌거나
     * 코드 체계가 늘어날 때 도메인이 함께 흔들린다.
     */
    static Market toMarket(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toUpperCase()) {
            case "KOSPI" -> Market.KOSPI;
            case "KOSDAQ" -> Market.KOSDAQ;
            case "KONEX" -> Market.KONEX;
            default -> null;
        };
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
