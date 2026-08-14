package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.CloseStatementUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 청구서 마감 서비스 — 청구주기 OPEN 명세서를 CLOSED 로 전환한다.
 */
@Service
public class CloseStatementService implements CloseStatementUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseStatementService.class);

    private final LoadCardStatementPort loadCardStatementPort;
    private final SaveCardStatementPort saveCardStatementPort;

    public CloseStatementService(LoadCardStatementPort loadCardStatementPort,
                                 SaveCardStatementPort saveCardStatementPort) {
        this.loadCardStatementPort = loadCardStatementPort;
        this.saveCardStatementPort = saveCardStatementPort;
    }

    @Override
    @Transactional
    public List<Long> closeStatements(YearMonth billingYearMonth) {
        List<CardStatement> openStatements =
                loadCardStatementPort.findOpenByBillingYearMonth(billingYearMonth);

        List<Long> closedIds = new ArrayList<>();
        for (CardStatement statement : openStatements) {
            try {
                statement.close();
                CardStatement saved = saveCardStatementPort.save(statement);
                closedIds.add(saved.getId());
                log.debug("[StatementClose] 명세서 마감 statementId={} cardAccountId={} period={}",
                        saved.getId(), saved.getCardAccountId(), billingYearMonth);
            } catch (RuntimeException e) {
                log.error("[StatementClose] 명세서 마감 실패 statementId={} — 건너뜀",
                        statement.getId(), e);
            }
        }
        log.info("[StatementClose] 마감 완료 period={} count={}", billingYearMonth, closedIds.size());
        return closedIds;
    }
}
