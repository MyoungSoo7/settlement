package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase.AttachCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.in.GetCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase.ReviewCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 담보서류 첨부·조회·리뷰 API (ADR 0036 확산).
 *
 * <p><b>인가</b>: 첨부·조회는 본인(차주) 또는 운영자, 리뷰 종결은 운영자(ADMIN/MANAGER) 전용 —
 * {@code SecuredLoanController}·{@code CollateralController} 와 동형. 업로더·리뷰어 식별자는
 * 요청이 아니라 JWT 주체에서만 파생한다.
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST /loans/secured/{loanId}/collateral/documents} — 멀티파트 업로드 → OCR → 자동 대사</li>
 *   <li>{@code GET /loans/secured/{loanId}/collateral/documents/latest} — 최신 서류(승인 게이트와 같은 기준)</li>
 *   <li>{@code POST /loans/secured/collateral-documents/{documentId}/review} — NEEDS_REVIEW 육안 리뷰 종결(운영자)</li>
 * </ul>
 */
@Tag(name = "Collateral Document", description = "담보서류 OCR 대사 (ADR 0036)")
@RestController
public class CollateralDocumentController {

    private final AttachCollateralDocumentUseCase attachUseCase;
    private final GetCollateralDocumentUseCase getUseCase;
    private final ReviewCollateralDocumentUseCase reviewUseCase;
    private final LoadSecuredLoanPort loadSecuredLoanPort;

    public CollateralDocumentController(AttachCollateralDocumentUseCase attachUseCase,
                                        GetCollateralDocumentUseCase getUseCase,
                                        ReviewCollateralDocumentUseCase reviewUseCase,
                                        LoadSecuredLoanPort loadSecuredLoanPort) {
        this.attachUseCase = attachUseCase;
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
        this.loadSecuredLoanPort = loadSecuredLoanPort;
    }

    @Operation(summary = "담보서류 업로드 → OCR → 자동 대사",
            description = "감정평가액·선순위 채권최고액·평가기준일을 담보 설정값과 대조한다. "
                    + "같은 파일 재업로드는 기존 서류 반환(멱등, OCR 재호출 없음).")
    @PostMapping("/loans/secured/{loanId}/collateral/documents")
    public ResponseEntity<CollateralDocumentResponse> attach(
            @PathVariable Long loanId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long caller = requireOwnerOrOperator(loanId, authentication);
        CollateralDocument document = attachUseCase.attach(new AttachCollateralDocumentCommand(
                loanId, caller, file.getOriginalFilename(), file.getContentType(), bytesOf(file)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(document));
    }

    @Operation(summary = "대출의 최신 담보서류 조회 — 승인 게이트가 판정하는 것과 같은 기준")
    @GetMapping("/loans/secured/{loanId}/collateral/documents/latest")
    public ResponseEntity<CollateralDocumentResponse> latest(@PathVariable Long loanId,
                                                             Authentication authentication) {
        requireOwnerOrOperator(loanId, authentication);
        return getUseCase.latestForLoan(loanId)
                .map(document -> ResponseEntity.ok(toResponse(document)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "담보서류 리뷰 큐 — 상태별 목록 (운영자)",
            description = "기본 NEEDS_REVIEW, 최신 우선. 리뷰 큐 화면이 이 목록을 그린다.")
    @GetMapping("/loans/secured/collateral-documents")
    public ResponseEntity<java.util.List<CollateralDocumentResponse>> queue(
            @RequestParam(defaultValue = "NEEDS_REVIEW") github.lms.lemuel.loan.domain.CollateralDocumentStatus status,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(getUseCase.byStatus(status, limit).stream()
                .map(CollateralDocumentController::toResponse)
                .toList());
    }

    @Operation(summary = "NEEDS_REVIEW 서류 육안 리뷰 종결 (운영자)",
            description = "신뢰도 미달·선순위/평가기준일 판독 불가 서류를 MATCHED/MISMATCHED 로 종결한다.")
    @PostMapping("/loans/secured/collateral-documents/{documentId}/review")
    public ResponseEntity<CollateralDocumentResponse> review(
            @PathVariable Long documentId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        Long caller = callerUserId(authentication);
        requireOperator(authentication);
        CollateralDocument document = reviewUseCase.review(new ReviewCollateralDocumentCommand(
                documentId, caller, request.matched(), request.note()));
        return ResponseEntity.ok(toResponse(document));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CollateralDocumentResponse toResponse(CollateralDocument document) {
        BigDecimal senior = document.getExtracted().seniorClaimAmount();
        return new CollateralDocumentResponse(
                document.getId(),
                document.getSecuredLoanId(),
                document.getCollateralId(),
                document.getStatus().name(),
                document.getExtracted().ownerName(),
                document.getExtracted().locationText(),
                document.getExtracted().appraisedValue().toPlainString(),
                senior == null ? null : senior.toPlainString(),
                document.getExtracted().appraisalDate(),
                document.getExtracted().confidence().toPlainString(),
                document.getMatchNote(),
                document.getOcrModel(),
                document.getFileName(),
                document.getReviewedBy(),
                document.getCreatedAt());
    }

    // ── DTO ──

    /**
     * 리뷰 요청 — 리뷰어는 JWT 주체에서 파생하므로 본문에 없다.
     *
     * @param matched true=대사 확정 / false=반려
     * @param note    육안 대조 근거
     */
    public record ReviewRequest(@NotNull Boolean matched, String note) {
    }

    /** 담보서류 응답 — 금액·신뢰도는 십진 문자열, 파일 본문은 싣지 않는다. */
    public record CollateralDocumentResponse(
            Long id,
            Long securedLoanId,
            Long collateralId,
            String status,
            String ownerName,
            String locationText,
            String appraisedValue,
            String seniorClaimAmount,
            LocalDate appraisalDate,
            String confidence,
            String matchNote,
            String ocrModel,
            String fileName,
            Long reviewedBy,
            LocalDateTime createdAt
    ) {
    }

    // ─── 인가 헬퍼 (SecuredLoanController 와 동형) ────────────────────────────

    private Long requireOwnerOrOperator(Long loanId, Authentication authentication) {
        Long caller = callerUserId(authentication);
        SecuredLoan loan = loadSecuredLoanPort.findById(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
        if (isOperator(authentication) || caller.equals(loan.getBorrower().userId())) {
            return caller;
        }
        throw new AccessDeniedException("본인 소유가 아닌 대출입니다. loanId=" + loanId);
    }

    private static void requireOperator(Authentication authentication) {
        if (!isOperator(authentication)) {
            throw new AccessDeniedException("운영 권한이 필요한 작업입니다.");
        }
    }

    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }
}
