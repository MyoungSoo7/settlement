package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ChangeCardStatusUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 카드 상태 변경 유스케이스(정지·재개·해지).
 *
 * <p><b>허용 전이의 정본은 도메인 하나뿐이다.</b> 이 서비스는 목표 상태를 값으로 받아 그에 대응하는
 * 도메인 메서드로 위임할 뿐, 어떤 전이가 가능한지 스스로 판단하지 않는다 — 규칙을 여기에 복제하면
 * {@code CardStatus.canTransitionTo} 와 서서히 어긋나고, 그 어긋남은 "API 로는 되는데 이벤트
 * 소비 경로로는 안 되는" 형태로만 드러난다.
 *
 * <p><b>상태가 실제로 바뀌지 않았으면 발행하지 않는다.</b> {@code suspend()} 는 멱등이라 이미 정지된
 * 카드를 다시 정지해도 아무 일이 없는데, 그때도 발행하면 재수신되는 이탈 이벤트(Task 12)마다
 * 소비자가 일어나지 않은 변화를 통지받는다. 반대로 해지는 멱등이 아니라 도메인이 예외로 드러낸다.
 *
 * <p>정지는 한도를 반납하지 않는다 — {@code sumActiveSubLimits} 가 {@code status <> CANCELED}
 * 기준이라 정지 카드도 자기 몫을 계속 점유한다. 그래서 이 유스케이스는 합계를 읽지 않는다:
 * 한도 배분이 달라지지 않으므로 재계산할 것이 없다.
 */
@Service
public class ChangeCardStatusService implements ChangeCardStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeCardStatusService.class);

    /** 한도 배분(OWNER 전용)과 달리 정지·재개·해지는 운영 행위라 MANAGER 에게도 연다. */
    private static final Set<OrgRole> ALLOWED_TO_CHANGE_STATUS = Set.of(OrgRole.OWNER, OrgRole.MANAGER);

    private final CardOrgAuthorizer authorizer;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadCardPort loadCardPort;
    private final SaveCardPort saveCardPort;
    private final PublishCardEventPort publishCardEventPort;

    public ChangeCardStatusService(CardOrgAuthorizer authorizer,
                                   LoadCardAccountPort loadCardAccountPort,
                                   LoadCardPort loadCardPort,
                                   SaveCardPort saveCardPort,
                                   PublishCardEventPort publishCardEventPort) {
        this.authorizer = authorizer;
        this.loadCardAccountPort = loadCardAccountPort;
        this.loadCardPort = loadCardPort;
        this.saveCardPort = saveCardPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public Card change(ChangeCardStatusCommand command) {
        // 사유는 유스케이스 입력 계약이다 — 카드 정지·해지는 감사 대상이라 "왜"가 없으면
        // 사후에 재현할 수 없다. 조회조차 하기 전에 막는다.
        if (command.reason() == null || command.reason().isBlank()) {
            throw new IllegalArgumentException("카드 상태 변경에는 사유가 필요합니다.");
        }

        Card card = loadCardPort.findById(command.cardId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));

        // 조직 식별자는 카드가 아니라 카드계정에만 있다 — 인가에도, 이벤트에도 필요하다.
        CardAccount account = loadCardAccountPort.findByIdForUpdate(card.getCardAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));

        authorizer.requireRole(account.getOrganizationId(), command.requesterUserId(),
                ALLOWED_TO_CHANGE_STATUS, "카드 상태 변경");

        CardStatus previousStatus = card.getStatus();
        applyTransition(card, command.targetStatus());

        // 멱등 no-op — 상태가 그대로면 저장도 발행도 하지 않는다.
        if (card.getStatus() == previousStatus) {
            log.debug("[CardStatusUnchanged] cardId={} status={} 재수신으로 판단해 무시", card.getId(), previousStatus);
            return card;
        }

        Card saved = saveCardPort.save(card);
        publishCardEventPort.publishStatusChanged(saved, account, previousStatus, command.reason());
        log.info("[CardStatusChanged] cardId={} accountId={} {} → {} reason={}",
                saved.getId(), account.getId(), previousStatus, saved.getStatus(), command.reason());
        return saved;
    }

    /** 목표 상태 → 도메인 메서드. 전이 가능 여부는 도메인이 판단한다. */
    private static void applyTransition(Card card, CardStatus targetStatus) {
        switch (targetStatus) {
            case SUSPENDED -> card.suspend();
            case ISSUED -> card.resume();
            case CANCELED -> card.cancel();
        }
    }
}
