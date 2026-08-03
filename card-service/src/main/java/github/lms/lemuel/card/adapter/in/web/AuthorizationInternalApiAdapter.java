package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizationResult;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 내부 API 어댑터 — 다른 내부 서비스가 카드 승인을 요청하는 경로.
 *
 * <p>경로: {@code POST /internal/api/v1/cards/{cardId}/authorizations}
 *
 * <p>이 어댑터도 {@link AuthorizationVanAdapter} 와 동일한 {@link AuthorizeCardUseCase} 포트를
 * 호출하므로 두 진입점의 승인 결과가 항상 일치한다(EntryPointParity).
 *
 * <p>경로 변수 {@code cardId} 와 본문의 {@code cardId} 가 일치해야 한다 — 불일치 시 400 반환.
 * 이를 통해 경로·페이로드 불일치로 인한 조용한 오작동을 예방한다.
 */
@RestController
@RequestMapping("/internal/api/v1/cards")
public class AuthorizationInternalApiAdapter {

    private final AuthorizeCardUseCase authorizeCardUseCase;

    public AuthorizationInternalApiAdapter(AuthorizeCardUseCase authorizeCardUseCase) {
        this.authorizeCardUseCase = authorizeCardUseCase;
    }

    /**
     * 내부 승인 요청.
     *
     * @param cardId 경로 변수(pathId) — 본문의 cardId 와 반드시 일치해야 한다
     */
    @PostMapping("/{cardId}/authorizations")
    public ResponseEntity<?> authorize(
            @PathVariable Long cardId,
            @Valid @RequestBody InternalAuthorizationRequest request) {

        if (!cardId.equals(request.cardId())) {
            return ResponseEntity.badRequest().body(
                    "경로의 cardId(" + cardId + ")와 본문의 cardId(" + request.cardId() + ")가 다릅니다.");
        }

        AuthorizationResult result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(
                        request.authorizationId(),
                        request.cardId(),
                        request.amount(),
                        request.merchantName(),
                        request.mcc(),
                        request.overseas(),
                        request.online()
                ));

        return ResponseEntity.ok(
                result.approved()
                        ? InternalAuthorizationResponse.approved(
                                result.hold().getAuthorizationId(),
                                result.hold().getAmount(),
                                result.hold().getAuthorizedAt())
                        : InternalAuthorizationResponse.declined(result.declineReason().name()));
    }

    // ── DTO ──

    /**
     * 내부 API 승인 요청 본문.
     *
     * @param authorizationId 멱등 자연키 — 호출자가 생성하는 고유 ID
     * @param cardId          승인 대상 카드 ID(경로 변수와 일치해야 함)
     * @param amount          승인 금액(양수)
     * @param merchantName    가맹점 이름(optional)
     * @param mcc             4자리 가맹점 업종코드(optional)
     * @param overseas        해외 거래 여부
     * @param online          온라인 거래 여부
     */
    public record InternalAuthorizationRequest(
            @NotBlank String authorizationId,
            @NotNull Long cardId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            String merchantName,
            String mcc,
            boolean overseas,
            boolean online
    ) {
    }

    /**
     * 내부 API 승인 응답.
     *
     * @param approved          승인 여부
     * @param authorizationId   승인 시 authorizationId, 거절 시 null
     * @param approvedAmount    승인 금액(승인 시), 거절 시 null
     * @param authorizedAt      승인 시각(승인 시), 거절 시 null
     * @param declineReason     거절 시 사유 코드, 승인 시 null
     */
    public record InternalAuthorizationResponse(
            boolean approved,
            String authorizationId,
            BigDecimal approvedAmount,
            Instant authorizedAt,
            String declineReason
    ) {
        static InternalAuthorizationResponse approved(String authId, BigDecimal amount, Instant at) {
            return new InternalAuthorizationResponse(true, authId, amount, at, null);
        }

        static InternalAuthorizationResponse declined(String reason) {
            return new InternalAuthorizationResponse(false, null, null, null, reason);
        }
    }
}
