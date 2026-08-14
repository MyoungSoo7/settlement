package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.ExpenseReceiptAdapter.ExpenseReceiptResponse;
import github.lms.lemuel.card.adapter.in.web.ExpenseReceiptAdminController.ReviewRequest;
import github.lms.lemuel.card.application.port.in.GetExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase.ReviewReceiptCommand;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.card.domain.ReceiptMatchDecision;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영수증 리뷰 큐 콘솔 — card 최초의 admin 표면(ADR 0036).
 *
 * <p>고정하는 것: ① 내부망 표면과 달리 리뷰어를 본문이 아니라 <b>JWT 주체에서 파생</b>한다
 * ② 목록은 상태·limit 을 유스케이스에 그대로 전달하고 금액·신뢰도를 십진 문자열로 내보낸다.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseReceiptAdminControllerTest {

    @Mock GetExpenseReceiptUseCase getUseCase;
    @Mock ReviewExpenseReceiptUseCase reviewUseCase;

    private ExpenseReceiptAdminController controller() {
        return new ExpenseReceiptAdminController(getUseCase, reviewUseCase);
    }

    private static Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(99L, "admin@lemuel.io", "ADMIN"), null, List.of());
    }

    private static ExpenseReceipt receiptIn(ExpenseReceiptStatus status) {
        ExpenseReceipt receipt = ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "receipt.jpg", "image/jpeg", "hash", 1024L,
                new ExtractedReceipt("김밥천국", LocalDate.of(2026, 8, 10),
                        new BigDecimal("12000"), new BigDecimal("0.50")),
                "gemini-2.5-flash", Instant.parse("2026-08-14T01:00:00Z"));
        if (status == ExpenseReceiptStatus.NEEDS_REVIEW) {
            receipt.applyDecision(ReceiptMatchDecision.needsReview("신뢰도 미달"),
                    Instant.parse("2026-08-14T01:00:00Z"));
        }
        return receipt;
    }

    @Test
    @DisplayName("리뷰 큐 목록 — 상태·limit 전달 + 금액·신뢰도 십진 문자열 응답")
    void queue() {
        when(getUseCase.byStatus(ExpenseReceiptStatus.NEEDS_REVIEW, 50))
                .thenReturn(List.of(receiptIn(ExpenseReceiptStatus.NEEDS_REVIEW)));

        ResponseEntity<List<ExpenseReceiptResponse>> response =
                controller().queue(ExpenseReceiptStatus.NEEDS_REVIEW, 50);

        assertThat(response.getBody()).hasSize(1);
        ExpenseReceiptResponse view = response.getBody().get(0);
        assertThat(view.reportId()).isEqualTo("RPT-1");
        assertThat(view.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(view.totalAmount()).isEqualTo("12000");
        assertThat(view.confidence()).isEqualTo("0.50");
        assertThat(view.matchNote()).isEqualTo("신뢰도 미달");
    }

    @Test
    @DisplayName("리뷰 종결 — 리뷰어는 본문이 아니라 JWT 주체에서 파생된다")
    void reviewDerivesReviewerFromJwt() {
        when(reviewUseCase.review(any())).thenAnswer(inv -> {
            ExpenseReceipt receipt = receiptIn(ExpenseReceiptStatus.NEEDS_REVIEW);
            receipt.reviewMatch(99L, "육안 대조", Instant.parse("2026-08-14T02:00:00Z"));
            return receipt;
        });

        ResponseEntity<ExpenseReceiptResponse> response = controller().review(
                5L, new ReviewRequest(true, "육안 대조"), adminAuth());

        ArgumentCaptor<ReviewReceiptCommand> captor = ArgumentCaptor.forClass(ReviewReceiptCommand.class);
        verify(reviewUseCase).review(captor.capture());
        assertThat(captor.getValue().receiptId()).isEqualTo(5L);
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);   // JWT 주체
        assertThat(captor.getValue().matched()).isTrue();
        assertThat(response.getBody().status()).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("인증 주체에서 userId 를 못 뽑으면 403 결 — 리뷰어 없는 리뷰는 없다")
    void rejectsUnidentifiedReviewer() {
        assertThatThrownBy(() -> controller().review(5L, new ReviewRequest(true, "note"),
                new UsernamePasswordAuthenticationToken("anonymous", null, List.of())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
