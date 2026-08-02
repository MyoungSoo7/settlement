package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * organization-service 이벤트를 조직·멤버 프로젝션으로 적재한다.
 *
 * <p>{@code @Transactional} 은 컨슈머의 {@code @KafkaListener} 메서드에도 이미 걸려 있어(REQUIRED
 * 전파로 합류) 실질적으로는 방어적 중복이지만, 이 서비스가 컨슈머 밖(예: 관리자 백필 API)에서
 * 단독 호출되는 경우에도 트랜잭션 경계가 스스로 성립하도록 유지한다.
 */
@Service
public class OrgProjectionService implements IngestOrgProjectionUseCase {

    private final SaveOrgProjectionPort saveOrgProjectionPort;

    public OrgProjectionService(SaveOrgProjectionPort saveOrgProjectionPort) {
        this.saveOrgProjectionPort = saveOrgProjectionPort;
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

    @Override
    @Transactional
    public void removeMember(Long organizationId, Long userId) {
        // 카드 정지는 Task 12 가 이 지점에 이어붙인다 — 여기서는 프로젝션 비활성화까지만.
        saveOrgProjectionPort.deactivateMember(organizationId, userId);
    }
}
