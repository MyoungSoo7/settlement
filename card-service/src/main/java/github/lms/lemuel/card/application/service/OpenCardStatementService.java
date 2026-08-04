package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.OpenCardStatementUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardStatement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;

/** 청구서 신규 개설 서비스 — 청구주기별 카드계정당 1개, 멱등. */
@Service
public class OpenCardStatementService implements OpenCardStatementUseCase {

    private final LoadCardStatementPort loadCardStatementPort;
    private final SaveCardStatementPort saveCardStatementPort;

    public OpenCardStatementService(LoadCardStatementPort loadCardStatementPort,
                                    SaveCardStatementPort saveCardStatementPort) {
        this.loadCardStatementPort = loadCardStatementPort;
        this.saveCardStatementPort = saveCardStatementPort;
    }

    @Override
    @Transactional
    public CardStatement getOrOpenStatement(Long cardAccountId, YearMonth billingYearMonth,
                                            LocalDate dueDate) {
        return loadCardStatementPort
                .findByCardAccountAndPeriod(cardAccountId, billingYearMonth)
                .orElseGet(() -> {
                    CardStatement newStatement =
                            CardStatement.openFor(cardAccountId, billingYearMonth, dueDate);
                    return saveCardStatementPort.save(newStatement);
                });
    }
}
