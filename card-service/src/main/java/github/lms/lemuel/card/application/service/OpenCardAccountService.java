package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase;
import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort.OrgView;
import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardLimitPolicy;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.ScreeningResult;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 카드계정 개설(심사) 유스케이스.
 *
 * <p><b>순서가 곧 안전 설계다:</b> 인가 → 조직 검증 → 중복 검증 → 재원 조회 → 평판 조회 →
 * 산정 → 저장 → 발행. 재원 조회는 account-service 로 나가는 외부 호출이라, 인가·검증을 통과한
 * 요청만 도달해야 한다(권한 없는 요청이 내부 API 를 두드리게 두면 그 자체가 증폭 경로다).
 *
 * <p>저장과 Outbox 기록이 같은 트랜잭션에서 커밋되어야 "카드계정은 생겼는데 이벤트는 안 나갔다"
 * (또는 그 반대)가 생기지 않는다.
 */
@Service
public class OpenCardAccountService implements OpenCardAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(OpenCardAccountService.class);

    /** 개설은 법인 대표(OWNER)만 — 한도는 곧 여신이라 MANAGER 에게도 열지 않는다. */
    private static final Set<OrgRole> ALLOWED_TO_OPEN = Set.of(OrgRole.OWNER);

    private static final String SELLER_TYPE = "SELLER";

    private final CardOrgAuthorizer authorizer;
    private final LoadOrgProjectionPort loadOrgProjectionPort;
    private final LoadCardAccountPort loadCardAccountPort;
    private final SaveCardAccountPort saveCardAccountPort;
    private final LoadSellerFundingPort loadSellerFundingPort;
    private final LoadReputationPort loadReputationPort;
    private final PublishCardEventPort publishCardEventPort;
    private final CardLimitPolicy cardLimitPolicy;

    public OpenCardAccountService(CardOrgAuthorizer authorizer,
                                  LoadOrgProjectionPort loadOrgProjectionPort,
                                  LoadCardAccountPort loadCardAccountPort,
                                  SaveCardAccountPort saveCardAccountPort,
                                  LoadSellerFundingPort loadSellerFundingPort,
                                  LoadReputationPort loadReputationPort,
                                  PublishCardEventPort publishCardEventPort,
                                  CardLimitPolicy cardLimitPolicy) {
        this.authorizer = authorizer;
        this.loadOrgProjectionPort = loadOrgProjectionPort;
        this.loadCardAccountPort = loadCardAccountPort;
        this.saveCardAccountPort = saveCardAccountPort;
        this.loadSellerFundingPort = loadSellerFundingPort;
        this.loadReputationPort = loadReputationPort;
        this.publishCardEventPort = publishCardEventPort;
        this.cardLimitPolicy = cardLimitPolicy;
    }

    /**
     * ★ {@code noRollbackFor = BusinessException.class} 인 이유: 심사 탈락은 REJECTED 레코드를
     * <b>남긴 채</b> 422 를 던진다. 기본 규칙(RuntimeException → 롤백)이면 그 근거 기록이 함께
     * 사라져 "왜 떨어졌나"를 사후에 답할 수 없다. 탈락 이전 단계에서 던지는 BusinessException
     * (403·409·422·503)은 애초에 아무것도 쓰지 않으므로 커밋해도 남는 것이 없다.
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public CardAccount open(OpenCardAccountCommand command) {
        Long organizationId = command.organizationId();

        // 1) 인가 — 재원 조회(외부 호출)보다 먼저.
        authorizer.requireRole(organizationId, command.requesterUserId(), ALLOWED_TO_OPEN, "카드계정 개설");

        // 2) 조직 검증 — 1단계는 셀러 법인 전용이고, sellerId 는 externalRef 로만 해석된다.
        OrgView org = loadOrgProjectionPort.findOrg(organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_SCREENING_REJECTED,
                        "조직 " + organizationId + " 정보를 아직 확인할 수 없습니다. 잠시 후 다시 시도해주세요."));
        if (!SELLER_TYPE.equals(org.type())) {
            throw new BusinessException(ErrorCode.CARD_SCREENING_REJECTED,
                    "셀러 법인만 카드계정을 개설할 수 있습니다. 현재 조직 타입=" + org.type());
        }
        String sellerId = org.externalRef();
        if (sellerId == null || sellerId.isBlank()) {
            throw new BusinessException(ErrorCode.CARD_SCREENING_REJECTED,
                    "조직 " + organizationId + " 에 셀러 식별자(externalRef)가 없어 재원을 조회할 수 없습니다.");
        }

        // 3) 중복 검증 — 조직당 1개(uq_card_account_org). DB 제약이 최종 방어선이고 여기선 친절한 409.
        if (loadCardAccountPort.findByOrganizationId(organizationId).isPresent()) {
            throw new BusinessException(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS,
                    "조직 " + organizationId + " 에는 이미 카드계정이 있습니다.");
        }

        // 4) 재원 조회 — 실패는 폴백하지 않고 503 으로 번역한다. 아무 상태도 남기지 않는다:
        //    REJECTED 로 기록하면 "심사 탈락"이라는 사실이 아닌 기록이 남고, 터미널 상태라 재시도도 막힌다.
        SellerFunding funding = loadFunding(sellerId);

        // 5) 평판 조회 — 프로젝션에 없으면 보수적 기본값(D). null 은 반환되지 않는다.
        ReputationGrade grade = loadReputationPort.gradeOf(sellerId);

        // 6) 산정 — 순수 도메인 정책. 승인이든 탈락이든 근거(LimitSnapshot)를 항상 동반한다.
        ScreeningResult result =
                cardLimitPolicy.screen(funding.sellerPayable(), funding.holdbackPayable(), grade);

        CardAccount account = CardAccount.open(organizationId, sellerId);
        if (!result.approved()) {
            account.reject(result.rejectReason(), result.snapshot());
            saveCardAccountPort.save(account);
            log.info("[CardAccount] 개설 심사 탈락 orgId={} sellerId={} 사유={}",
                    organizationId, sellerId, result.rejectReason());
            throw new BusinessException(ErrorCode.CARD_SCREENING_REJECTED, result.rejectReason());
        }

        account.activate(result.masterLimit(), result.snapshot());
        CardAccount saved = saveCardAccountPort.save(account);

        // 7) 발행 — 같은 트랜잭션의 Outbox 기록이라 저장과 원자적이다. 영속 id 가 필요해 저장 뒤에 한다.
        publishCardEventPort.publishAccountOpened(saved);
        log.info("[CardAccount] 개설 완료 orgId={} sellerId={} masterLimit={} grade={}",
                organizationId, sellerId, saved.getMasterLimit().toPlainString(), grade);
        return saved;
    }

    private SellerFunding loadFunding(String sellerId) {
        try {
            return loadSellerFundingPort.load(sellerId);
        } catch (FundingUnavailableException e) {
            // 폴백 없음 — 재원을 모른 채 추정 한도를 부여하면 그 자체가 여신 사고다.
            throw new BusinessException(ErrorCode.CARD_FUNDING_UNAVAILABLE,
                    ErrorCode.CARD_FUNDING_UNAVAILABLE.defaultMessage(), e);
        }
    }
}
