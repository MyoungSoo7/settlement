package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CompanyReputation;

public interface SaveCompanyReputationPort {

    /** 종목코드가 할당 식별자이므로 멱등 UPSERT (재수신 시 최신 등급으로 덮어씀). */
    void upsert(CompanyReputation reputation);
}
