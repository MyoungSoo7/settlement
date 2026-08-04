package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase.CaptureHoldCommand;
import github.lms.lemuel.card.domain.CardCapture;
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
import java.time.Instant;

/**
 * VAN 매입 어댑터 — VAN 네트워크가 매입 확정을 푸시하는 경로.
 *
 * <p>경로: {@code POST /van/v1/captures}
 *
 * <p>보안: {@code /van/**} 경로는 내부망에서만 접근 가능. API Gateway 가 외부 미노출.
 */
@RestController
@RequestMapping("/van/v1/captures")
public class CaptureVanAdapter {

    private final CaptureHoldUseCase captureHoldUseCase;

    public CaptureVanAdapter(CaptureHoldUseCase captureHoldUseCase) {
        this.captureHoldUseCase = captureHoldUseCase;
    }

    @PostMapping
    public ResponseEntity<VanCaptureResponse> capture(
            @Valid @RequestBody VanCaptureRequest request) {

        CardCapture capture = captureHoldUseCase.capture(
                new CaptureHoldCommand(
                        request.captureId(),
                        request.authorizationId(),
                        request.capturedAmount(),
                        request.merchantName(),
                        request.capturedAt() != null ? request.capturedAt() : Instant.now()
                ));

        return ResponseEntity.ok(new VanCaptureResponse(
                capture.getCaptureId(),
                capture.getAuthorizationId(),
                capture.getCapturedAmount().toPlainString()
        ));
    }

    // ── DTO ──

    /**
     * VAN 매입 요청 본문.
     *
     * @param captureId       VAN 매입번호 — 멱등 자연키
     * @param authorizationId 매입 대상 승인번호
     * @param capturedAmount  매입 금액(양수)
     * @param merchantName    가맹점 이름(optional)
     * @param capturedAt      매입 확정 시각(optional — null 이면 수신 시각)
     */
    public record VanCaptureRequest(
            @NotBlank String captureId,
            @NotBlank String authorizationId,
            @NotNull @DecimalMin("0.01") BigDecimal capturedAmount,
            String merchantName,
            Instant capturedAt
    ) {
    }

    /**
     * VAN 매입 응답.
     *
     * @param captureId       저장된 매입번호
     * @param authorizationId 승인번호
     * @param capturedAmount  매입 금액(문자열, DATA-STANDARD N5)
     */
    public record VanCaptureResponse(
            String captureId,
            String authorizationId,
            String capturedAmount
    ) {
    }
}
