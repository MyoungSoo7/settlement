package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.dto.CardAccountResponse;
import github.lms.lemuel.card.adapter.in.web.dto.OpenCardAccountRequest;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase.OpenCardAccountCommand;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 법인카드 REST 표면. 경로 {@code /api/cards/**} 는 shared-common SecurityConfig 에서
 * <b>인증만</b> 요구하고, 조직 역할(OWNER/MANAGER/STAFF) 판정은 {@code CardOrgAuthorizer} 가
 * 멤버십 프로젝션으로 수행한다 — 역할을 요청에서 받지 않는 것이 IDOR 방어의 핵심이다.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final OpenCardAccountUseCase openCardAccountUseCase;

    public CardController(OpenCardAccountUseCase openCardAccountUseCase) {
        this.openCardAccountUseCase = openCardAccountUseCase;
    }

    @PostMapping("/accounts")
    public ResponseEntity<CardAccountResponse> openAccount(@Valid @RequestBody OpenCardAccountRequest request,
                                                           Authentication authentication) {
        CardAccount account = openCardAccountUseCase.open(
                new OpenCardAccountCommand(request.organizationId(), callerUserId(authentication)));
        return ResponseEntity.status(HttpStatus.CREATED).body(CardAccountResponse.from(account));
    }

    /** JWT 인증 주체에서 userId 를 추출한다. 미인증/식별불가면 403. */
    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }
}
