package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.Company;

import java.util.List;

/**
 * 기업 마스터 쓰기 포트 — 일괄 upsert(등록/갱신). Phase 1 의 Load 전용 정책을 확장한다
 * (외부 유니버스 등록 진입점 신설, {@link github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase}).
 */
public interface SaveCompanyPort {

    UpsertResult upsertAll(List<Company> companies);

    record UpsertResult(int registered, int updated, int skipped) {
    }
}
