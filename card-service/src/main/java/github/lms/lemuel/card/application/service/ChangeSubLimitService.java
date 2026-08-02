package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ChangeSubLimitUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
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
 * 임직원 서브한도 변경 유스케이스.
 *
 * <p><b>비교 대상은 "나를 뺀 합계"다.</b> {@code sumActiveSubLimits} 에는 이 카드의 현재 한도가
 * 이미 포함돼 있어, 그대로 {@code sum + new} 로 검증하면 자기 자신을 두 번 세게 된다 —
 * 60만짜리 카드를 70만으로 올릴 때 90만(=60만+30만) + 70만 = 160만으로 계산되어, 마스터
 * 100만에 정확히 들어맞는 정상 상향이 거부된다. {@code sum - 내 현재 한도 + new} 여야 한다.
 *
 * <p>락 순서는 {@link IssueCardService} 와 동형이다 — 카드계정을 잠근 <b>뒤에</b> 합계를 읽는다.
 * 카드 조회가 락보다 앞서는 것은 소속 계정을 알아내기 위한 불가피한 순서이고, 그 사이 같은 카드가
 * 바뀌었을 가능성은 {@code CardJpaEntity} 의 {@code @Version} 낙관적 락이 저장 시점에 잡는다
 * (트랜잭션 안에서 다시 읽어도 1차 캐시가 같은 인스턴스를 돌려주므로 재조회는 방어가 아니다).
 * 다른 카드의 동시 변경은 계정 락이 직렬화한다.
 */
@Service
public class ChangeSubLimitService implements ChangeSubLimitUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeSubLimitService.class);

    /** 한도 배분은 곧 여신이라 발급과 같은 등급 — OWNER 만. */
    private static final Set<OrgRole> ALLOWED_TO_CHANGE_LIMIT = Set.of(OrgRole.OWNER);

    private final CardOrgAuthorizer authorizer;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadCardPort loadCardPort;
    private final SaveCardPort saveCardPort;
    private final PublishCardEventPort publishCardEventPort;

    public ChangeSubLimitService(CardOrgAuthorizer authorizer,
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
    public Card change(ChangeSubLimitCommand command) {
        Card card = loadCardPort.findById(command.cardId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));

        // 락은 합계 재계산보다 먼저 — 순서를 뒤집으면 그 사이 끼어든 변경이 합계에서 빠진다.
        CardAccount account = loadCardAccountPort.findByIdForUpdate(card.getCardAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));

        authorizer.requireRole(account.getOrganizationId(), command.requesterUserId(),
                ALLOWED_TO_CHANGE_LIMIT, "카드 한도 변경");

        // CANCELED 카드는 어차피 못 바꾼다 — 판정을 도메인에 물어보고, 그 전에 집계 쿼리를 돌리지 않는다.
        card.assertLimitMutable();

        BigDecimal previousSubLimit = card.getSubLimit();
        BigDecimal currentSum = loadCardPort.sumActiveSubLimits(account.getId());

        // 하향은 불변식을 깰 수 없으므로 검증하지 않는다 — 재산정 클램프 등으로 합계가 이미
        // 마스터를 넘긴 상태에서도 "줄이는 방향"까지 막으면 복구 경로 자체가 사라진다.
        if (command.newSubLimit().compareTo(previousSubLimit) > 0) {
            account.assertCanIssue(currentSum.subtract(previousSubLimit), command.newSubLimit());
        }

        card.changeSubLimit(command.newSubLimit());
        Card saved = saveCardPort.save(card);

        publishCardEventPort.publishSubLimitChanged(saved, account, previousSubLimit);
        log.info("[CardSubLimitChanged] cardId={} accountId={} {} → {} sumBefore={}",
                saved.getId(), account.getId(), previousSubLimit, saved.getSubLimit(), currentSum);
        return saved;
    }
}
