package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.deposit.adapter.in.web.DepositProofAdminController.DepositProofResponse;
import github.lms.lemuel.deposit.adapter.in.web.DepositProofAdminController.ReviewRequest;
import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase.AttachProofCommand;
import github.lms.lemuel.deposit.application.port.in.GetDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase.ReviewProofCommand;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 예치금 증빙 운영자 콘솔 (ADR 0036).
 *
 * <p>이 표면의 목적은 <b>감사 추적</b>이다. 그래서 업로더·리뷰어를 요청 바디가 아니라 JWT 주체에서만
 * 파생하고, 주체가 없으면 아예 진행하지 않는다("누가 올렸는지 모르는 증빙"을 만들지 않기 위해서다).
 */
class DepositProofAdminControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);
    private static final byte[] CONTENT = "png-bytes".getBytes(StandardCharsets.UTF_8);

    private AttachDepositProofUseCase attachUseCase;
    private GetDepositProofUseCase getUseCase;
    private ReviewDepositProofUseCase reviewUseCase;
    private DepositProofAdminController controller;

    @BeforeEach
    void setUp() {
        attachUseCase = mock(AttachDepositProofUseCase.class);
        getUseCase = mock(GetDepositProofUseCase.class);
        reviewUseCase = mock(ReviewDepositProofUseCase.class);
        controller = new DepositProofAdminController(attachUseCase, getUseCase, reviewUseCase);
    }

    private static Authentication adminAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "admin@example.com", "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static DepositProof proof() {
        return DepositProof.extracted(7L, "MANUAL_TOPUP", "TOPUP-2026-0814-001", 99L,
                "이체확인증.png", "image/png", "hash-abc", 2048L,
                new ExtractedTransferProof("홍길동", LocalDate.of(2026, 8, 12),
                        new BigDecimal("3000000"), new BigDecimal("0.93")),
                "gemini-2.5-flash", NOW);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "이체확인증.png", "image/png", CONTENT);
    }

    @Test
    @DisplayName("업로더는 JWT 주체에서 파생하고 앵커(referenceType·Id)를 그대로 전달한다")
    void attachDerivesUploaderAndAnchor() {
        when(attachUseCase.attach(any(AttachProofCommand.class))).thenReturn(proof());

        var response = controller.attach(7L, "MANUAL_TOPUP", "TOPUP-2026-0814-001",
                file(), adminAuth(99L));

        ArgumentCaptor<AttachProofCommand> captor = ArgumentCaptor.forClass(AttachProofCommand.class);
        verify(attachUseCase).attach(captor.capture());
        assertThat(captor.getValue().sellerId()).isEqualTo(7L);
        assertThat(captor.getValue().referenceType()).isEqualTo("MANUAL_TOPUP");
        assertThat(captor.getValue().referenceId()).isEqualTo("TOPUP-2026-0814-001");
        assertThat(captor.getValue().uploadedBy()).isEqualTo(99L);
        assertThat(captor.getValue().fileName()).isEqualTo("이체확인증.png");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
        assertThat(captor.getValue().content()).isEqualTo(CONTENT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("응답은 금액·신뢰도를 십진 문자열로 주고 파일 본문은 싣지 않는다")
    void responseCarriesPlainStrings() {
        when(attachUseCase.attach(any(AttachProofCommand.class))).thenReturn(proof());

        DepositProofResponse body = controller
                .attach(7L, "MANUAL_TOPUP", "TOPUP-1", file(), adminAuth(99L)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.transferAmount()).isEqualTo("3000000");
        assertThat(body.confidence()).isEqualTo("0.93");
        assertThat(body.senderName()).isEqualTo("홍길동");
        assertThat(body.transferDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(body.status()).isEqualTo(DepositProofStatus.EXTRACTED.name());
        assertThat(body.fileName()).isEqualTo("이체확인증.png");
    }

    @Test
    @DisplayName("인증 주체가 없으면 업로드를 진행하지 않는다 — 누가 올렸는지 모르는 증빙을 만들지 않는다")
    void attachRejectsAnonymous() {
        assertThatThrownBy(() ->
                controller.attach(7L, "MANUAL_TOPUP", "TOPUP-1", file(), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(attachUseCase);
    }

    @Test
    @DisplayName("최신 증빙이 있으면 200, 없으면 404")
    void latestReturnsOkOrNotFound() {
        when(getUseCase.latestForReference(7L, "MANUAL_TOPUP", "TOPUP-1"))
                .thenReturn(Optional.of(proof()));

        assertThat(controller.latest(7L, "MANUAL_TOPUP", "TOPUP-1").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        when(getUseCase.latestForReference(7L, "MANUAL_TOPUP", "TOPUP-1")).thenReturn(Optional.empty());
        assertThat(controller.latest(7L, "MANUAL_TOPUP", "TOPUP-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("리뷰 큐는 상태·건수를 그대로 위임한다")
    void queueDelegates() {
        when(getUseCase.byStatus(DepositProofStatus.NEEDS_REVIEW, 50)).thenReturn(List.of(proof()));

        assertThat(controller.queue(DepositProofStatus.NEEDS_REVIEW, 50).getBody()).hasSize(1);
        verify(getUseCase).byStatus(DepositProofStatus.NEEDS_REVIEW, 50);
    }

    @Test
    @DisplayName("리뷰어도 JWT 주체에서 파생하고 판정·사유를 그대로 넘긴다")
    void reviewDerivesReviewer() {
        when(reviewUseCase.review(any(ReviewProofCommand.class))).thenReturn(proof());

        controller.review(3L, new ReviewRequest(false, "이체금액 불일치"), adminAuth(99L));

        ArgumentCaptor<ReviewProofCommand> captor = ArgumentCaptor.forClass(ReviewProofCommand.class);
        verify(reviewUseCase).review(captor.capture());
        assertThat(captor.getValue().proofId()).isEqualTo(3L);
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);
        assertThat(captor.getValue().matched()).isFalse();
        assertThat(captor.getValue().note()).isEqualTo("이체금액 불일치");
    }

    @Test
    @DisplayName("인증 주체 없는 리뷰 종결은 거부한다")
    void reviewRejectsAnonymous() {
        assertThatThrownBy(() -> controller.review(3L, new ReviewRequest(true, "확인"), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(reviewUseCase);
    }
}
