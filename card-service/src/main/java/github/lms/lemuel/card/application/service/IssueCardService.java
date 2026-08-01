package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.out.CardIssuerPort;
import github.lms.lemuel.card.application.port.out.CardIssuerPort.IssuedCard;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 임직원 카드 발급 유스케이스.
 *
 * <p><b>이 클래스의 존재 이유는 한 줄이다</b> — {@code master_limit >= Σ sub_limit} 를 동시 발급
 * 경합에서 지키는 것. 카드계정과 카드를 별개 애그리거트로 쪼갠 대가라, 그 값을 락으로 갚는다:
 * 카드계정 행을 PESSIMISTIC_WRITE 로 잠근 <b>뒤에</b> 서브한도 합계를 재계산한다. 순서를 뒤집어
 * 합계를 먼저 읽으면 그 사이에 끼어든 다른 발급이 합계에 반영되지 않아 둘 다 통과한다
 * (집계 제약은 DB 로 표현할 수 없어 이 락이 유일한 방어선이다 — V4__card_core.sql 주석 참조).
 *
 * <p>채번({@link CardIssuerPort})은 <b>모든 검증 뒤</b>다. 거절될 요청에 실물 카드번호를 태우면
 * 그 번호는 우리 DB 에 남지 않아 회수할 방법이 없다.
 */
@Service
public class IssueCardService implements IssueCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(IssueCardService.class);

    /** 발급은 법인 대표(OWNER)만 — 한도 배분은 곧 여신이라 MANAGER 에게도 열지 않는다. */
    private static final Set<OrgRole> ALLOWED_TO_ISSUE = Set.of(OrgRole.OWNER);

    private final CardOrgAuthorizer authorizer;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadOrgProjectionPort loadOrgProjectionPort;
    private final LoadCardPort loadCardPort;
    private final SaveCardPort saveCardPort;
    private final CardIssuerPort cardIssuerPort;
    private final PublishCardEventPort publishCardEventPort;

    public IssueCardService(CardOrgAuthorizer authorizer,
                            LoadCardAccountPort loadCardAccountPort,
                            LoadOrgProjectionPort loadOrgProjectionPort,
                            LoadCardPort loadCardPort,
                            SaveCardPort saveCardPort,
                            CardIssuerPort cardIssuerPort,
                            PublishCardEventPort publishCardEventPort) {
        this.authorizer = authorizer;
        this.loadCardAccountPort = loadCardAccountPort;
        this.loadOrgProjectionPort = loadOrgProjectionPort;
        this.loadCardPort = loadCardPort;
        this.saveCardPort = saveCardPort;
        this.cardIssuerPort = cardIssuerPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public Card issue(IssueCardCommand command) {
        // 락은 검증보다 먼저 — 합계를 읽은 뒤 잠그면 그 사이에 다른 발급이 끼어든다.
        CardAccount account = loadCardAccountPort.findByIdForUpdate(command.cardAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));

        authorizer.requireRole(account.getOrganizationId(), command.requesterUserId(),
                ALLOWED_TO_ISSUE, "카드 발급");

        // 요청자 권한과 별개로 "대상이 이 조직 사람인가"를 다시 본다 — 프로젝션이 비활성으로
        // 바꾼 퇴사자에게 새 카드가 나가는 경로를 여기서 닫는다.
        loadOrgProjectionPort.findMemberRole(account.getOrganizationId(), command.holderUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_HOLDER_NOT_MEMBER,
                        "조직 " + account.getOrganizationId() + " 의 활성 구성원이 아닌 사용자("
                                + command.holderUserId() + ")에게는 발급할 수 없습니다."));

        // 선검증 409. 최종 차단선은 uq_card_active_holder 부분 유니크 인덱스다(이중 방어).
        loadCardPort.findActiveByHolder(account.getId(), command.holderUserId())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CARD_ALREADY_ISSUED);
                });

        BigDecimal currentSum = loadCardPort.sumActiveSubLimits(account.getId());
        account.assertCanIssue(currentSum, command.subLimit());

        IssuedCard issued = cardIssuerPort.issue(account.getId(), command.holderUserId());
        Card card = saveCardPort.save(Card.issue(
                account.getId(), command.holderUserId(), issued.maskedCardNo(), command.subLimit()));

        publishCardEventPort.publishIssued(card, account);
        log.info("[CardIssued] cardId={} accountId={} holderUserId={} subLimit={} sumBefore={}",
                card.getId(), account.getId(), command.holderUserId(), command.subLimit(), currentSum);
        return card;
    }
}
