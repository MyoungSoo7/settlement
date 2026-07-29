package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.port.out.LoadScreeningRunPort;
import github.lms.lemuel.investment.application.port.out.RecordScreeningRunPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class ScreeningRunPersistenceAdapter implements LoadScreeningRunPort, RecordScreeningRunPort {

    private final ScreeningRunRepository repository;

    public ScreeningRunPersistenceAdapter(ScreeningRunRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<LocalDate> loadLatestScreenedDate() {
        return repository.findLatestQuoteBaseDate();
    }

    /** 기준일이 PK 라 같은 날 재실행은 갱신된다 — 재시도·수동 재실행이 행을 늘리지 않는다. */
    @Override
    @Transactional
    public void record(LocalDate quoteBaseDate, int recommendationCount) {
        repository.save(ScreeningRunJpaEntity.of(quoteBaseDate, recommendationCount, LocalDateTime.now()));
    }
}
