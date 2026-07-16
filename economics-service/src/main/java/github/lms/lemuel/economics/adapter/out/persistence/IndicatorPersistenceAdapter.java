package github.lms.lemuel.economics.adapter.out.persistence;

import github.lms.lemuel.economics.application.port.out.LoadIndicatorPort;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorValuePort;
import github.lms.lemuel.economics.application.port.out.SaveIndicatorValuePort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class IndicatorPersistenceAdapter
        implements LoadIndicatorPort, LoadIndicatorValuePort, SaveIndicatorValuePort {

    private final IndicatorRepository indicatorRepository;
    private final IndicatorValueRepository indicatorValueRepository;

    public IndicatorPersistenceAdapter(IndicatorRepository indicatorRepository,
                                        IndicatorValueRepository indicatorValueRepository) {
        this.indicatorRepository = indicatorRepository;
        this.indicatorValueRepository = indicatorValueRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Indicator> findAll() {
        return indicatorRepository.findAll().stream()
                .map(IndicatorJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Indicator> findByCode(String code) {
        return indicatorRepository.findById(code).map(IndicatorJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IndicatorValue> findLatest(String indicatorCode, int limit) {
        return indicatorValueRepository
                .findByIndicatorCodeOrderByObservedDateDesc(indicatorCode, Limit.of(limit)).stream()
                .map(IndicatorValueJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IndicatorValue> findSeries(String indicatorCode, LocalDate from, LocalDate to) {
        return indicatorValueRepository
                .findByIndicatorCodeAndObservedDateBetweenOrderByObservedDateAsc(indicatorCode, from, to).stream()
                .map(IndicatorValueJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void upsert(IndicatorValue value) {
        IndicatorValueJpaEntity entity = indicatorValueRepository
                .findByIndicatorCodeAndObservedDate(value.indicatorCode(), value.observedDate())
                .map(existing -> {
                    existing.applyDomain(value);
                    return existing;
                })
                .orElseGet(() -> IndicatorValueJpaEntity.fromDomain(value));
        indicatorValueRepository.save(entity);
    }

    @Override
    @Transactional
    public int purgeSeedNewerThan(String indicatorCode, LocalDate latestEcosDate) {
        return indicatorValueRepository
                .deleteByIndicatorCodeAndSourceNewerThan(indicatorCode, ValueSource.SEED, latestEcosDate);
    }
}
