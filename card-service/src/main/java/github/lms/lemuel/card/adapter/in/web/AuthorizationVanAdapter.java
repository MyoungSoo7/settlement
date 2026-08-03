package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizationResult;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * VAN 시뮬레이터 어댑터 — 외부 VAN 네트워크가 카드 승인을 요청하는 경로를 모방한다.
 *
 * <p>경로: {@code POST /van/v1/authorizations}
 *
 * <p>이 어댑터의 책임은 VAN 형식 ↔ 내부 커맨드 변환뿐이다 — 승인 결정 로직은
 * {@link AuthorizeCardUseCase}({@code AuthorizeCardService})에만 있다.
 * {@link AuthorizationInternalApiAdapter} 와 동일한 포트를 호출해 두 진입점이 항상 같은 결과를 낸다.
 *
 * <p>보안: {@code /van/**} 경로는 내부망에서만 접근 가능하고 공개 엔드포인트가 아니다.
 * 운영에서는 API Gateway 가 이 경로를 외부에 노출하지 않는다.
 */
@RestController
@RequestMapping("/van/v1/authorizations")
public class AuthorizationVanAdapter {

    private final AuthorizeCardUseCase authorizeCardUseCase;

    public AuthorizationVanAdapter(AuthorizeCardUseCase authorizeCardUseCase) {
        this.authorizeCardUseCase = authorizeCardUseCase;
    }

    /**
     * VAN 승인 요청. {@code networkRequestId} 가 멱등 키다 — 동일 요청 재전송 시
     * 중복 홀드를 생성하지 않고 기존 홀드를 반환한다.
     */
    @PostMapping
    public ResponseEntity<VanAuthorizationResponse> authorize(
            @Valid @RequestBody VanAuthorizationRequest request) {

        AuthorizationResult result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(
                        request.networkRequestId(),
                        request.cardId(),
                        request.amount(),
                        request.merchantName(),
                        request.mcc(),
                        request.overseas(),
                        request.online()
                ));

        VanAuthorizationResponse response = result.approved()
                ? VanAuthorizationResponse.approved(result.hold().getAuthorizationId())
                : VanAuthorizationResponse.declined(result.declineReason().name());

        return ResponseEntity.ok(response);
    }

    // ── DTO ──

    /**
     * VAN 네트워크 승인 요청 본문.
     *
     * @param networkRequestId VAN 고유 요청 ID — 멱등 자연키(authorizationId 로 사용)
     * @param cardId           승인 대상 카드 ID
     * @param amount           승인 금액(양수, BigDecimal)
     * @param merchantName     가맹점 이름(optional)
     * @param mcc              4자리 가맹점 업종코드(optional)
     * @param overseas         해외 거래 여부
     * @param online           온라인 거래 여부
     */
    public record VanAuthorizationRequest(
            @NotBlank String networkRequestId,
            @NotNull Long cardId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            String merchantName,
            String mcc,
            boolean overseas,
            boolean online
    ) {
    }

    /**
     * VAN 네트워크 승인 응답.
     *
     * @param approved         승인 여부
     * @param authorizationCode 승인 시 authorizationId, 거절 시 null
     * @param declineCode      거절 시 {@link github.lms.lemuel.card.domain.DeclineReason} 이름, 승인 시 null
     */
    public record VanAuthorizationResponse(
            boolean approved,
            String authorizationCode,
            String declineCode
    ) {
        static VanAuthorizationResponse approved(String authorizationCode) {
            return new VanAuthorizationResponse(true, authorizationCode, null);
        }

        static VanAuthorizationResponse declined(String declineCode) {
            return new VanAuthorizationResponse(false, null, declineCode);
        }
    }
}
