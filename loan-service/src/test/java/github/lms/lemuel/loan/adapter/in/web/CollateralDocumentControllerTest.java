package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.CollateralDocumentController.CollateralDocumentResponse;
import github.lms.lemuel.loan.adapter.in.web.CollateralDocumentController.ReviewRequest;
import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase.AttachCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.in.GetCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase.ReviewCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentStatus;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 담보서류 API 의 인가·매핑 규칙 (ADR 0036).
 *
 * <p>업로더·리뷰어 식별자를 <b>요청이 아니라 JWT 주체</b>에서만 파생하는지, 남의 대출 서류에
 * 접근이 막히는지, 리뷰 큐가 운영자 전용인지를 고정한다. 컨트롤러를 직접 호출해 예외 종류를
 * 그대로 관찰한다(슬라이스는 예외를 상태코드로 바꿔 구분을 흐린다).
 */
class CollateralDocumentControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 9, 0);
    private static final byte[] CONTENT = "pdf-bytes".getBytes(StandardCharsets.UTF_8);

    private AttachCollateralDocumentUseCase attachUseCase;
    private GetCollateralDocumentUseCase getUseCase;
    private ReviewCollateralDocumentUseCase reviewUseCase;
    private LoadSecuredLoanPort loadSecuredLoanPort;
    private CollateralDocumentController controller;

    @BeforeEach
    void setUp() {
        attachUseCase = mock(AttachCollateralDocumentUseCase.class);
        getUseCase = mock(GetCollateralDocumentUseCase.class);
        reviewUseCase = mock(ReviewCollateralDocumentUseCase.class);
        loadSecuredLoanPort = mock(LoadSecuredLoanPort.class);
        controller = new CollateralDocumentController(attachUseCase, getUseCase, reviewUseCase,
                loadSecuredLoanPort);
    }

    private static Authentication userAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(99L, "admin@example.com", "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static SecuredLoan loanOf(long borrowerUserId) {
        return SecuredLoan.reconstitute(1L, Borrower.individual(borrowerUserId, "홍길동"),
                LoanProductType.PERSONAL_CREDIT, null, new BigDecimal("10000000.00"), 12,
                new BigDecimal("6.0"), RepaymentMethod.BULLET, 800, "B",
                BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, NOW, null);
    }

    private static ExtractedCollateralDocument extracted() {
        return new ExtractedCollateralDocument("홍길동", "서울시 강남구", new BigDecimal("500000000"),
                new BigDecimal("120000000"), LocalDate.of(2026, 8, 10), new BigDecimal("0.93"));
    }

    private static CollateralDocument document() {
        return CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf", "application/pdf",
                "hash-abc", 4096L, extracted(), "gemini-2.5-flash", NOW);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "감정평가서.pdf", "application/pdf", CONTENT);
    }

    // ── 업로드 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업로더는 JWT 주체에서 파생하고 파일 메타를 그대로 커맨드에 넣는다")
    void attachDerivesUploaderFromToken() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));
        when(attachUseCase.attach(any(AttachCollateralDocumentCommand.class))).thenReturn(document());

        ResponseEntity<CollateralDocumentResponse> response =
                controller.attach(1L, file(), userAuth(77L));

        ArgumentCaptor<AttachCollateralDocumentCommand> captor =
                ArgumentCaptor.forClass(AttachCollateralDocumentCommand.class);
        verify(attachUseCase).attach(captor.capture());
        assertThat(captor.getValue().uploaderUserId()).isEqualTo(77L);
        assertThat(captor.getValue().fileName()).isEqualTo("감정평가서.pdf");
        assertThat(captor.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(captor.getValue().content()).isEqualTo(CONTENT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("응답은 금액·신뢰도를 십진 문자열로 주고 파일 본문은 싣지 않는다")
    void responseCarriesPlainStrings() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));
        when(attachUseCase.attach(any(AttachCollateralDocumentCommand.class))).thenReturn(document());

        CollateralDocumentResponse body = controller.attach(1L, file(), userAuth(77L)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.appraisedValue()).isEqualTo("500000000");
        assertThat(body.seniorClaimAmount()).isEqualTo("120000000");
        assertThat(body.confidence()).isEqualTo("0.93");
        assertThat(body.ownerName()).isEqualTo("홍길동");
        assertThat(body.fileName()).isEqualTo("감정평가서.pdf");
    }

    @Test
    @DisplayName("선순위 채권최고액이 판독되지 않으면 null 로 남긴다 (0 으로 채우지 않는다)")
    void keepsNullSeniorClaim() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));
        CollateralDocument noSenior = CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf",
                "application/pdf", "hash-x", 4096L,
                new ExtractedCollateralDocument("홍길동", "서울시 강남구", new BigDecimal("500000000"),
                        null, LocalDate.of(2026, 8, 10), new BigDecimal("0.93")),
                "gemini-2.5-flash", NOW);
        when(attachUseCase.attach(any(AttachCollateralDocumentCommand.class))).thenReturn(noSenior);

        CollateralDocumentResponse body = controller.attach(1L, file(), userAuth(77L)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.seniorClaimAmount()).isNull();
    }

    @Test
    @DisplayName("남의 대출에는 서류를 붙일 수 없다")
    void attachRejectsOtherBorrower() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));

        assertThatThrownBy(() -> controller.attach(1L, file(), userAuth(88L)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(attachUseCase);
    }

    @Test
    @DisplayName("운영자는 타인 대출에도 서류를 붙일 수 있다")
    void attachAllowsOperator() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));
        when(attachUseCase.attach(any(AttachCollateralDocumentCommand.class))).thenReturn(document());

        assertThat(controller.attach(1L, file(), adminAuth()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("없는 대출이면 404 도메인 예외")
    void attachRejectsMissingLoan() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.attach(1L, file(), userAuth(77L)))
                .isInstanceOf(SecuredLoanNotFoundException.class);
    }

    @Test
    @DisplayName("인증 주체가 없으면 업로드를 거부한다")
    void attachRejectsAnonymous() {
        assertThatThrownBy(() -> controller.attach(1L, file(), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(loadSecuredLoanPort, attachUseCase);
    }

    // ── 최신 서류 조회 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("최신 서류가 있으면 200, 없으면 404")
    void latestReturnsOkOrNotFound() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));
        when(getUseCase.latestForLoan(1L)).thenReturn(Optional.of(document()));

        assertThat(controller.latest(1L, userAuth(77L)).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(getUseCase.latestForLoan(1L)).thenReturn(Optional.empty());
        assertThat(controller.latest(1L, userAuth(77L)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("최신 서류 조회도 소유권 대조를 거친다")
    void latestChecksOwnership() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(loanOf(77L)));

        assertThatThrownBy(() -> controller.latest(1L, userAuth(88L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── 리뷰 큐·종결 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("리뷰 큐는 운영자 전용이며 상태·건수를 그대로 위임한다")
    void queueIsOperatorOnly() {
        when(getUseCase.byStatus(CollateralDocumentStatus.NEEDS_REVIEW, 50))
                .thenReturn(List.of(document()));

        assertThat(controller.queue(CollateralDocumentStatus.NEEDS_REVIEW, 50, adminAuth()).getBody())
                .hasSize(1);
        assertThatThrownBy(() ->
                controller.queue(CollateralDocumentStatus.NEEDS_REVIEW, 50, userAuth(77L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("리뷰 종결은 운영자 전용이고 리뷰어는 JWT 주체에서 파생한다")
    void reviewIsOperatorOnlyAndDerivesReviewer() {
        when(reviewUseCase.review(any(ReviewCollateralDocumentCommand.class))).thenReturn(document());

        controller.review(3L, new ReviewRequest(true, "등기부 대조 완료"), adminAuth());

        ArgumentCaptor<ReviewCollateralDocumentCommand> captor =
                ArgumentCaptor.forClass(ReviewCollateralDocumentCommand.class);
        verify(reviewUseCase).review(captor.capture());
        assertThat(captor.getValue().documentId()).isEqualTo(3L);
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);
        assertThat(captor.getValue().matched()).isTrue();
        assertThat(captor.getValue().note()).isEqualTo("등기부 대조 완료");
    }

    @Test
    @DisplayName("차주는 리뷰를 종결할 수 없다")
    void reviewRejectsBorrower() {
        assertThatThrownBy(() ->
                controller.review(3L, new ReviewRequest(false, "반려"), userAuth(77L)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(reviewUseCase);
    }
}
