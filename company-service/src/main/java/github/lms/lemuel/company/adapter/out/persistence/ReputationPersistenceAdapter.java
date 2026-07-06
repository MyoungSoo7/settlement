package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.application.port.out.SaveReputationPort;
import github.lms.lemuel.company.domain.ReputationScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class ReputationPersistenceAdapter implements LoadReputationPort, SaveReputationPort {

    private static final Logger log = LoggerFactory.getLogger(ReputationPersistenceAdapter.class);

    private final ReputationScoreRepository repository;

    public ReputationPersistenceAdapter(ReputationScoreRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReputationScore> findLatest(String stockCode) {
        return repository.findFirstByStockCodeOrderByCalculatedAtDesc(stockCode)
                .map(ReputationScoreJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReputationScore> findHistory(String stockCode, int limit) {
        return repository.findByStockCodeOrderBySnapshotDateDesc(stockCode, PageRequest.of(0, limit))
                .map(ReputationScoreJpaEntity::toDomain)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsForDate(String stockCode, LocalDate snapshotDate) {
        return repository.existsByStockCodeAndSnapshotDate(stockCode, snapshotDate);
    }

    @Override
    @Transactional
    public boolean saveIfAbsent(ReputationScore score) {
        if (repository.existsByStockCodeAndSnapshotDate(score.stockCode(), score.snapshotDate())) {
            return false;
        }
        try {
            repository.save(ReputationScoreJpaEntity.fromDomain(score));
            return true;
        } catch (DataIntegrityViolationException e) {
            // (stock_code, snapshot_date) UNIQUE 충돌 — 동시 재계산 레이스. 먼저 저장한 스냅샷을 존중.
            log.debug("동시 평판 스냅샷 충돌 스킵 stockCode={} date={}", score.stockCode(), score.snapshotDate());
            return false;
        }
    }
}
