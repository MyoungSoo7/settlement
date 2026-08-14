package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase.SubmitApplicationCommand;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase.IssuedPolicySummary;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotMatchedException;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationOwnershipException;
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
 * <p><b>인증·인가</b>: 접수는 인증된 FC 본인이 하고, <b>접수자(fcId)는 JWT 주체에서만 파생</b>한다
 * ({@link FcIdentity}) — 본문으로 받으면 남의 fcId 를 적어 수수료 수령인을 가로챌 수 있다.
 * 심사 전이(착수·승인·반려)는 shared-common SecurityConfig 에서 ADMIN/MANAGER 로 막는다 —
 * 계약을 발행하고 수수료 12회를 확정시키는 행위라 접수자와 같은 권한일 수 없다.
 *
 * <p>승인은 완전판매 게이트(교부 증빙 필수)를 통과해야 한다 — 미교부 시 409.
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
                request.consultationId(), request.productCode(), requireFcId(),
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
     * 현재 요청자의 FC 식별자 — 요청 본문이 아니라 JWT 주체에서만 파생한다(IDOR 차단).
     *
     * <p>접수자는 발행될 계약의 <b>수수료 수령인</b>이 된다. 본문 fcId 를 신뢰하면 남의 fcId 를
     * 적는 것만으로 타인 명의로 청약을 만들고 수수료를 자기 것으로 돌릴 수 있다.
     *
     * @throws ApplicationOwnershipException userId 가 없는 구(舊) 토큰 — 403 으로 매핑된다
     */
    private static String requireFcId() {
        String fcId = FcIdentity.currentFcId();
        if (fcId == null) {
            throw ApplicationOwnershipException.unidentifiedRequester();
        }
        return fcId;
    }

    /**
     * 접수자(fcId) 필드는 의도적으로 없다 — JWT 주체에서만 파생한다(IDOR 차단).
     *
     * @param insuredRrn      선택 — 제공 시 PII 분리 테이블에 암호화 저장
     * @param partnerBankCode BANCA 청약 시 필수 (도메인이 강제)
     */
    public record SubmitRequest(String consultationId,
                                @NotBlank String productCode,
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

    @ExceptionHandler(ApplicationOwnershipException.class)
    public ResponseEntity<Map<String, String>> forbidden(ApplicationOwnershipException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
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

    @ExceptionHandler(ApplicationDocumentNotMatchedException.class)
    public ResponseEntity<Map<String, String>> documentGate(ApplicationDocumentNotMatchedException e) {
        // 서류 대사 미통과(ADR 0036) — 형식이 아니라 "지금은 승인 불가"라서 422 (409 는 전이·미교부 몫)
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({InvalidApplicationException.class, InvalidSalesChannelException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
