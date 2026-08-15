package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.insurance.adapter.in.web.ApplicationDocumentController.ApplicationDocumentResponse;
import github.lms.lemuel.insurance.adapter.in.web.ApplicationDocumentController.ReviewRequest;
import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase.AttachDocumentCommand;
import github.lms.lemuel.insurance.application.port.in.GetApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase.ReviewDocumentCommand;
import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentTransitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 청약서류 API (ADR 0036).
 *
 * <p>FC 식별자를 요청이 아니라 <b>SecurityContext 의 JWT 주체</b>에서만 파생하는지(IDOR),
 * 응답이 금액·신뢰도를 십진 문자열로 주는지, 그리고 컨트롤러 로컬 예외 매핑(404/503/409/400)이
 * 살아 있는지를 고정한다. OCR 불가는 500 이 아니라 <b>503</b> 이어야 한다 — 무폴백(ADR 0036)이라
 * 재시도 가능한 일시 장애로 안내해야 하기 때문이다.
 */
class ApplicationDocumentControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
    private static final byte[] CONTENT = "pdf-bytes".getBytes(StandardCharsets.UTF_8);

    private AttachApplicationDocumentUseCase attachUseCase;
    private GetApplicationDocumentUseCase getUseCase;
    private ReviewApplicationDocumentUseCase reviewUseCase;
    private ApplicationDocumentController controller;

    @BeforeEach
    void setUp() {
        attachUseCase = mock(AttachApplicationDocumentUseCase.class);
        getUseCase = mock(GetApplicationDocumentUseCase.class);
        reviewUseCase = mock(ReviewApplicationDocumentUseCase.class);
        controller = new ApplicationDocumentController(attachUseCase, getUseCase, reviewUseCase);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, "fc@example.com", "USER"), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static ExtractedApplicationForm form() {
        return new ExtractedApplicationForm("홍길동", "홍길순", "무배당 종신보험",
                LocalDate.of(2026, 8, 10), new BigDecimal("360000"), new BigDecimal("100000000"),
                new BigDecimal("0.94"));
    }

    private static ApplicationDocument document() {
        return ApplicationDocument.extracted("APP-1", "77", "청약서.pdf", "application/pdf",
                "hash-abc", 4096L, form(), "gemini-2.5-flash", NOW);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "청약서.pdf", "application/pdf", CONTENT);
    }

    @Test
    @DisplayName("업로더(FC)는 SecurityContext 의 JWT 주체에서 파생한다 — 요청에 fcId 필드가 없다")
    void attachDerivesFcFromToken() {
        authenticateAs(77L);
        when(attachUseCase.attach(any(AttachDocumentCommand.class))).thenReturn(document());

        var response = controller.attach("APP-1", file());

        ArgumentCaptor<AttachDocumentCommand> captor = ArgumentCaptor.forClass(AttachDocumentCommand.class);
        verify(attachUseCase).attach(captor.capture());
        assertThat(captor.getValue().applicationId()).isEqualTo("APP-1");
        assertThat(captor.getValue().uploadedBy()).isEqualTo("77");
        assertThat(captor.getValue().fileName()).isEqualTo("청약서.pdf");
        assertThat(captor.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("응답은 금액·신뢰도를 십진 문자열로 주고 파일 본문은 싣지 않는다")
    void responseCarriesPlainStrings() {
        authenticateAs(77L);
        when(attachUseCase.attach(any(AttachDocumentCommand.class))).thenReturn(document());

        ApplicationDocumentResponse body = controller.attach("APP-1", file()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.annualPremium()).isEqualTo("360000");
        assertThat(body.coverageAmount()).isEqualTo("100000000");
        assertThat(body.confidence()).isEqualTo("0.94");
        assertThat(body.contractorName()).isEqualTo("홍길동");
        assertThat(body.insuredName()).isEqualTo("홍길순");
        assertThat(body.status()).isEqualTo(ApplicationDocumentStatus.EXTRACTED.name());
    }

    @Test
    @DisplayName("가입금액이 판독되지 않으면 null 로 남긴다 (0 으로 채우지 않는다)")
    void keepsNullCoverageAmount() {
        authenticateAs(77L);
        ApplicationDocument noCoverage = ApplicationDocument.extracted("APP-1", "77", "청약서.pdf",
                "application/pdf", "hash-x", 4096L,
                new ExtractedApplicationForm("홍길동", "홍길순", "무배당 종신보험",
                        LocalDate.of(2026, 8, 10), new BigDecimal("360000"), null,
                        new BigDecimal("0.94")),
                "gemini-2.5-flash", NOW);
        when(attachUseCase.attach(any(AttachDocumentCommand.class))).thenReturn(noCoverage);

        assertThat(controller.attach("APP-1", file()).getBody().coverageAmount()).isNull();
    }

    @Test
    @DisplayName("최신 서류가 있으면 200, 없으면 404")
    void latestReturnsOkOrNotFound() {
        when(getUseCase.latestForApplication("APP-1")).thenReturn(Optional.of(document()));
        assertThat(controller.latest("APP-1").getStatusCode()).isEqualTo(HttpStatus.OK);

        when(getUseCase.latestForApplication("APP-1")).thenReturn(Optional.empty());
        assertThat(controller.latest("APP-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("리뷰 큐는 상태·건수를 그대로 위임한다")
    void queueDelegates() {
        when(getUseCase.byStatus(ApplicationDocumentStatus.NEEDS_REVIEW, 50))
                .thenReturn(List.of(document()));

        assertThat(controller.queue(ApplicationDocumentStatus.NEEDS_REVIEW, 50).getBody()).hasSize(1);
        verify(getUseCase).byStatus(ApplicationDocumentStatus.NEEDS_REVIEW, 50);
    }

    @Test
    @DisplayName("리뷰어도 JWT 주체에서 파생하고 판정·사유를 그대로 넘긴다")
    void reviewDerivesReviewer() {
        authenticateAs(99L);
        when(reviewUseCase.review(any(ReviewDocumentCommand.class))).thenReturn(document());

        controller.review(3L, new ReviewRequest(false, "성명 불일치"));

        ArgumentCaptor<ReviewDocumentCommand> captor = ArgumentCaptor.forClass(ReviewDocumentCommand.class);
        verify(reviewUseCase).review(captor.capture());
        assertThat(captor.getValue().documentId()).isEqualTo(3L);
        assertThat(captor.getValue().reviewerId()).isEqualTo("99");
        assertThat(captor.getValue().matched()).isFalse();
        assertThat(captor.getValue().note()).isEqualTo("성명 불일치");
    }

    @Test
    @DisplayName("인증이 없으면 FC 식별자는 null 로 내려가고 유스케이스가 거른다")
    void unauthenticatedYieldsNullFcId() {
        when(attachUseCase.attach(any(AttachDocumentCommand.class))).thenReturn(document());

        controller.attach("APP-1", file());

        ArgumentCaptor<AttachDocumentCommand> captor = ArgumentCaptor.forClass(AttachDocumentCommand.class);
        verify(attachUseCase).attach(captor.capture());
        assertThat(captor.getValue().uploadedBy()).isNull();
    }

    @Test
    @DisplayName("없는 청약·서류는 404 로 매핑한다")
    void mapsNotFound() {
        assertThat(controller.notFound(new ApplicationDocumentNotFoundException(3L))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("OCR 불가는 500 이 아니라 503 — 무폴백이라 재시도 가능한 장애로 안내한다")
    void mapsOcrUnavailableTo503() {
        var response = controller.ocrUnavailable(
                new ApplicationDocumentOcrUnavailableException("OCR 응답 없음"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("error", "OCR 응답 없음");
    }

    @Test
    @DisplayName("허용되지 않는 전이는 409, 잘못된 입력은 400")
    void mapsTransitionAndValidation() {
        assertThat(controller.invalidTransition(
                new InvalidApplicationDocumentTransitionException("이미 종결된 서류"))
                .getStatusCode().value()).isEqualTo(409);
        assertThat(controller.badRequest(new InvalidApplicationDocumentException("파일이 비어 있습니다"))
                .getStatusCode().value()).isEqualTo(400);
    }
}
