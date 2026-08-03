package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

@Component
public class AuthorizationHoldPersistenceAdapter
        implements LoadAuthorizationHoldPort, SaveAuthorizationHoldPort {

    private final SpringDataAuthorizationHoldRepository repository;

    public AuthorizationHoldPersistenceAdapter(SpringDataAuthorizationHoldRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AuthorizationHold> findByAuthorizationId(String authorizationId) {
        return repository.findByAuthorizationId(authorizationId)
                .map(AuthorizationHoldJpaEntity::toDomain);
    }

    @Override
    public BigDecimal sumActiveHoldsByAccount(Long cardAccountId) {
        return repository.sumActiveHoldsByAccount(cardAccountId);
    }

    @Override
    public BigDecimal sumActiveHoldsByCard(Long cardId) {
        return repository.sumActiveHoldsByCard(cardId);
    }

    @Override
    public BigDecimal sumHoldsByCardAndDate(Long cardId, LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.sumHoldsByCardAndPeriod(cardId, from, to);
    }

    @Override
    public BigDecimal sumHoldsByCardAndMonth(Long cardId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.sumHoldsByCardAndPeriod(cardId, from, to);
    }

    @Override
    public AuthorizationHold save(AuthorizationHold hold) {
        return repository.saveAndFlush(AuthorizationHoldJpaEntity.fromDomain(hold)).toDomain();
    }
}
