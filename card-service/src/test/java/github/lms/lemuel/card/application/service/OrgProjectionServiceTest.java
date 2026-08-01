package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 조직·멤버 프로젝션 적재 서비스 — 이벤트 커맨드가 프로젝션 저장 포트로 올바르게 매핑되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrgProjectionServiceTest {

    @Mock SaveOrgProjectionPort saveOrgProjectionPort;
    @InjectMocks OrgProjectionService service;

    @Test
    @DisplayName("조직 등록은 조직 프로젝션과 함께 소유자를 OWNER 멤버로 적재한다 — created 에 member_joined 가 따로 오지 않는다")
    void registerOrg_upsertsOrgAndOwnerMember() {
        service.registerOrg(new OrgCommand(3001L, "무신사 스토어", "SELLER", "SELLER-777", 777L));

        verify(saveOrgProjectionPort).upsertOrg(3001L, "무신사 스토어", "SELLER", "SELLER-777");
        verify(saveOrgProjectionPort).upsertMember(3001L, 777L, OrgRole.OWNER);
    }

    @Test
    @DisplayName("멤버 upsert 는 역할 문자열을 OrgRole 로 변환해 저장한다")
    void upsertMember_mapsRole() {
        service.upsertMember(new MemberCommand(3001L, 888L, "MANAGER"));

        verify(saveOrgProjectionPort).upsertMember(3001L, 888L, OrgRole.MANAGER);
    }

    @Test
    @DisplayName("알 수 없는 역할 문자열은 IllegalArgumentException — 즉시 DLT 격리 대상")
    void upsertMember_unknownRoleThrows() {
        assertThatThrownBy(() -> service.upsertMember(new MemberCommand(3001L, 888L, "INTERN")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(saveOrgProjectionPort, never()).upsertMember(any(), any(), any());
    }

    @Test
    @DisplayName("멤버 제거는 프로젝션을 비활성화한다 — 여기가 낡으면 퇴사자 카드가 살아남는다")
    void removeMember_deactivatesProjection() {
        service.removeMember(3001L, 888L);

        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L);
    }
}
