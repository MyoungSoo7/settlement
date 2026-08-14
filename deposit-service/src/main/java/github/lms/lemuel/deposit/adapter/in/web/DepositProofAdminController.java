package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase.AttachProofCommand;
import github.lms.lemuel.deposit.application.port.in.GetDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase.ReviewProofCommand;
import github.lms.lemuel.deposit.domain.DepositProof;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 예치금 증빙 첨부·조회·리뷰 운영자 콘솔 (ADR 0036 확산).
 *
 * <p><b>인가</b>: {@code /admin/deposits/**} 전체가 shared-common SecurityConfig 에서 ADMIN 전용.
 * 업로더·리뷰어 식별자는 요청이 아니라 JWT 주체({@link AuthPrincipal})에서만 파생한다 — 기존
 * {@code DepositAdminController} 는 조작자 식별자를 남기지 않지만, 증빙은 감사 추적이 목적이라
 * 기록을 강제한다.
 *
 * <h3>운영 흐름</h3>
 * <ol>
 *   <li>referenceId 를 정하고 증빙을 먼저 첨부 — {@code POST .../proofs}</li>
 *   <li>같은 referenceId 로 수기 기표 — {@code POST /admin/deposits/accounts/{sellerId}/credits|debits}
 *       (기표 시점에 금액·이체일이 증빙과 지연 대사되고, 미통과면 422 로 기표가 거부된다)</li>
 *   <li>NEEDS_REVIEW(신뢰도·이체일 판독 불가)는 육안 대조 후 {@code .../review} 로 종결</li>
 * </ol>
 */
@Tag(name = "Deposit Proof", description = "예치금 수기 기표 증빙 OCR 대사 (ADR 0036, 운영자)")
@RestController
@RequestMapping("/admin/deposits")
public class DepositProofAdminController {

    private final AttachDepositProofUseCase attachUseCase;
    private final GetDepositProofUseCase getUseCase;
    private final ReviewDepositProofUseCase reviewUseCase;

    public DepositProofAdminController(AttachDepositProofUseCase attachUseCase,
                                       GetDepositProofUseCase getUseCase,
                                       ReviewDepositProofUseCase reviewUseCase) {
        this.attachUseCase = attachUseCase;
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
    }

    @Operation(summary = "증빙 업로드 → OCR 추출 (기표 전 선행)",
            description = "앵커는 수기 기표에 쓸 (referenceType, referenceId). 같은 파일 재업로드는 "
                    + "기존 증빙 반환(멱등, OCR 재호출 없음). 값 대사는 기표 시점에 실행된다(지연 대사).")
    @PostMapping("/accounts/{sellerId}/proofs")
    public ResponseEntity<DepositProofResponse> attach(
            @PathVariable Long sellerId,
            @RequestParam("referenceType") String referenceType,
            @RequestParam("referenceId") String referenceId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long uploadedBy = callerUserId(authentication);
        DepositProof proof = attachUseCase.attach(new AttachProofCommand(
                sellerId, referenceType, referenceId, uploadedBy,
                file.getOriginalFilename(), file.getContentType(), bytesOf(file)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(proof));
    }

    @Operation(summary = "참조의 최신 증빙 조회 — 기표 게이트가 판정하는 것과 같은 기준")
    @GetMapping("/accounts/{sellerId}/proofs/latest")
    public ResponseEntity<DepositProofResponse> latest(
            @PathVariable Long sellerId,
            @RequestParam("referenceType") String referenceType,
            @RequestParam("referenceId") String referenceId) {
        return getUseCase.latestForReference(sellerId, referenceType, referenceId)
                .map(proof -> ResponseEntity.ok(toResponse(proof)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "NEEDS_REVIEW 증빙 육안 리뷰 종결 (MATCHED/MISMATCHED)")
    @PostMapping("/proofs/{proofId}/review")
    public ResponseEntity<DepositProofResponse> review(
            @PathVariable Long proofId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        Long reviewerId = callerUserId(authentication);
        DepositProof proof = reviewUseCase.review(new ReviewProofCommand(
                proofId, reviewerId, request.matched(), request.note()));
        return ResponseEntity.ok(toResponse(proof));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private static DepositProofResponse toResponse(DepositProof proof) {
        return new DepositProofResponse(
                proof.getId(),
                proof.getSellerId(),
                proof.getReferenceType(),
                proof.getReferenceId(),
                proof.getStatus().name(),
                proof.getExtracted().senderName(),
                proof.getExtracted().transferDate(),
                proof.getExtracted().transferAmount().toPlainString(),
                proof.getExtracted().confidence().toPlainString(),
                proof.getMatchNote(),
                proof.getOcrModel(),
                proof.getFileName(),
                proof.getReviewedBy(),
                proof.getCreatedAt());
    }

    // ── DTO ──

    /**
     * 리뷰 요청 — 리뷰어는 JWT 주체에서 파생하므로 본문에 없다.
     *
     * @param matched true=대사 확정 / false=반려
     * @param note    육안 대조 근거
     */
    public record ReviewRequest(@NotNull Boolean matched, @NotBlank String note) {
    }

    /** 증빙 응답 — 금액·신뢰도는 십진 문자열, 파일 본문은 싣지 않는다. */
    public record DepositProofResponse(
            Long id,
            Long sellerId,
            String referenceType,
            String referenceId,
            String status,
            String senderName,
            LocalDate transferDate,
            String transferAmount,
            String confidence,
            String matchNote,
            String ocrModel,
            String fileName,
            Long reviewedBy,
            LocalDateTime createdAt
    ) {
    }
}
