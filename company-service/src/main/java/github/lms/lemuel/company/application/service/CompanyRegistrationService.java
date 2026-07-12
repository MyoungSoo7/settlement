package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase;
import github.lms.lemuel.company.application.port.out.SaveCompanyPort;
import github.lms.lemuel.company.domain.Company;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기업 마스터 일괄 등록 — 요청 항목을 도메인 {@link Company} 로 검증(종목코드 6자리·고유번호
 * 8자리)한 뒤 {@link SaveCompanyPort} 로 upsert 한다. 같은 배치 내 stockCode 중복은 마지막 값이
 * 이긴다(정렬 안정성 위해 LinkedHashMap).
 */
@Service
public class CompanyRegistrationService implements RegisterCompaniesUseCase {

    private final SaveCompanyPort saveCompanyPort;

    public CompanyRegistrationService(SaveCompanyPort saveCompanyPort) {
        this.saveCompanyPort = saveCompanyPort;
    }

    @Override
    public RegisterResult register(List<RegisterCommand> companies) {
        if (companies == null || companies.isEmpty()) {
            throw new IllegalArgumentException("등록할 기업 목록이 비어 있습니다");
        }
        Map<String, Company> deduped = new LinkedHashMap<>();
        for (RegisterCommand command : companies) {
            Company company = new Company(command.stockCode(), blankToNull(command.corpCode()),
                    command.name(), command.market());
            deduped.put(company.stockCode(), company);
        }
        SaveCompanyPort.UpsertResult result = saveCompanyPort.upsertAll(List.copyOf(deduped.values()));
        return new RegisterResult(companies.size(), result.registered(), result.updated(), result.skipped());
    }

    /** 공란 corpCode 는 null 로 — 도메인은 8자리 또는 null 만 허용한다(빈 문자열은 length 검증 실패). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
