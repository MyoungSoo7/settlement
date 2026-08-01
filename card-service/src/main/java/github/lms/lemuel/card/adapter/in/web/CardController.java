package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.dto.CardAccountResponse;
import github.lms.lemuel.card.adapter.in.web.dto.CardResponse;
import github.lms.lemuel.card.adapter.in.web.dto.IssueCardRequest;
import github.lms.lemuel.card.adapter.in.web.dto.OpenCardAccountRequest;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase.OpenCardAccountCommand;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final IssueCardUseCase issueCardUseCase;

    public CardController(OpenCardAccountUseCase openCardAccountUseCase,
                          IssueCardUseCase issueCardUseCase) {
        this.openCardAccountUseCase = openCardAccountUseCase;
        this.issueCardUseCase = issueCardUseCase;
    }

    @PostMapping("/accounts")
    public ResponseEntity<CardAccountResponse> openAccount(@Valid @RequestBody OpenCardAccountRequest request,
                                                           Authentication authentication) {
        CardAccount account = openCardAccountUseCase.open(
                new OpenCardAccountCommand(request.organizationId(), callerUserId(authentication)));
        return ResponseEntity.status(HttpStatus.CREATED).body(CardAccountResponse.from(account));
    }

    /**
     * 임직원 카드 발급. 카드계정은 경로에서, 발급 대상은 본문에서, <b>요청자는 JWT 에서</b> 온다 —
     * 세 값의 출처가 다른 것이 곧 권한 모델이다.
     */
    @PostMapping("/accounts/{cardAccountId}/cards")
    public ResponseEntity<CardResponse> issueCard(@PathVariable Long cardAccountId,
                                                  @Valid @RequestBody IssueCardRequest request,
                                                  Authentication authentication) {
        Card card = issueCardUseCase.issue(new IssueCardCommand(
                cardAccountId, request.holderUserId(), request.subLimit(), callerUserId(authentication)));
        return ResponseEntity.status(HttpStatus.CREATED).body(CardResponse.from(card));
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
