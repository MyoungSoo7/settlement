package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.VoidHoldUseCase;
import github.lms.lemuel.card.application.port.in.VoidHoldUseCase.VoidHoldCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VAN 취소 어댑터 — VAN 네트워크가 매입 전 취소를 푸시하는 경로.
 *
 * <p>경로: {@code POST /van/v1/voids}
 *
 * <p>취소(void) 는 매입 전에만 가능하다. 매입 후 취소는 환불({@code /van/v1/refunds})을 사용한다.
 */
@RestController
@RequestMapping("/van/v1/voids")
public class VoidVanAdapter {

    private final VoidHoldUseCase voidHoldUseCase;

    public VoidVanAdapter(VoidHoldUseCase voidHoldUseCase) {
        this.voidHoldUseCase = voidHoldUseCase;
    }

    @PostMapping
    public ResponseEntity<VanVoidResponse> voidHold(
            @Valid @RequestBody VanVoidRequest request) {

        voidHoldUseCase.voidHold(new VoidHoldCommand(
                request.authorizationId(),
                request.reason()
        ));

        return ResponseEntity.ok(new VanVoidResponse(request.authorizationId(), "VOIDED"));
    }

    // ── DTO ──

    /**
     * VAN 취소 요청.
     *
     * @param authorizationId 취소 대상 승인번호(자연키)
     * @param reason          취소 사유(optional)
     */
    public record VanVoidRequest(
            @NotBlank String authorizationId,
            String reason
    ) {
    }

    /**
     * VAN 취소 응답.
     *
     * @param authorizationId 취소된 승인번호
     * @param status          처리 결과 상태("VOIDED")
     */
    public record VanVoidResponse(
            String authorizationId,
            String status
    ) {
    }
}
