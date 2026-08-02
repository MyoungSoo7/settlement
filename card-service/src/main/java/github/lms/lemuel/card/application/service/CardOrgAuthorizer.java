package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 조직 역할 기반 인가. organization-service 의 {@code OrgAuthorizer} 를 미러링한다.
 *
 * <p>★ 권한은 요청 파라미터가 아니라 JWT 주체(uid)가 그 조직의 멤버 프로젝션에서 갖는 역할로 판정한다.
 * 요청 본문의 역할을 믿으면 그 자체가 권한 상승 경로다(IDOR).
 *
 * <p>프로젝션이 낡으면(이벤트 소비 지연·유실) 권한 판정이 <b>보수적으로</b> 틀린다 —
 * 아직 도착하지 않은 멤버십은 "권한 없음"이 되어 403 이 난다. 반대 방향(제거된 멤버가 계속
 * 통과)은 {@code findMemberRole} 이 active=true 만 반환하는 것으로 막는다.
 */
@Component
public class CardOrgAuthorizer {

    private final LoadOrgProjectionPort loadOrgProjectionPort;

    public CardOrgAuthorizer(LoadOrgProjectionPort loadOrgProjectionPort) {
        this.loadOrgProjectionPort = loadOrgProjectionPort;
    }

    /**
     * @param action 실패 메시지에 들어갈 작업 이름(예: "카드계정 개설")
     * @return 판정된 역할 — 호출자가 역할별로 분기해야 할 때 쓴다
     * @throws BusinessException {@link ErrorCode#CARD_FORBIDDEN}(403) — 비활성/비구성원이거나 역할 부족
     */
    public OrgRole requireRole(Long organizationId, Long userId, Set<OrgRole> allowed, String action) {
        OrgRole role = loadOrgProjectionPort.findMemberRole(organizationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_FORBIDDEN,
                        "조직 " + organizationId + " 의 활성 구성원이 아닙니다."));
        if (!allowed.contains(role)) {
            throw new BusinessException(ErrorCode.CARD_FORBIDDEN,
                    action + " 권한이 없습니다. 필요=" + allowed + ", 현재=" + role);
        }
        return role;
    }
}
