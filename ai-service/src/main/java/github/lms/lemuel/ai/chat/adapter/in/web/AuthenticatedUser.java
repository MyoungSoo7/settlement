package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/** JWT 인증 주체에서 userId 를 꺼내는 헬퍼 — 대화 소유권의 기준 키. */
final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /**
     * uid claim 이 없는 구(舊) 토큰은 대화 소유자를 특정할 수 없으므로 401 로 재로그인을 유도한다.
     */
    static Long userId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "사용자 식별자가 없는 토큰입니다. 다시 로그인해 주세요.");
    }
}
