package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조직·멤버 프로젝션 적재 서비스.
 *
 * <p>컨슈머 트랜잭션 안에서 호출된다(멱등 마커와 같은 tx — 마커만 커밋되고 프로젝션이
 * 롤백되면 이벤트 유실이다). 역할 문자열이 계약 밖이면 {@link IllegalArgumentException}
 * 으로 즉시 DLT 격리된다.
 */
@Service
@Transactional
public class OrgProjectionService implements IngestOrgProjectionUseCase {

    private final SaveOrgProjectionPort saveOrgProjectionPort;

    public OrgProjectionService(SaveOrgProjectionPort saveOrgProjectionPort) {
        this.saveOrgProjectionPort = saveOrgProjectionPort;
    }

    @Override
    public void registerOrg(OrgCommand command) {
        saveOrgProjectionPort.upsertOrg(
                command.organizationId(), command.name(), command.type(), command.externalRef());
        // created 이벤트는 소유자의 member_joined 를 따로 발행하지 않는다(계약: ownerUserId 는 자동 OWNER).
        saveOrgProjectionPort.upsertMember(command.organizationId(), command.ownerUserId(), OrgRole.OWNER);
    }

    @Override
    public void upsertMember(MemberCommand command) {
        OrgRole role = OrgRole.valueOf(command.role());
        saveOrgProjectionPort.upsertMember(command.organizationId(), command.userId(), role);
    }

    @Override
    public void removeMember(long organizationId, long userId) {
        saveOrgProjectionPort.deactivateMember(organizationId, userId);
    }
}
