package github.lms.lemuel.loan.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * financial-statements-service 공개 API(무인증) HTTP 클라이언트 — 회사정보 + 최신 요약 재무제표를
 * 가져와 {@link CorporateFinancials} 로 옮긴다(코드·DB 의존 0, MSA 경계).
 *
 * <ul>
 *   <li>{@code GET {base}/api/financial/companies/{stockCode}} → 회사(없으면 404)</li>
 *   <li>{@code GET {base}/api/financial/companies/{stockCode}/statements} → 요약 재무제표 배열</li>
 * </ul>
 *
 * <p>회사 미존재/재무 없음/API 실패는 모두 {@link Optional#empty()} 로 흡수한다 — 신용평가 서비스가
 * "재무자료 없음" 도메인 예외로 변환해 404/422 로 매핑한다(하나의 종목 실패가 스택트레이스로 누수되지 않게).
 */
@Component
public class FinancialApiClient implements LoadCorporateFinancialPort {

    private static final Logger log = LoggerFactory.getLogger(FinancialApiClient.class);

    private final RestClient restClient;

    public FinancialApiClient(RestClient.Builder loanRestClientBuilder,
                              @Value("${app.loan.financial.base-url:http://localhost:8086}") String baseUrl) {
        this.restClient = loanRestClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<CorporateFinancials> loadLatest(String stockCode) {
        try {
            CompanyDto company = restClient.get()
                    .uri("/api/financial/companies/{stockCode}", stockCode)
                    .retrieve()
                    .body(CompanyDto.class);
            if (company == null) {
                return Optional.empty();
            }

            StatementDto[] statements = restClient.get()
                    .uri("/api/financial/companies/{stockCode}/statements", stockCode)
                    .retrieve()
                    .body(StatementDto[].class);

            Optional<StatementDto> latest = statements == null ? Optional.empty()
                    : Arrays.stream(statements)
                    .max(Comparator.comparingInt(StatementDto::fiscalYear));

            return latest.map(s -> new CorporateFinancials(
                    company.stockCode(),
                    company.name(),
                    company.market(),
                    s.fiscalYear(),
                    s.debtRatio(),
                    s.operatingMargin(),
                    s.roa(),
                    s.totalEquity(),
                    s.netIncome()));
        } catch (RestClientException e) {
            log.warn("financial API 조회 실패 stockCode={} — 재무자료 없음으로 처리: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompanyDto(String stockCode, String corpCode, String name, String market) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatementDto(
            int fiscalYear,
            BigDecimal totalEquity,
            BigDecimal netIncome,
            BigDecimal operatingMargin,
            BigDecimal debtRatio,
            BigDecimal roa) {
    }
}
