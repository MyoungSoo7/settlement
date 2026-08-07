package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase.SubmitApplicationCommand;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase.IssuedPolicySummary;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.DisclosureNotDeliveredException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 청약 접수·언더라이팅 API.
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * 승인은 완전판매 게이트(교부 증빙 필수)를 통과해야 한다 — 미교부 시 409.
 */
@RestController
@RequestMapping("/api/insurance/applications")
public class InsuranceApplicationController {

    private final SubmitApplicationUseCase submitUseCase;
    private final UnderwriteApplicationUseCase underwriteUseCase;

    public InsuranceApplicationController(
            SubmitApplicationUseCase submitUseCase,
            UnderwriteApplicationUseCase underwriteUseCase) {
        this.submitUseCase = submitUseCase;
        this.underwriteUseCase = underwriteUseCase;
    }

    /** 청약 접수 — 201 + applicationId. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> submit(@Valid @RequestBody SubmitRequest request) {
        String applicationId = submitUseCase.submit(new SubmitApplicationCommand(
                request.consultationId(), request.productCode(), request.fcId(),
                request.insuredName(), request.contractorName(),
                request.insuredRrn(), request.contractorPhone(),
                request.desiredCoverage(), request.desiredPremium(),
                request.salesChannel(), request.partnerBankCode()));
        return Map.of("applicationId", applicationId);
    }

    /** 심사 착수 — SUBMITTED → UNDER_REVIEW. */
    @PostMapping("/{applicationId}/review")
    public Map<String, String> startReview(@PathVariable String applicationId) {
        underwriteUseCase.startReview(applicationId);
        return Map.of("applicationId", applicationId, "status", "UNDER_REVIEW");
    }

    /** 승인 — 계약 발행 + 수수료 12회 확정. 완전판매 게이트 미통과 시 409. */
    @PostMapping("/{applicationId}/approve")
    public IssuedPolicySummary approve(@PathVariable String applicationId) {
        return underwriteUseCase.approve(applicationId);
    }

    /** 반려 — 사유 필수. */
    @PostMapping("/{applicationId}/reject")
    public Map<String, String> reject(@PathVariable String applicationId,
                                      @Valid @RequestBody RejectRequest request) {
        underwriteUseCase.reject(applicationId, request.reason());
        return Map.of("applicationId", applicationId, "status", "REJECTED");
    }

    /**
     * @param insuredRrn      선택 — 제공 시 PII 분리 테이블에 암호화 저장
     * @param partnerBankCode BANCA 청약 시 필수 (도메인이 강제)
     */
    public record SubmitRequest(String consultationId,
                                @NotBlank String productCode,
                                @NotBlank String fcId,
                                @NotBlank String insuredName,
                                @NotBlank String contractorName,
                                String insuredRrn,
                                String contractorPhone,
                                @NotNull @DecimalMin(value = "0.01") BigDecimal desiredCoverage,
                                @NotNull @DecimalMin(value = "0.01") BigDecimal desiredPremium,
                                @NotNull SalesChannel salesChannel,
                                String partnerBankCode) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    @ExceptionHandler({ApplicationNotFoundException.class, ProductNotFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidApplicationTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(InvalidApplicationTransitionException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DisclosureNotDeliveredException.class)
    public ResponseEntity<Map<String, String>> disclosureGate(DisclosureNotDeliveredException e) {
        // 교부 후 재시도 가능한 업무 규칙 충돌 — 409
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({InvalidApplicationException.class, InvalidSalesChannelException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
