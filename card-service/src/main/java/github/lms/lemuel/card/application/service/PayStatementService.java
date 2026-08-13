package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.PayStatementUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.StatementStatus;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구서 상환 서비스.
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>명세서 조회</li>
 *   <li>멱등 체크 — 동일 paymentId 로 이미 처리됐으면 현재 상태 반환</li>
 *   <li>납부 적용 → 명세서 저장 + 납부 레코드 저장(멱등 키)</li>
 *   <li>전액 납부(PAID)이면 카드계정 DELINQUENT → ACTIVE 자동 복구</li>
 *   <li>PAID 이면 {@code lemuel.card.statement_paid} Outbox 이벤트 발행</li>
 * </ol>
 */
@Service
public class PayStatementService implements PayStatementUseCase {

    private static final Logger log = LoggerFactory.getLogger(PayStatementService.class);

    private final LoadCardStatementPort loadCardStatementPort;
    private final SaveCardStatementPort saveCardStatementPort;
    private final LoadCardAccountPort loadCardAccountPort;
    private final SaveCardAccountPort saveCardAccountPort;
    private final PublishCardEventPort publishCardEventPort;

    public PayStatementService(LoadCardStatementPort loadCardStatementPort,
                               SaveCardStatementPort saveCardStatementPort,
                               LoadCardAccountPort loadCardAccountPort,
                               SaveCardAccountPort saveCardAccountPort,
                               PublishCardEventPort publishCardEventPort) {
        this.loadCardStatementPort = loadCardStatementPort;
        this.saveCardStatementPort = saveCardStatementPort;
        this.loadCardAccountPort = loadCardAccountPort;
        this.saveCardAccountPort = saveCardAccountPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public CardStatement pay(PayStatementCommand command) {
        // 1. 명세서 조회
        CardStatement statement = loadCardStatementPort.findById(command.statementId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));

        // 2. 멱등 체크 — 동일 paymentId 로 이미 처리됐으면 현재 상태 반환(no-op)
        if (loadCardStatementPort.existsPaymentById(command.paymentId())) {
            log.info("[StatementPay] 멱등 재처리 statementId={} paymentId={}",
                    command.statementId(), command.paymentId());
            return statement;
        }

        // 3. 납부 적용 + 납부 레코드 저장(UNIQUE 제약으로 최후 중복 차단)
        StatementStatus newStatus = statement.applyPayment(command.amount());
        CardStatement saved = saveCardStatementPort.save(statement);
        saveCardStatementPort.savePayment(saved.getId(), command.paymentId(), command.amount());

        log.info("[StatementPay] 납부 완료 statementId={} paymentId={} amount={} status={}",
                saved.getId(), command.paymentId(), command.amount(), newStatus);

        // 4. 전액 납부 → 카드계정 DELINQUENT 복구 + 이벤트 발행
        if (newStatus == StatementStatus.PAID) {
            recoverAccountIfDelinquent(saved);
            publishCardEventPort.publishStatementPaid(saved, command.paymentId());
        }

        return saved;
    }

    /** 카드계정이 DELINQUENT 이면 ACTIVE 로 복구한다. */
    private void recoverAccountIfDelinquent(CardStatement statement) {
        loadCardAccountPort.findByIdForUpdate(statement.getCardAccountId()).ifPresent(account -> {
            if (account.getStatus() == CardAccountStatus.DELINQUENT) {
                CardAccountStatus previous = account.getStatus();
                account.recoverFromDelinquency();
                saveCardAccountPort.save(account);
                publishCardEventPort.publishAccountStatusChanged(account, previous,
                        "전액 상환 완료 — 연체 자동 복구");
                log.info("[StatementPay] 연체 복구 cardAccountId={} statementId={}",
                        account.getId(), statement.getId());
            }
        });
    }
}
