package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase.AttachDocumentCommand;
import github.lms.lemuel.insurance.application.port.in.GetApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase.ReviewDocumentCommand;
import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentTransitionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 청약서류 첨부·조회·리뷰 API (ADR 0036 확산).
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * 업로더·리뷰어 식별자는 요청이 아니라 <b>JWT 주체({@link FcIdentity})에서만</b> 파생한다.
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST /api/insurance/applications/{applicationId}/documents} — 멀티파트 업로드 → OCR → 자동 대사</li>
 *   <li>{@code GET /api/insurance/applications/{applicationId}/documents/latest} — 최신 서류(승인 게이트와 같은 기준)</li>
 *   <li>{@code POST /api/insurance/application-documents/{documentId}/review} — NEEDS_REVIEW 육안 리뷰 종결</li>
 * </ul>
 */
@RestController
public class ApplicationDocumentController {

    private final AttachApplicationDocumentUseCase attachUseCase;
    private final GetApplicationDocumentUseCase getUseCase;
    private final ReviewApplicationDocumentUseCase reviewUseCase;

    public ApplicationDocumentController(AttachApplicationDocumentUseCase attachUseCase,
                                         GetApplicationDocumentUseCase getUseCase,
                                         ReviewApplicationDocumentUseCase reviewUseCase) {
        this.attachUseCase = attachUseCase;
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
    }

    /** 청약서류 업로드 → OCR 추출 → 자동 대사. 같은 파일 재업로드는 기존 서류 반환(멱등). */
    @PostMapping("/api/insurance/applications/{applicationId}/documents")
    public ResponseEntity<ApplicationDocumentResponse> attach(
            @PathVariable String applicationId,
            @RequestParam("file") MultipartFile file) {

        ApplicationDocument document = attachUseCase.attach(new AttachDocumentCommand(
                applicationId, FcIdentity.currentFcId(), file.getOriginalFilename(),
                file.getContentType(), bytesOf(file)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(document));
    }

    /** 청약의 최신 서류 조회 — 승인 게이트가 판정하는 것과 같은 기준. */
    @GetMapping("/api/insurance/applications/{applicationId}/documents/latest")
    public ResponseEntity<ApplicationDocumentResponse> latest(@PathVariable String applicationId) {
        return getUseCase.latestForApplication(applicationId)
                .map(document -> ResponseEntity.ok(toResponse(document)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** NEEDS_REVIEW 서류 육안 리뷰 종결 (MATCHED/MISMATCHED). */
    @PostMapping("/api/insurance/application-documents/{documentId}/review")
    public ResponseEntity<ApplicationDocumentResponse> review(
            @PathVariable Long documentId,
            @Valid @RequestBody ReviewRequest request) {

        ApplicationDocument document = reviewUseCase.review(new ReviewDocumentCommand(
                documentId, FcIdentity.currentFcId(), request.matched(), request.note()));
        return ResponseEntity.ok(toResponse(document));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ApplicationDocumentResponse toResponse(ApplicationDocument document) {
        return new ApplicationDocumentResponse(
                document.getId(),
                document.getApplicationId(),
                document.getStatus().name(),
                document.getExtracted().contractorName(),
                document.getExtracted().insuredName(),
                document.getExtracted().productName(),
                document.getExtracted().applicationDate(),
                document.getExtracted().annualPremium().toPlainString(),
                document.getExtracted().coverageAmount() == null
                        ? null : document.getExtracted().coverageAmount().toPlainString(),
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

    /** 청약서류 응답 — 금액·신뢰도는 십진 문자열, 파일 본문은 싣지 않는다. */
    public record ApplicationDocumentResponse(
            Long id,
            String applicationId,
            String status,
            String contractorName,
            String insuredName,
            String productName,
            LocalDate applicationDate,
            String annualPremium,
            String coverageAmount,
            String confidence,
            String matchNote,
            String ocrModel,
            String fileName,
            String reviewedBy,
            Instant createdAt
    ) {
    }

    // ── 예외 매핑 (insurance 하우스 스타일 — 컨트롤러 로컬 핸들러) ──

    @ExceptionHandler({ApplicationNotFoundException.class, ApplicationDocumentNotFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApplicationDocumentOcrUnavailableException.class)
    public ResponseEntity<Map<String, String>> ocrUnavailable(ApplicationDocumentOcrUnavailableException e) {
        // 무폴백(ADR 0036) — 재시도 가능한 일시 장애로 안내
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidApplicationDocumentTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(InvalidApplicationDocumentTransitionException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidApplicationDocumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(InvalidApplicationDocumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
