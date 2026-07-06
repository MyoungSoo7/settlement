package github.lms.lemuel.financial.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.financial.application.port.out.DartClientPort;
import github.lms.lemuel.financial.domain.FsDivision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * OpenDART HTTP 클라이언트.
 *
 * <ul>
 *   <li>corpCode.xml — zip 안의 CORPCODE.xml 을 파싱해 상장사(종목코드 보유)만 추출</li>
 *   <li>company.json — 기업개황(corp_cls: Y=유가 / K=코스닥 / N=코넥스 / E=기타)</li>
 *   <li>fnlttSinglAcnt.json — 사업보고서(reprt_code=11011) 주요계정, 연결(CFS) 우선</li>
 * </ul>
 *
 * <p>DART status 코드: 000 정상, 013 조회 데이터 없음(→ empty 처리), 그 외는 예외.
 */
@Component
public class DartApiClient implements DartClientPort {

    private static final Logger log = LoggerFactory.getLogger(DartApiClient.class);
    private static final String STATUS_OK = "000";
    private static final String STATUS_NO_DATA = "013";
    private static final String ANNUAL_REPORT_CODE = "11011";

    private final DartProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DartApiClient(DartProperties properties, RestClient.Builder restClientBuilder,
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
    public List<ListedCompany> fetchListedCompanies() {
        byte[] zip = restClient.get()
                .uri(uri -> uri.path("/corpCode.xml").queryParam("crtfc_key", properties.apiKey()).build())
                .retrieve()
                .body(byte[].class);
        if (zip == null || zip.length == 0) {
            throw new IllegalStateException("corpCode.xml 응답이 비어 있습니다");
        }
        try {
            return parseCorpCodeZip(zip);
        } catch (Exception e) {
            throw new IllegalStateException("corpCode.xml 파싱 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<CompanyProfile> fetchProfile(String corpCode) {
        JsonNode root = getJson("/company.json", Map.of("corp_code", corpCode));
        String status = root.path("status").asText();
        if (STATUS_NO_DATA.equals(status)) {
            return Optional.empty();
        }
        requireOk(status, root, "company.json corpCode=" + corpCode);
        return Optional.of(new CompanyProfile(corpCode,
                root.path("corp_cls").asText(null),
                root.path("corp_name").asText(null)));
    }

    @Override
    public Optional<AnnualSummary> fetchAnnualSummary(String corpCode, int year) {
        JsonNode root = getJson("/fnlttSinglAcnt.json", Map.of(
                "corp_code", corpCode,
                "bsns_year", String.valueOf(year),
                "reprt_code", ANNUAL_REPORT_CODE));
        String status = root.path("status").asText();
        if (STATUS_NO_DATA.equals(status)) {
            return Optional.empty();
        }
        requireOk(status, root, "fnlttSinglAcnt.json corpCode=%s year=%d".formatted(corpCode, year));
        return summarize(root.path("list"));
    }

    // ---- 내부 구현 ----

    private JsonNode getJson(String path, Map<String, String> params) {
        String body = restClient.get()
                .uri(uri -> {
                    var builder = uri.path(path).queryParam("crtfc_key", properties.apiKey());
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("DART 응답 JSON 파싱 실패 (" + path + ")", e);
        }
    }

    private static void requireOk(String status, JsonNode root, String context) {
        if (!STATUS_OK.equals(status)) {
            throw new IllegalStateException("DART 오류 status=%s message=%s (%s)"
                    .formatted(status, root.path("message").asText(), context));
        }
    }

    private static List<ListedCompany> parseCorpCodeZip(byte[] zip) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().toUpperCase().endsWith(".XML")) {
                    return parseCorpCodeXml(zis.readAllBytes());
                }
            }
        }
        throw new IllegalStateException("corpCode.xml zip 안에 XML 엔트리가 없습니다");
    }

    private static List<ListedCompany> parseCorpCodeXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // XXE 방어 — 외부 엔티티/DTD 비활성
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        NodeList nodes = document.getElementsByTagName("list");
        List<ListedCompany> companies = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String stockCode = text(element, "stock_code");
            if (stockCode == null || stockCode.isBlank()) {
                continue;   // 비상장(공시대상 비상장법인 포함) 제외
            }
            companies.add(new ListedCompany(
                    text(element, "corp_code"), stockCode.strip(), text(element, "corp_name")));
        }
        log.info("corpCode.xml 파싱 — 전체 {} 중 상장사 {} 건", nodes.getLength(), companies.size());
        return companies;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        String value = list.item(0).getTextContent();
        return value == null ? null : value.strip();
    }

    /**
     * fnlttSinglAcnt list → 요약. 연결(CFS) 계정이 하나라도 있으면 연결 기준, 없으면 별도(OFS).
     * 계정명 매칭: 매출액/수익(매출액), 영업이익(손실), 당기순이익(손실), 자산/부채/자본총계.
     */
    private static Optional<AnnualSummary> summarize(JsonNode list) {
        if (!list.isArray() || list.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BigDecimal> cfs = new HashMap<>();
        Map<String, BigDecimal> ofs = new HashMap<>();
        String currency = "KRW";
        for (JsonNode row : list) {
            String account = normalizeAccount(row.path("account_nm").asText(""));
            if (account == null) {
                continue;
            }
            BigDecimal amount = parseAmount(row.path("thstrm_amount").asText(""));
            if (amount == null) {
                continue;
            }
            String rowCurrency = row.path("currency").asText("");
            if (!rowCurrency.isBlank()) {
                currency = rowCurrency;
            }
            Map<String, BigDecimal> target = "CFS".equals(row.path("fs_div").asText()) ? cfs : ofs;
            target.putIfAbsent(account, amount);
        }
        Map<String, BigDecimal> chosen = cfs.isEmpty() ? ofs : cfs;
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AnnualSummary(
                cfs.isEmpty() ? FsDivision.OFS : FsDivision.CFS,
                currency,
                chosen.get("revenue"),
                chosen.get("operatingProfit"),
                chosen.get("netIncome"),
                chosen.get("totalAssets"),
                chosen.get("totalLiabilities"),
                chosen.get("totalEquity")));
    }

    private static String normalizeAccount(String accountName) {
        String name = accountName.replace(" ", "");
        if (name.equals("매출액") || name.equals("수익(매출액)") || name.equals("영업수익")) {
            return "revenue";
        }
        if (name.startsWith("영업이익")) {
            return "operatingProfit";
        }
        if (name.startsWith("당기순이익")) {
            return "netIncome";
        }
        return switch (name) {
            case "자산총계" -> "totalAssets";
            case "부채총계" -> "totalLiabilities";
            case "자본총계" -> "totalEquity";
            default -> null;
        };
    }

    private static BigDecimal parseAmount(String raw) {
        String cleaned = raw.replace(",", "").strip();
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
