package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterCommand;

import java.util.List;

/**
 * 기업 마스터 일괄 등록 요청 — 유니버스 빌더 산출물({@code briefing-companies*.json})의 companies
 * 배열을 그대로 실어 보낼 수 있는 형태. 항목의 businessNumber 등 배치 전용 필드는 무시된다
 * (기업 마스터는 stockCode·corpCode·name·market 만 보관).
 */
public record CompanyRegistrationRequest(List<Entry> companies) {

    public record Entry(String stockCode, String corpCode, String name, String market) {
    }

    public List<RegisterCommand> toCommands() {
        if (companies == null) {
            return List.of();
        }
        return companies.stream()
                .map(e -> new RegisterCommand(e.stockCode(), e.corpCode(), e.name(), e.market()))
                .toList();
    }
}
