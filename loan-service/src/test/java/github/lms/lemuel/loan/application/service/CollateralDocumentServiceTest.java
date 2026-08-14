package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase.AttachCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase.ReviewCollateralDocumentCommand;
import github.lms.lemuel.loan.application.port.out.ExtractCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveCollateralDocumentPort;
import github.lms.lemuel.loan.config.CollateralOcrProperties;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentMatchDecision;
import github.lms.lemuel.loan.domain.CollateralDocumentStatus;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentNotFoundException;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentOcrUnavailableException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 담보서류 첨부·대사·리뷰 유스케이스 테스트.
 *
 * <p>고정하는 것: ① 무담보 상품에는 첨부 불가(OCR 비용 유발 전 차단) ② 같은 파일 재업로드는 OCR 을
 * 다시 부르지 않는다(멱등 + 비용 방어) ③ 판독 실패는 503 무폴백.
 */
@ExtendWith(MockitoExtension.class)
class CollateralDocumentServiceTest {

    private static final LocalDateTime APPRAISED_AT = LocalDateTime.of(2026, 8, 10, 14, 0);
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final byte[] CONTENT = "appraisal-doc-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock LoadSecuredLoanPort loadSecuredLoanPort;
    @Mock ExtractCollateralDocumentPort extractCollateralDocumentPort;
    @Mock SaveCollateralDocumentPort saveCollateralDocumentPort;
    @Mock LoadCollateralDocumentPort loadCollateralDocumentPort;

    private CollateralDocumentService service;

    @BeforeEach
    void setUp() {
        service = new CollateralDocumentService(loadSecuredLoanPort, extractCollateralDocumentPort,
                saveCollateralDocumentPort, loadCollateralDocumentPort,
                new CollateralOcrProperties("key", null, null, null, null, null), FIXED);
    }

    private static SecuredLoan mortgageLoan() {
        Collateral collateral = Collateral.reconstitute(2L, CollateralType.REAL_ESTATE,
                "서울시 강남구 역삼동 123-4", new BigDecimal("500000000.00"),
                new BigDecimal("120000000.00"), APPRAISED_AT, CollateralStatus.PLEDGED);
        return SecuredLoan.reconstitute(1L, new Borrower(BorrowerType.INDIVIDUAL, 77L, "홍길동", null),
                LoanProductType.MORTGAGE, collateral, new BigDecimal("200000000.00"), 120,
                new BigDecimal("4.3"), RepaymentMethod.EQUAL_PAYMENT, null, null,
                BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, APPRAISED_AT, null);
    }

    private static SecuredLoan personalCreditLoan() {
        return SecuredLoan.reconstitute(1L, new Borrower(BorrowerType.INDIVIDUAL, 77L, "홍길동", null),
                LoanProductType.PERSONAL_CREDIT, null, new BigDecimal("10000000.00"), 12,
                new BigDecimal("6.0"), RepaymentMethod.BULLET, 800, "B",
                BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, APPRAISED_AT, null);
    }

    private static ExtractedCollateralDocument extracted(String appraised) {
        return new ExtractedCollateralDocument("홍길동", "서울시 강남구", new BigDecimal(appraised),
                new BigDecimal("120000000"), LocalDate.of(2026, 8, 10), new BigDecimal("0.93"));
    }

    private static AttachCollateralDocumentCommand command() {
        return new AttachCollateralDocumentCommand(1L, 77L, "감정평가서.pdf", "application/pdf", CONTENT);
    }

