package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.StatementStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Component
public class CardStatementPersistenceAdapter implements LoadCardStatementPort, SaveCardStatementPort {

    private final SpringDataCardStatementRepository statementRepository;
    private final SpringDataStatementPaymentRepository paymentRepository;

    public CardStatementPersistenceAdapter(
            SpringDataCardStatementRepository statementRepository,
            SpringDataStatementPaymentRepository paymentRepository) {
        this.statementRepository = statementRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public Optional<CardStatement> findById(Long id) {
        return statementRepository.findById(id).map(CardStatementJpaEntity::toDomain);
    }

    @Override
    public Optional<CardStatement> findByCardAccountAndPeriod(Long cardAccountId,
                                                               YearMonth billingYearMonth) {
        return statementRepository.findByCardAccountIdAndBillingYearMonth(
                cardAccountId, billingYearMonth.toString()
        ).map(CardStatementJpaEntity::toDomain);
    }

    @Override
    public List<CardStatement> findOpenByBillingYearMonth(YearMonth billingYearMonth) {
        return statementRepository
                .findByBillingYearMonthAndStatus(billingYearMonth.toString(), StatementStatus.OPEN)
                .stream()
                .map(CardStatementJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<CardStatement> findOverdueAndUnpaid(LocalDate today) {
        return statementRepository.findOverdueAndUnpaid(today)
                .stream()
                .map(CardStatementJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsPaymentById(String paymentId) {
        return paymentRepository.existsByPaymentId(paymentId);
    }

    @Override
    public CardStatement save(CardStatement statement) {
        CardStatementJpaEntity entity = CardStatementJpaEntity.fromDomain(statement);
        return statementRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public void savePayment(Long statementId, String paymentId, BigDecimal amount) {
        StatementPaymentJpaEntity payment =
                StatementPaymentJpaEntity.create(statementId, paymentId, amount);
        paymentRepository.saveAndFlush(payment);
    }
}
