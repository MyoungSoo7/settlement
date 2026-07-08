package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ReputationScore;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoadReputationPort {

    /** 가장 최근 스냅샷 (calculatedAt 기준). */
    Optional<ReputationScore> findLatest(String stockCode);

    /** 최근 스냅샷 시계열 (snapshotDate 내림차순, limit 개). */
    List<ReputationScore> findHistory(String stockCode, int limit);

    /** 해당 일자의 스냅샷이 이미 있는지 — INSERT-only 이므로 하루 1건 중복 방어. */
    boolean existsForDate(String stockCode, LocalDate snapshotDate);
}
