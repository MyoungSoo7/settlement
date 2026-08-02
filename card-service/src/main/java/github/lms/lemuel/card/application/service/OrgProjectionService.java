package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.OrgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * organization-service 이벤트를 조직·멤버 프로젝션으로 적재하고, 이탈에 카드 정지를 잇는다.
 *
 * <p>{@code @Transactional} 은 컨슈머의 {@code @KafkaListener} 메서드에도 이미 걸려 있어(REQUIRED
 * 전파로 합류) 실질적으로는 방어적 중복이지만, 이 서비스가 컨슈머 밖(예: 관리자 백필 API)에서
 * 단독 호출되는 경우에도 트랜잭션 경계가 스스로 성립하도록 유지한다.
 */
@Service
public class OrgProjectionService implements IngestOrgProjectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrgProjectionService.class);

    /** 이탈 정지의 사유 — 감사에서 "사람이 누른 정지"와 구분되는 유일한 표식이다. */
    private static final String REMOVAL_REASON = "조직 이탈로 자동 정지(member_removed)";

    private final SaveOrgProjectionPort saveOrgProjectionPort;
    private final LoadCardAccountPort loadCardAccountPort;
    private final LoadCardPort loadCardPort;
    private final SaveCardPort saveCardPort;
    private final PublishCardEventPort publishCardEventPort;

    public OrgProjectionService(SaveOrgProjectionPort saveOrgProjectionPort,
                                LoadCardAccountPort loadCardAccountPort,
                                LoadCardPort loadCardPort,
                                SaveCardPort saveCardPort,
                                PublishCardEventPort publishCardEventPort) {
        this.saveOrgProjectionPort = saveOrgProjectionPort;
        this.loadCardAccountPort = loadCardAccountPort;
        this.loadCardPort = loadCardPort;
        this.saveCardPort = saveCardPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public void createOrg(OrgCommand command) {
        saveOrgProjectionPort.saveOrg(
                command.organizationId(), command.name(), command.type(), command.externalRef());
    }

    @Override
    @Transactional
    public void upsertMember(MemberCommand command) {
        // 계약 밖 역할이면 여기서 IAE 가 나고 IdempotentEventConsumer 가 non-retryable 로 보아
        // 격리·DLT 로 보낸다. enum 이름으로 정규화해 저장하므로 읽는 쪽 valueOf 는 절대 실패하지 않는다.
        OrgRole role = OrgRole.from(command.role());
        saveOrgProjectionPort.upsertMember(command.organizationId(), command.userId(), role.name());
    }

    /**
     * 조직 이탈 처리 — 멤버 프로젝션 비활성화와 카드 정지를 <b>한 트랜잭션에서</b> 끝낸다.
     *
     * <p>둘을 나누면 "멤버는 빠졌는데 카드는 살아 있는" 창이 커밋되고, 그 창에서 승인된 결제는
     * 나중에 되돌릴 수 없다. Outbox 기록도 같은 트랜잭션이라 상태 변경과 통지가 갈라지지 않는다.
     *
     * <p>정지이지 해지가 아니다 — 이탈은 번복된다(휴직·전출·오발행 정정). 해지는 되돌릴 수 없는
     * 터미널 상태라 되돌릴 수 있는 사실에 되돌릴 수 없는 전이를 붙이면 복직 경로가 사라진다.
     * 정지 카드는 서브한도 합계에 계속 잡히므로(§{@code sumActiveSubLimits}) 복직 시 자기 한도를
     * 남에게 빼앗기지도 않는다.
     */
    @Override
    @Transactional
    public void removeMember(Long organizationId, Long userId) {
        saveOrgProjectionPort.deactivateMember(organizationId, userId);
        suspendCardOf(organizationId, userId);
    }

    /**
     * 이탈자의 활성 카드를 정지한다. 카드계정이 없거나(카드 미도입 조직) 카드가 없으면 무해한 no-op —
     * organization-service 는 상대가 카드를 쓰는지 모르고 이벤트를 보내므로, 없음이 정상 경로다.
     *
     * <p>조회를 {@code findActiveByHolder}(= {@code status <> CANCELED})로 하는 것이 해지 카드
     * 방어를 겸한다. CANCELED → SUSPENDED 는 도메인이 금지하는 전이라, 걸러내지 않고 정지시키면
     * 이탈 이벤트 하나가 예외로 죽으면서 <b>프로젝션 비활성화까지 함께 롤백</b>된다 — 카드를
     * 못 막는 것을 넘어 권한 회수 자체가 무산된다.
     */
    private void suspendCardOf(Long organizationId, Long userId) {
        Optional<CardAccount> account = loadCardAccountPort.findByOrganizationId(organizationId);
        if (account.isEmpty()) {
            return;
        }
        Optional<Card> found = loadCardPort.findActiveByHolder(account.get().getId(), userId);
        if (found.isEmpty()) {
            return;
        }

        Card card = found.get();
        CardStatus previousStatus = card.getStatus();
        card.suspend();
        if (card.getStatus() == previousStatus) {
            // suspend() 는 멱등이다. 이미 정지된 카드까지 발행하면 리플레이·재처리마다
            // 소비자가 일어나지 않은 상태 변화를 통지받는다.
            log.debug("[MemberRemovedCardAlreadySuspended] orgId={} userId={} cardId={}",
                    organizationId, userId, card.getId());
            return;
        }

        Card saved = saveCardPort.save(card);
        publishCardEventPort.publishStatusChanged(saved, account.get(), previousStatus, REMOVAL_REASON);
        log.info("[MemberRemovedCardSuspended] orgId={} userId={} cardId={} {} → SUSPENDED",
                organizationId, userId, saved.getId(), previousStatus);
    }
}