    @Test
    @DisplayName("업로드 → OCR → 담보 설정값 대사 일치 → MATCHED 저장")
    void attachAndMatch() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(mortgageLoan()));
        when(loadCollateralDocumentPort.findByLoanIdAndFileHash(eq(1L), any()))
                .thenReturn(Optional.empty());
        when(extractCollateralDocumentPort.isConfigured()).thenReturn(true);
        when(extractCollateralDocumentPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractCollateralDocumentPort.extract(CONTENT, "application/pdf"))
                .thenReturn(extracted("500000000"));
        when(saveCollateralDocumentPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        CollateralDocument saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(CollateralDocumentStatus.MATCHED);
        assertThat(saved.getSecuredLoanId()).isEqualTo(1L);
        assertThat(saved.getCollateralId()).isEqualTo(2L);
        assertThat(saved.getOcrModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("감정평가액이 다르면 MISMATCHED 로 저장된다 — 판정 근거 보존")
    void attachMismatched() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(mortgageLoan()));
        when(loadCollateralDocumentPort.findByLoanIdAndFileHash(eq(1L), any()))
                .thenReturn(Optional.empty());
        when(extractCollateralDocumentPort.isConfigured()).thenReturn(true);
        when(extractCollateralDocumentPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractCollateralDocumentPort.extract(CONTENT, "application/pdf"))
                .thenReturn(extracted("450000000"));
        when(saveCollateralDocumentPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        CollateralDocument saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(CollateralDocumentStatus.MISMATCHED);
        assertThat(saved.getMatchNote()).contains("감정평가액");
    }

    @Test
    @DisplayName("무담보 상품(개인신용)에는 첨부 불가 — OCR 호출 없음")
    void rejectsUnsecuredProduct() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(personalCreditLoan()));

        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(LoanInvariantViolationException.class)
                .hasMessageContaining("무담보");

        verify(extractCollateralDocumentPort, never()).extract(any(), any());
    }

    @Test
    @DisplayName("같은 파일 재업로드는 기존 서류 반환 — OCR 재호출 없음 (멱등 + 비용 방어)")
    void idempotentReupload() {
        CollateralDocument existing = CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf",
                "application/pdf", CollateralDocumentService.sha256Hex(CONTENT), 4096L,
                extracted("500000000"), "gemini-2.5-flash", APPRAISED_AT);
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(mortgageLoan()));
        when(loadCollateralDocumentPort.findByLoanIdAndFileHash(1L,
                CollateralDocumentService.sha256Hex(CONTENT))).thenReturn(Optional.of(existing));

        CollateralDocument result = service.attach(command());

        assertThat(result).isSameAs(existing);
        verify(extractCollateralDocumentPort, never()).extract(any(), any());
        verify(saveCollateralDocumentPort, never()).saveNew(any(), any());
    }

    @Test
    @DisplayName("OCR 미구성이면 503 — 기본값·추정 판독 폴백 없음")
    void ocrNotConfiguredIs503() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.of(mortgageLoan()));
        when(loadCollateralDocumentPort.findByLoanIdAndFileHash(eq(1L), any()))
                .thenReturn(Optional.empty());
        when(extractCollateralDocumentPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(CollateralDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("대출 미존재는 404 동형 예외, 빈 파일은 불변식 위반")
    void notFoundAndEmptyContent() {
        when(loadSecuredLoanPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(SecuredLoanNotFoundException.class);

        assertThatThrownBy(() -> service.attach(
                new AttachCollateralDocumentCommand(1L, 77L, "f.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("리뷰 확정 — NEEDS_REVIEW 서류를 MATCHED 로 종결하고 저장한다")
    void reviewMatches() {
        CollateralDocument needsReview = CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf",
                "application/pdf", "hash", 4096L,
                new ExtractedCollateralDocument("홍길동", null, new BigDecimal("500000000"),
                        null, null, new BigDecimal("0.50")),
                "gemini-2.5-flash", APPRAISED_AT);
        needsReview.applyDecision(CollateralDocumentMatchDecision.needsReview("신뢰도 미달"), APPRAISED_AT);
        when(loadCollateralDocumentPort.findById(5L)).thenReturn(Optional.of(needsReview));
        when(saveCollateralDocumentPort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        CollateralDocument reviewed =
                service.review(new ReviewCollateralDocumentCommand(5L, 99L, true, "육안 대조 완료"));

        assertThat(reviewed.getStatus()).isEqualTo(CollateralDocumentStatus.MATCHED);
        assertThat(reviewed.getReviewedBy()).isEqualTo(99L);
    }

    @Test
    @DisplayName("리뷰 대상 미존재는 404 동형 예외")
    void reviewNotFound() {
        when(loadCollateralDocumentPort.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(new ReviewCollateralDocumentCommand(5L, 99L, true, "note")))
                .isInstanceOf(CollateralDocumentNotFoundException.class);
    }
}
