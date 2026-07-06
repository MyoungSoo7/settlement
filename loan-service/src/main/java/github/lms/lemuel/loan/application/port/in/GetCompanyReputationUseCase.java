package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CompanyReputation;

import java.util.Optional;

/** 셀러(법인) 평판 프로젝션 조회 — 여신 심사 참고 지표. */
public interface GetCompanyReputationUseCase {

    Optional<CompanyReputation> byStockCode(String stockCode);
}
