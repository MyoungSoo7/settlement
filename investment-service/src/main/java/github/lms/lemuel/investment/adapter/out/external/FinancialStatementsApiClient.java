package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.application.port.out.LoadFinancialStatementsPort;
import github.lms.lemuel.investment.domain.AnnualStatement;
import github.lms.lemuel.investment.domain.CompanyFinancials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * financial-statements-service 공개 API HTTP 클라이언트 — 회사 식별정보 + 연도별 요약 재무제표를 조회한다.
 *
 * <ul>
 *   <li>{@code GET {base}/api/financial/companies/{stockCode}} → 회사 식별정보(404 → empty)</li>
 *   <li>{@code GET {base}/api/financial/companies/{stockCode}/statements} → 연도별 요약 재무제표 배열</li>
 * </ul>
 *
 * <p>같은 회계연도에 연결(CFS)·별도(OFS) 가 함께 오면 연결(CFS)을 우선해 연도당 1건으로 dedupe 한다.
 * 금액/파생지표가 결측(null)인 계정은 그대로 null 로 담아 도메인 정책이 0점 처리하게 한다.
 */
@Component
public class FinancialStatementsApiClient implements LoadFinancialStatementsPort {

    private static final Logger log = LoggerFactory.getLogger(FinancialStatementsApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public FinancialStatementsApiClient(RestClient.Builder restClientBuilder,
                                        ObjectMapper objectMapper,
                                        @Value("${app.financial.base-url:http://localhost:8086}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CompanyFinancials> load(String stockCode) {
        JsonNode company = getJson("/api/financial/companies/" + stockCode);
        if (company == null || company.isMissingNode() || company.isNull()) {
            return Optional.empty();
        }
        JsonNode statementsNode = getJson("/api/financial/companies/" + stockCode + "/statements");
        List<AnnualStatement> statements = parseStatements(statementsNode);
        if (statements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CompanyFinancials(
                text(company, "stockCode", stockCode),
                text(company, "name", null),
                text(company, "market", null),
                statements));
    }

    // ---- 내부 구현 ----

    /** GET → JsonNode. 404 는 null(미존재), 그 외 오류는 예외. */
    private JsonNode getJson(String path) {
        try {
            String body = restClient.get().uri(path).retrieve().body(String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new IllegalStateException("financial API 오류 path=" + path + " status=" + e.getStatusCode(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("financial API 응답 파싱 실패 path=" + path, e);
        }
    }

    /** 재무제표 배열 → 연도당 1건(CFS 우선) 도메인 리스트. */
    private List<AnnualStatement> parseStatements(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        // fiscalYear → 채택 statement. CFS 가 오면 OFS 를 덮어쓴다.
        Map<Integer, AnnualStatement> byYear = new LinkedHashMap<>();
        Map<Integer, String> divisionByYear = new LinkedHashMap<>();
        for (JsonNode node : arr) {
            int fiscalYear = node.path("fiscalYear").asInt();
            String division = node.path("fsDivision").asText("");
            String existing = divisionByYear.get(fiscalYear);
            if (existing != null && "CFS".equals(existing) && !"CFS".equals(division)) {
                continue; // 이미 연결(CFS)을 채택했으면 별도(OFS)는 무시
            }
            byYear.put(fiscalYear, toStatement(node, fiscalYear));
            divisionByYear.put(fiscalYear, division);
        }
        return new ArrayList<>(byYear.values());
    }

    private AnnualStatement toStatement(JsonNode node, int fiscalYear) {
        return new AnnualStatement(
                fiscalYear,
                decimal(node, "revenue"),
                decimal(node, "operatingProfit"),
                decimal(node, "netIncome"),
                decimal(node, "totalAssets"),
                decimal(node, "totalLiabilities"),
                decimal(node, "totalEquity"),
                decimal(node, "operatingMargin"),
                decimal(node, "netMargin"),
                decimal(node, "debtRatio"),
                decimal(node, "equityRatio"),
                decimal(node, "roa"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            log.warn("financial 숫자 파싱 실패, null 처리: field={}, raw={}", field, v.asText());
            return null;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? fallback : v.asText();
    }
}
