package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.dto.CardAccountResponse;
import github.lms.lemuel.card.adapter.in.web.dto.CardResponse;
import github.lms.lemuel.card.adapter.in.web.dto.ChangeCardStatusRequest;
import github.lms.lemuel.card.adapter.in.web.dto.ChangeSubLimitRequest;
import github.lms.lemuel.card.adapter.in.web.dto.IssueCardRequest;
import github.lms.lemuel.card.adapter.in.web.dto.OpenCardAccountRequest;
import github.lms.lemuel.card.application.port.in.ChangeCardStatusUseCase;
import github.lms.lemuel.card.application.port.in.ChangeCardStatusUseCase.ChangeCardStatusCommand;
import github.lms.lemuel.card.application.port.in.ChangeSubLimitUseCase;
import github.lms.lemuel.card.application.port.in.ChangeSubLimitUseCase.ChangeSubLimitCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase.OpenCardAccountCommand;
import github.lms.lemuel.card.application.port.in.QueryCardUseCase;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final ChangeSubLimitUseCase changeSubLimitUseCase;
    private final ChangeCardStatusUseCase changeCardStatusUseCase;
    private final QueryCardUseCase queryCardUseCase;

    public CardController(OpenCardAccountUseCase openCardAccountUseCase,
                          IssueCardUseCase issueCardUseCase,
                          ChangeSubLimitUseCase changeSubLimitUseCase,
                          ChangeCardStatusUseCase changeCardStatusUseCase,
                          QueryCardUseCase queryCardUseCase) {
        this.openCardAccountUseCase = openCardAccountUseCase;
        this.issueCardUseCase = issueCardUseCase;
        this.changeSubLimitUseCase = changeSubLimitUseCase;
        this.changeCardStatusUseCase = changeCardStatusUseCase;
        this.queryCardUseCase = queryCardUseCase;
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

    /**
     * 서브한도 변경. 카드계정을 경로에 넣지 않는 이유는 카드 하나로 이미 결정되는 값이기 때문이다 —
     * 두 번 받으면 "경로의 계정과 카드의 실제 계정이 어긋날 때"를 검증할 책임이 생긴다.
     */
    @PatchMapping("/cards/{cardId}/limit")
    public ResponseEntity<CardResponse> changeSubLimit(@PathVariable Long cardId,
                                                       @Valid @RequestBody ChangeSubLimitRequest request,
                                                       Authentication authentication) {
        Card card = changeSubLimitUseCase.change(new ChangeSubLimitCommand(
                cardId, request.subLimit(), callerUserId(authentication)));
        return ResponseEntity.ok(CardResponse.from(card));
    }

    /** 카드 상태 변경(정지·재개·해지). 목표 상태를 값으로 받는다 — 전이 규칙의 정본은 도메인이다. */
    @PatchMapping("/cards/{cardId}/status")
    public ResponseEntity<CardResponse> changeStatus(@PathVariable Long cardId,
                                                     @Valid @RequestBody ChangeCardStatusRequest request,
                                                     Authentication authentication) {
        Card card = changeCardStatusUseCase.change(new ChangeCardStatusCommand(
                cardId, request.status(), request.reason(), callerUserId(authentication)));
        return ResponseEntity.ok(CardResponse.from(card));
    }

    @GetMapping("/accounts/{cardAccountId}")
    public ResponseEntity<CardAccountResponse> getAccount(@PathVariable Long cardAccountId,
                                                          Authentication authentication) {
        CardAccount account = queryCardUseCase.getAccount(cardAccountId, callerUserId(authentication));
        return ResponseEntity.ok(CardAccountResponse.from(account));
    }

    @GetMapping("/accounts/{cardAccountId}/cards")
    public ResponseEntity<List<CardResponse>> listCards(@PathVariable Long cardAccountId,
                                                        Authentication authentication) {
        List<CardResponse> cards = queryCardUseCase.listCards(cardAccountId, callerUserId(authentication))
                .stream().map(CardResponse::from).toList();
        return ResponseEntity.ok(cards);
    }

    /**
     * 내 카드 목록. 경로에 사용자 식별자가 <b>없다</b>는 것이 이 엔드포인트의 전부다 —
     * 대상을 경로로 받는 순간 {@code /cards/{userId}} 는 그대로 IDOR 경로가 된다.
     */
    @GetMapping("/cards/me")
    public ResponseEntity<List<CardResponse>> listMyCards(Authentication authentication) {
        List<CardResponse> cards = queryCardUseCase.listMyCards(callerUserId(authentication))
                .stream().map(CardResponse::from).toList();
        return ResponseEntity.ok(cards);
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
