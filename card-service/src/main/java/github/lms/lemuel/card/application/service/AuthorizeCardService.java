package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadMerchantPolicyPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.DeclineReason;
import github.lms.lemuel.card.domain.MerchantPolicy;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 카드 실시간 승인 유스케이스 구현.
 *
 * <h3>승인 흐름(순서는 곧 방어다)</h3>
 * <ol>
 *   <li>카드 조회 → 카드 상태 검증(SUSPENDED·CANCELED → CARD_SUSPENDED)</li>
 *   <li><b>카드계정 비관적 락</b> — 락 없이 한도를 읽으면 동시 승인이 모두 통과한다</li>
 *   <li>계정 상태 검증(SUSPENDED → CARD_SUSPENDED)</li>
 *   <li>임직원 활성 여부 검증(org_member_projection)</li>
 *   <li>가맹점/MCC 정책 위반 검증</li>
 *   <li>멱등 체크({@code authorizationId} 중복) — 기존 홀드 반환</li>
 *   <li>가용한도 계산 및 검증(LIMIT_EXCEEDED)</li>
 *   <li>홀드 저장 + 이벤트 발행(Outbox, 같은 트랜잭션)</li>
 * </ol>
 *
 * <h3>비관적 락 불변식</h3>
 * {@code LoadCardAccountPort.findByIdForUpdate(id)} 로 계정 행을 PESSIMISTIC_WRITE 로 잠근 뒤
 * ACTIVE 홀드 합계를 재계산한다. 순서를 뒤집으면 그 사이 끼어든 다른 승인이 합계에 반영되지
 * 않아 둘 다 한도 안에 있는 것처럼 보인다(집계 제약은 DB 로 표현할 수 없다).
 */
