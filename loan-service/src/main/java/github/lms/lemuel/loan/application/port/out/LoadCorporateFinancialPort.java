package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CorporateFinancials;

import java.util.Optional;

/**
 * 상장사 회사정보 + 최신 요약 재무제표 조회 아웃바운드 포트.
 * financial-statements-service 공개 API 를 HTTP 로 호출한다(코드·DB 의존 0).
 * 회사/재무가 없으면 {@link Optional#empty()}.
 */
public interface LoadCorporateFinancialPort {

    Optional<CorporateFinancials> loadLatest(String stockCode);
}
