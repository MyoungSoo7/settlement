package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.ReputationScore;

import java.util.List;
import java.util.Optional;

/** 기업 평판 스코어 조회 — 최신 스냅샷 + 추이. */
public interface GetReputationUseCase {

    Optional<ReputationScore> current(String stockCode);

    List<ReputationScore> history(String stockCode, int limit);
}