@Service
public class AuthorizeCardService implements AuthorizeCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthorizeCardService.class);

    private final LoadCardPort loadCardPort;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadOrgProjectionPort loadOrgProjectionPort;
    private final LoadMerchantPolicyPort loadMerchantPolicyPort;
    private final LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    private final SaveAuthorizationHoldPort saveAuthorizationHoldPort;
    private final PublishCardEventPort publishCardEventPort;

    public AuthorizeCardService(LoadCardPort loadCardPort,
                                LoadCardAccountPort loadCardAccountPort,
                                LoadOrgProjectionPort loadOrgProjectionPort,
                                LoadMerchantPolicyPort loadMerchantPolicyPort,
                                LoadAuthorizationHoldPort loadAuthorizationHoldPort,
                                SaveAuthorizationHoldPort saveAuthorizationHoldPort,
                                PublishCardEventPort publishCardEventPort) {
        this.loadCardPort = loadCardPort;
        this.loadCardAccountPort = loadCardAccountPort;
        this.loadOrgProjectionPort = loadOrgProjectionPort;
        this.loadMerchantPolicyPort = loadMerchantPolicyPort;
        this.loadAuthorizationHoldPort = loadAuthorizationHoldPort;
        this.saveAuthorizationHoldPort = saveAuthorizationHoldPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public AuthorizationResult authorize(AuthorizeCardCommand command) {
        // 1. 카드 조회(락 없음 — 계정 FK 를 알아내기 위한 선조회)
        Card card = loadCardPort.findById(command.cardId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));

        // 2. 카드 상태 검증 — 정지·해지 카드는 락 없이 즉시 거절
        if (card.getStatus() == CardStatus.SUSPENDED || card.getStatus() == CardStatus.CANCELED) {
            log.debug("[CardAuthorization] 거절(카드정지) cardId={} status={}",
                    command.cardId(), card.getStatus());
            return AuthorizationResult.declined(DeclineReason.CARD_SUSPENDED);
        }

        // 3. 카드계정 비관적 락 — 반드시 카드 상태 확인 후, 합계 읽기 전에 잠근다
        CardAccount account = loadCardAccountPort.findByIdForUpdate(card.getCardAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));

        // 4. 계정 상태 검증 — SUSPENDED·CLOSED·DELINQUENT 는 모두 CARD_SUSPENDED 로 거절
        if (account.getStatus() == CardAccountStatus.SUSPENDED
                || account.getStatus() == CardAccountStatus.CLOSED
                || account.getStatus() == CardAccountStatus.DELINQUENT) {
            log.debug("[CardAuthorization] 거절(계정정지/연체) cardAccountId={} status={}",
                    account.getId(), account.getStatus());
            return AuthorizationResult.declined(DeclineReason.CARD_SUSPENDED);
        }

        // 5. 임직원 활성 여부 검증
        Optional<github.lms.lemuel.card.domain.OrgRole> memberRole =
                loadOrgProjectionPort.findMemberRole(account.getOrganizationId(), card.getHolderUserId());
        if (memberRole.isEmpty()) {
            log.debug("[CardAuthorization] 거절(이탈멤버) cardId={} holderUserId={}",
                    command.cardId(), card.getHolderUserId());
            return AuthorizationResult.declined(DeclineReason.MEMBER_INACTIVE);
        }

        // 6. 가맹점/MCC 지출정책 위반 검증(락 획득 후, 홀드 저장 전)
        Optional<MerchantPolicy> policy =
                loadMerchantPolicyPort.findEffectivePolicy(command.cardId(), account.getId());
        if (policy.isPresent()) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            BigDecimal todaySpend = loadAuthorizationHoldPort
                    .sumHoldsByCardAndDate(command.cardId(), today);
            BigDecimal monthlySpend = loadAuthorizationHoldPort
                    .sumHoldsByCardAndMonth(command.cardId(), YearMonth.from(today));
            Optional<DeclineReason> policyViolation = policy.get().evaluate(
                    command.mcc(), command.amount(), command.overseas(), command.online(),
                    todaySpend, monthlySpend);
            if (policyViolation.isPresent()) {
                log.debug("[CardAuthorization] 거절(가맹점정책) cardId={} mcc={} amount={}",
                        command.cardId(), command.mcc(), command.amount());
                return AuthorizationResult.declined(policyViolation.get());
            }
        }

        // 7. 멱등 체크 — 같은 authorizationId 로 이미 홀드가 있으면 기존 홀드 반환
        Optional<AuthorizationHold> existing =
                loadAuthorizationHoldPort.findByAuthorizationId(command.authorizationId());
        if (existing.isPresent()) {
            log.info("[CardAuthorization] 멱등 재처리(기존홀드반환) authorizationId={} holdStatus={}",
                    command.authorizationId(), existing.get().getStatus());
            return AuthorizationResult.approved(existing.get());
        }

        // 8. 가용한도 계산(비관적 락 획득 상태에서)
        BigDecimal activeHoldsAccount =
                loadAuthorizationHoldPort.sumActiveHoldsByAccount(account.getId());
        BigDecimal activeHoldsCard =
                loadAuthorizationHoldPort.sumActiveHoldsByCard(command.cardId());

        BigDecimal availableMaster = account.getMasterLimit().subtract(activeHoldsAccount);
        BigDecimal availableSub = card.getSubLimit().subtract(activeHoldsCard);
        BigDecimal available = availableMaster.min(availableSub);

        if (command.amount().compareTo(available) > 0) {
            log.debug("[CardAuthorization] 거절(한도초과) cardId={} amount={} available={}",
                    command.cardId(), command.amount(), available);
            return AuthorizationResult.declined(DeclineReason.LIMIT_EXCEEDED);
        }

        // 9. 홀드 생성·저장
        AuthorizationHold hold = AuthorizationHold.create(
                command.authorizationId(),
                command.cardId(),
                account.getId(),
                card.getHolderUserId(),
                command.amount(),
                command.merchantName(),
                command.mcc(),
                Instant.now()
        );
        AuthorizationHold saved = saveAuthorizationHoldPort.save(hold);

        // 10. Outbox 이벤트 발행(같은 트랜잭션 — 원자성 보장)
        publishCardEventPort.publishAuthorized(saved, card, account);

        log.info("[CardAuthorization] 승인 cardId={} accountId={} authorizationId={} amount={} available→{}",
                command.cardId(), account.getId(), command.authorizationId(),
                command.amount(), available.subtract(command.amount()));
        return AuthorizationResult.approved(saved);
    }
}
