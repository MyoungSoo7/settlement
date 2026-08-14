package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 설계사(FC) 식별자를 <b>JWT 주체에서만</b> 파생하는 단일 초크포인트.
 *
 * <p><b>IDOR 방지</b>: FC 식별자를 요청(본문·경로·쿼리)에서 받으면, 남의 fcId 를 아는 것만으로
 * 소유권 대조를 통과할 수 있다. 요청 DTO 에 fcId 필드를 두지 않고 이 클래스로만 파생한다
 * (settlement {@code SellerBankAccountSelfController} 와 동일 관례).
 *
 * <p>현재 파생 규칙은 <b>JWT userId 를 그대로 FC 식별자로 쓴다</b> —
 * settlement 의 sellerId = userId 관례와 동형이다. FC 레지스트리(organization 프로젝션 소비)가
 * 생기면 이 메서드 한 곳만 바꾸면 된다.
 *
 * <p>사용처: 가입설계 · 계약(해지·철회) · <b>청약 접수</b> · <b>상품설명서 교부</b>. 즉 FC 식별자가
 * 필요한 모든 쓰기 경로가 이 한 곳을 통과하며, 요청 DTO 어디에도 fcId/deliveredBy 필드는 없다.
 * 심사 전이(승인·반려)는 FC 가 아니라 백오피스 역할이 하므로 경로 매처가 따로 막는다.
 */
public final class FcIdentity {

    private FcIdentity() {
    }

    /**
     * 현재 인증 주체의 FC 식별자.
     *
     * @return FC 식별자, 또는 인증이 없거나 userId 가 없는 구(舊) 토큰이면 {@code null}
     */
    public static String currentFcId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return String.valueOf(principal.userId());
        }
        return null;
    }
}
