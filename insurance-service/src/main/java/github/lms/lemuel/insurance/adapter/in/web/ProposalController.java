package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConversionResult;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConvertProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.CreateProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;
import github.lms.lemuel.insurance.application.port.in.GetProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProposalSheetUseCase;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;
import github.lms.lemuel.insurance.domain.exception.InvalidProposalTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;
import github.lms.lemuel.insurance.domain.exception.ProposalNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException;
import github.lms.lemuel.insurance.domain.exception.RateNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 가입설계 API — 산출·조회·청약 전환·설계서 PDF.
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * 전환 요청의 금액은 받지 않는다 — 설계 스냅샷의 산출값을 서버가 주입한다(D-P3).
 */
@RestController
@RequestMapping("/api/insurance/proposals")
public class ProposalController {

    private final CreateProposalUseCase createUseCase;
    private final GetProposalUseCase getUseCase;
    private final ConvertProposalUseCase convertUseCase;
    private final RenderProposalSheetUseCase renderUseCase;

    public ProposalController(CreateProposalUseCase createUseCase,
                              GetProposalUseCase getUseCase,
                              ConvertProposalUseCase convertUseCase,
                              RenderProposalSheetUseCase renderUseCase) {
        this.createUseCase = createUseCase;
        this.getUseCase = getUseCase;
        this.convertUseCase = convertUseCase;
        this.renderUseCase = renderUseCase;
    }

    /** 가입설계 산출 — 201 + 산출 스냅샷(적용 요율·보험료·유효기한). 설계자는 JWT 주체. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProposalSummary create(@Valid @RequestBody CreateRequest request) {
        return createUseCase.create(new CreateProposalCommand(
                request.consultationId(), request.productCode(), requireFcId(),
                request.insuredName(), request.insuredBirthDate(), request.insuredGender(),
                request.coverageAmount(), request.paymentTermYears(),
                request.salesChannel(), request.partnerBankCode()));
    }

    /** 가입설계 단건 조회 — 본인 설계만(타인 403). */
    @GetMapping("/{proposalId}")
    public ProposalSummary get(@PathVariable String proposalId) {
        return getUseCase.get(proposalId, requireFcId());
    }

    /** 청약 전환 — 금액 파라미터 없음(서버 주입). 만기 409, 타인 설계 403. */
    @PostMapping("/{proposalId}/convert")
    public ConversionResult convert(@PathVariable String proposalId,
                                    @Valid @RequestBody ConvertRequest request) {
        return convertUseCase.convert(new ConvertProposalCommand(
                proposalId, requireFcId(), request.contractorName(),
                request.insuredRrn(), request.contractorPhone()));
    }

    /** 가입설계서 PDF — 본인 설계만(피보험자·보장금액이 실린다). */
    @GetMapping("/{proposalId}/sheet")
    public ResponseEntity<byte[]> sheet(@PathVariable String proposalId) {
        byte[] pdf = renderUseCase.render(proposalId, requireFcId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition",
                        "attachment; filename=\"proposal-" + proposalId + ".pdf\"")
                .body(pdf);
    }

    /**
     * 현재 요청자의 FC 식별자 — 요청 본문이 아니라 JWT 주체에서만 파생한다(IDOR 차단).
     *
     * @throws ProposalOwnershipException userId 가 없는 구(舊) 토큰 — 403 으로 매핑된다
     *                                    (전역 핸들러의 500 폴백을 타지 않도록 도메인 예외로 통일)
     */
    private static String requireFcId() {
        String fcId = FcIdentity.currentFcId();
        if (fcId == null) {
            throw ProposalOwnershipException.unidentifiedRequester();
        }
        return fcId;
    }

    /**
     * 설계자(fcId) 필드는 의도적으로 없다 — JWT 주체에서만 파생한다(IDOR 차단).
     *
     * @param insuredBirthDate 보험나이 산정에만 사용 — 저장하지 않는다(PII 최소화)
     * @param partnerBankCode  BANCA 설계 시 필수 (도메인이 강제)
     */
    public record CreateRequest(String consultationId,
                                @NotBlank String productCode,
                                @NotBlank String insuredName,
                                @NotNull @Past LocalDate insuredBirthDate,
                                @NotNull Gender insuredGender,
                                @NotNull @DecimalMin(value = "0.01") BigDecimal coverageAmount,
                                @Min(1) int paymentTermYears,
                                @NotNull SalesChannel salesChannel,
                                String partnerBankCode) {
    }

    /**
     * 요청자(fcId) 필드는 의도적으로 없다 — JWT 주체에서만 파생한다(IDOR 차단).
     *
     * @param insuredRrn      선택 — 제공 시 PII 분리 테이블에 암호화 저장
     * @param contractorPhone 선택 — 제공 시 PII 분리 테이블에 암호화 저장
     */
    public record ConvertRequest(@NotBlank String contractorName,
                                 String insuredRrn,
                                 String contractorPhone) {
    }

    @ExceptionHandler({ProposalNotFoundException.class, ProductNotFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RateNotFoundException.class)
    public ResponseEntity<Map<String, String>> rateNotFound(RateNotFoundException e) {
        // 요청 형식은 맞지만 요율표상 산출 불가한 조합 — 422
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({InvalidProposalTransitionException.class, ProposalExpiredException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProposalOwnershipException.class)
    public ResponseEntity<Map<String, String>> forbidden(ProposalOwnershipException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({InvalidProposalException.class, InvalidSalesChannelException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
