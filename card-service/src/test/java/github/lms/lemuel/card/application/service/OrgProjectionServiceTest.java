package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrgProjectionServiceTest {

    @Mock SaveOrgProjectionPort saveOrgProjectionPort;

    OrgProjectionService service;

    @BeforeEach
    void setUp() {
        service = new OrgProjectionService(saveOrgProjectionPort);
    }

    @Test
    void createOrg_delegatesToSavePort() {
        service.createOrg(new OrgCommand(3001L, "무신사 스토어", "SELLER", "SELLER-777"));

        verify(saveOrgProjectionPort).saveOrg(3001L, "무신사 스토어", "SELLER", "SELLER-777");
    }

    @Test
    void upsertMember_delegatesToSavePort() {
        service.upsertMember(new MemberCommand(3001L, 888L, "MANAGER"));

        verify(saveOrgProjectionPort).upsertMember(3001L, 888L, "MANAGER");
    }

    @Test
    void removeMember_delegatesToDeactivate() {
        service.removeMember(3001L, 888L);

        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L);
    }

    /**
     * 검증하지 않고 저장하면 실패가 <b>읽는 시점</b>(카드 발급 심사의 {@code findMemberRole})으로
     * 밀려 500 이 되고 원인 이벤트도 DLT 에 남지 않는다. 적재 시점 IAE 는 프레임워크가
     * non-retryable 로 규정한 격리·DLT 경로다.
     */
    @Test
    void upsertMember_unknownRole_rejectedAtIngest() {
        assertThatThrownBy(() -> service.upsertMember(new MemberCommand(3001L, 888L, "ACCOUNTANT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCOUNTANT");

        verifyNoInteractions(saveOrgProjectionPort);
    }

    @Test
    void upsertMember_nullRole_rejectedAtIngest() {
        assertThatThrownBy(() -> service.upsertMember(new MemberCommand(3001L, 888L, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(saveOrgProjectionPort);
    }
}
