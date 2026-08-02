package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.QueryCardUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 카드 조회 유스케이스.
 *
 * <p><b>조회에도 인가가 붙는다</b> — 한도와 보유 현황 자체가 여신 정보다. 다만 등급을 나눈다:
 * 계정 요약은 구성원이면 누구나(자기 회사 한도는 알아야 한다), 임직원 카드 목록은 OWNER·MANAGER 만
 * (누가 얼마짜리 카드를 갖고 있는지는 인사 정보에 가깝다).
 *
 * <p>"내 카드"만 조직 인가를 거치지 않는다 — 조회 대상이 요청 파라미터가 아니라 JWT 주체라
 * 남의 것을 볼 경로 자체가 없기 때문이다. 대상을 입력으로 받는 순간 그것이 IDOR 경로가 된다.
 *
 * <p>컨트롤러가 아웃포트를 직접 부르지 않도록 조회도 유스케이스로 감싼다 — 헥사고날 경계는
 * ArchUnit(application ↛ adapter, adapter ↛ 다른 도메인)이 강제하고, 인가 판정이 응용 계층에
 * 모여 있어야 "조회 경로만 권한이 빠진" 사고가 생기지 않는다.
 */
@Service
public class QueryCardService implements QueryCardUseCase {

    /** 계정 요약은 구성원 전체 — 자기 회사의 한도를 STAFF 에게 숨길 이유가 없다. */
    private static final Set<OrgRole> ANY_MEMBER = Set.of(OrgRole.OWNER, OrgRole.MANAGER, OrgRole.STAFF);

    /** 남의 카드 보유·한도는 인사 정보에 가까워 STAFF 에게 열지 않는다. */
    private static final Set<OrgRole> MANAGEMENT = Set.of(OrgRole.OWNER, OrgRole.MANAGER);

    private final CardOrgAuthorizer authorizer;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadCardPort loadCardPort;

    public QueryCardService(CardOrgAuthorizer authorizer,
                            LoadCardAccountPort loadCardAccountPort,
                            LoadCardPort loadCardPort) {
        this.authorizer = authorizer;
        this.loadCardAccountPort = loadCardAccountPort;
        this.loadCardPort = loadCardPort;
    }

    @Override
    @Transactional(readOnly = true)
    public CardAccount getAccount(Long cardAccountId, Long requesterUserId) {
        CardAccount account = requireAccount(cardAccountId);
        authorizer.requireRole(account.getOrganizationId(), requesterUserId, ANY_MEMBER, "카드계정 조회");
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Card> listCards(Long cardAccountId, Long requesterUserId) {
        CardAccount account = requireAccount(cardAccountId);
        authorizer.requireRole(account.getOrganizationId(), requesterUserId, MANAGEMENT, "임직원 카드 목록 조회");
        return loadCardPort.findByCardAccountId(account.getId());
    }

    /** 대상이 곧 주체다 — 조직 인가를 거치지 않는 유일한 조회. */
    @Override
    @Transactional(readOnly = true)
    public List<Card> listMyCards(Long requesterUserId) {
        return loadCardPort.findByHolderUserId(requesterUserId);
    }

    /**
     * 존재 확인이 인가보다 먼저다 — 반대 순서면 "없는 계정"과 "권한 없는 계정"이 모두 403 이 되어
     * 운영자가 오타와 권한 문제를 구분할 수 없다. 카드계정 id 는 조직 내부에서 유추 가능한 값이라
     * 존재 여부 노출이 새로운 정보를 주지 않는다.
     */
    private CardAccount requireAccount(Long cardAccountId) {
        return loadCardAccountPort.findById(cardAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));
    }
}
