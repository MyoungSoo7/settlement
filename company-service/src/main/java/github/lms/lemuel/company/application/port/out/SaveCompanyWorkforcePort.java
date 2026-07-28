package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.CompanyWorkforce;

import java.util.List;

/** 국민연금 사업장가입자 CSV 임포트 전용 — 조회 경로(LoadCompanyWorkforcePort)와는 분리. */
public interface SaveCompanyWorkforcePort {

    UpsertResult batchUpsert(List<CompanyWorkforce> batch);

    record UpsertResult(int upserted) {
    }
}
