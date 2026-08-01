package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 조직 역할 인가 테스트. 권한을 요청이 아니라 <b>멤버십 프로젝션</b>에서 읽는다는 것이 핵심이다.
 */
@ExtendWith(MockitoExtension.class)
class CardOrgAuthorizerTest {

    @Mock LoadOrgProjectionPort loadOrgProjectionPort;
    @InjectMocks CardOrgAuthorizer authorizer;

    @Test
    @DisplayName("허용 역할이면 그 역할을 돌려준다")
    void allowedRolePasses() {
        when(loadOrgProjectionPort.findMemberRole(3001L, 100L)).thenReturn(Optional.of(OrgRole.OWNER));

        assertThat(authorizer.requireRole(3001L, 100L, Set.of(OrgRole.OWNER), "카드계정 개설"))
                .isEqualTo(OrgRole.OWNER);
    }

    @Test
    @DisplayName("역할이 부족하면 403 이고 필요·현재 역할을 메시지에 남긴다")
    void insufficientRoleIsForbidden() {
        when(loadOrgProjectionPort.findMemberRole(3001L, 200L)).thenReturn(Optional.of(OrgRole.STAFF));

        assertThatThrownBy(() -> authorizer.requireRole(3001L, 200L, Set.of(OrgRole.OWNER), "카드계정 개설"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카드계정 개설")
                .hasMessageContaining("STAFF")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FORBIDDEN);
    }

    /**
     * 조직에서 제거된 멤버는 {@code findMemberRole} 이 빈 Optional 을 준다(active=true 만 조회).
     * 여기서 403 이 나야 "퇴사자가 계속 카드 권한을 갖는" 경로가 닫힌다.
     */
    @Test
    @DisplayName("활성 구성원이 아니면 403 — 비활성·미도착 모두 권한 없음으로 본다")
    void nonMemberIsForbidden() {
        when(loadOrgProjectionPort.findMemberRole(3001L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizer.requireRole(3001L, 999L, Set.of(OrgRole.OWNER), "카드계정 개설"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("활성 구성원이 아닙니다")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FORBIDDEN);
    }
}
