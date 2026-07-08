package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.ReputationScore;

import java.util.Optional;

/** 평판 스냅샷 재계산 트리거 — 저장된 기사에서 오늘자 스냅샷을 산정한다. */
public interface RecalcReputationUseCase {

    /** 단건 기업 — 기사가 없으면 empty(스냅샷 미생성). */
    Optional<ReputationScore> recalcFor(String stockCode);

    RecalcSummary recalcAll();

    /**
     * @param companies        대상 기업 수
     * @param saved            신규 저장된 스냅샷 수
     * @param skippedNoArticle 기사가 없어 건너뛴 수
     * @param skippedExisting  오늘자 스냅샷이 이미 있어 건너뛴 수
     */
    record RecalcSummary(int companies, int saved, int skippedNoArticle, int skippedExisting) {
    }
}
