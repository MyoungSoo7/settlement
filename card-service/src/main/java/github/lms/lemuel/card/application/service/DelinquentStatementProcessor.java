package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연체 명세서 <b>1건</b>의 DELINQUENT 전이 — 배치의 트랜잭션 단위.
 *
 * <p>{@link MarkDelinquentStatementsService} 안의 private 메서드가 아니라 <b>별도 빈</b>인 이유는
 * 트랜잭션 경계 때문이다. 자기 호출(self-invocation)은 Spring 프록시를 타지 않아
 * {@code @Transactional} 이 무시되고, 그러면 배치 전체가 한 트랜잭션이 되어
 * <b>한 명세서의 실패가 앞서 처리한 건들까지 롤백</b>시킨다 —
 * fail-open 처리(명세서별 독립 커밋)가 무너지는 지점이 정확히 여기다.
 *
 * <p>{@code REQUIRES_NEW} 는 호출자에 트랜잭션이 있든 없든 명세서마다 독립 커밋을 보장한다.
 *
 * @see CardAccountRescreener 동일 패턴의 선례
 */
@Service
class DelinquentStatementProcessor {

    private static final Logger log = LoggerFactory.getLogger(DelinquentStatementProcessor.class);

    private final SaveCardStatementPort saveCardStatementPort;
    private final LoadCardAccountPort loadCardAccountPort;
    private final SaveCardAccountPort saveCardAccountPort;
    private final PublishCardEventPort publishCardEventPort;

    DelinquentStatementProcessor(SaveCardStatementPort saveCardStatementPort,
                                 LoadCardAccountPort loadCardAccountPort,
                                 SaveCardAccountPort saveCardAccountPort,
                                 PublishCardEventPort publishCardEventPort) {
        this.saveCardStatementPort = saveCardStatementPort;
        this.loadCardAccountPort = loadCardAccountPort;
        this.saveCardAccountPort = saveCardAccountPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    /**
     * 명세서 1건 연체 전이 + 카드계정 연체 전이 (독립 트랜잭션).
     *
     * @param statement 연체 처리 대상 명세서(이미 CLOSED·PARTIALLY_PAID 상태여야 한다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(CardStatement statement) {
        // 명세서 → DELINQUENT
        statement.markDelinquent();
        saveCardStatementPort.save(statement);

        // 카드계정 비관적 락 + DELINQUENT 전이(이미 DELINQUENT 이면 스킵)
        loadCardAccountPort.findByIdForUpdate(statement.getCardAccountId()).ifPresent(account -> {
            if (account.getStatus() == CardAccountStatus.ACTIVE) {
                CardAccountStatus previous = account.getStatus();
                account.markDelinquent();
                saveCardAccountPort.save(account);
                publishCardEventPort.publishAccountStatusChanged(account, previous,
                        "청구서 만기 경과 — 연체 자동 전이");
                log.info("[Delinquency] 카드계정 연체 전이 cardAccountId={} statementId={}",
                        account.getId(), statement.getId());
            }
        });
    }
}
