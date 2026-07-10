package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.CompanyFinancials;

import java.util.Optional;

/** financial-statements-service 공개 API 로 회사 식별정보 + 연도별 요약 재무제표를 조회하는 아웃바운드 포트. */
public interface LoadFinancialStatementsPort {

    /** 조회 실패(회사 미존재/재무제표 없음)면 {@link Optional#empty()}. */
    Optional<CompanyFinancials> load(String stockCode);
}
