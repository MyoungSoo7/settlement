package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase.AttachDocumentCommand;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase.ReviewDocumentCommand;
import github.lms.lemuel.insurance.application.port.out.ExtractApplicationFormPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationDocumentPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationSubmissionPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationDocumentPort;
import github.lms.lemuel.insurance.config.ApplicationOcrProperties;
import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import github.lms.lemuel.insurance.domain.ApplicationStatus;
import github.lms.lemuel.insurance.domain.DocumentMatchDecision;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
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
 * 청약서류 첨부·대사·리뷰 유스케이스 테스트.
 *
 * <p>고정하는 것: ① 종결 청약에는 첨부 불가 ② 같은 파일 재업로드는 OCR 을 다시 부르지 않는다
 * (멱등 + 비용 방어) ③ 판독 실패는 503 무폴백 ④ 대사 판정이 접수일(KST) 기준으로 저장된다.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationDocumentServiceTest {

    private static final String APP_ID = "11111111-1111-1111-1111-111111111111";
    /** 접수 시각: 2026-08-10 14:00 KST */
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-10T05:00:00Z");
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final byte[] CONTENT = "application-form-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock LoadApplicationPort loadApplicationPort;
    @Mock LoadApplicationSubmissionPort loadApplicationSubmissionPort;
    @Mock ExtractApplicationFormPort extractApplicationFormPort;
    @Mock SaveApplicationDocumentPort saveApplicationDocumentPort;
    @Mock LoadApplicationDocumentPort loadApplicationDocumentPort;

    private ApplicationDocumentService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationDocumentService(loadApplicationPort, loadApplicationSubmissionPort,
                extractApplicationFormPort, saveApplicationDocumentPort, loadApplicationDocumentPort,
                new ApplicationOcrProperties("key", null, null, null, null), FIXED);
    }

    private static InsuranceApplication application(ApplicationStatus status) {
        return InsuranceApplication.builder()
                .id(1L)
                .applicationId(APP_ID)
                .productCode("PROD-1")
                .fcId("77")
                .insuredName("김피보")
                .contractorName("홍길동")
                .desiredCoverage(new BigDecimal("100000000"))
                .desiredPremium(new BigDecimal("1200000"))
                .status(status)
                .salesChannel(SalesChannel.FC)
                .build();
    }

    private static ExtractedApplicationForm form(String premium) {
        return new ExtractedApplicationForm("홍길동", "김피보", "레무엘 종신보험",
                LocalDate.of(2026, 8, 10), new BigDecimal(premium),
                new BigDecimal("100000000"), new BigDecimal("0.93"));
    }

    private static AttachDocumentCommand command() {
        return new AttachDocumentCommand(APP_ID, "77", "청약서.jpg", "image/jpeg", CONTENT);
    }

    @Test
    @DisplayName("업로드 → OCR → 청약 대사 일치 → MATCHED 저장")
    void attachAndMatch() {
        when(loadApplicationPort.findByApplicationId(APP_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.UNDER_REVIEW)));
        when(loadApplicationDocumentPort.findByApplicationIdAndFileHash(eq(APP_ID), any()))
                .thenReturn(Optional.empty());
        when(extractApplicationFormPort.isConfigured()).thenReturn(true);
        when(extractApplicationFormPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractApplicationFormPort.extract(CONTENT, "image/jpeg")).thenReturn(form("1200000"));
        when(loadApplicationSubmissionPort.findSubmittedAt(APP_ID)).thenReturn(Optional.of(SUBMITTED_AT));
        when(saveApplicationDocumentPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplicationDocument saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(ApplicationDocumentStatus.MATCHED);
        assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
        assertThat(saved.getOcrModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("연 보험료가 다르면 MISMATCHED 로 저장된다 — 판정 근거 보존")
    void attachMismatched() {
        when(loadApplicationPort.findByApplicationId(APP_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.UNDER_REVIEW)));
        when(loadApplicationDocumentPort.findByApplicationIdAndFileHash(eq(APP_ID), any()))
                .thenReturn(Optional.empty());
        when(extractApplicationFormPort.isConfigured()).thenReturn(true);
        when(extractApplicationFormPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractApplicationFormPort.extract(CONTENT, "image/jpeg")).thenReturn(form("1500000"));
        when(loadApplicationSubmissionPort.findSubmittedAt(APP_ID)).thenReturn(Optional.of(SUBMITTED_AT));
        when(saveApplicationDocumentPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplicationDocument saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(saved.getMatchNote()).contains("보험료");
    }

    @Test
    @DisplayName("종결(APPROVED/REJECTED) 청약에는 첨부 불가 — OCR 호출 없음")
    void rejectsTerminalApplication() {
        InsuranceApplication approved = application(ApplicationStatus.UNDER_REVIEW);
        approved.approve();
        when(loadApplicationPort.findByApplicationId(APP_ID)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(InvalidApplicationDocumentException.class)
                .hasMessageContaining("종결");

        verify(extractApplicationFormPort, never()).extract(any(), any());
    }

    @Test
    @DisplayName("같은 파일 재업로드는 기존 서류 반환 — OCR 재호출 없음 (멱등 + 비용 방어)")
    void idempotentReupload() {
        ApplicationDocument existing = ApplicationDocument.extracted(APP_ID, "77", "청약서.jpg",
                "image/jpeg", ApplicationDocumentService.sha256Hex(CONTENT), 1024L,
                form("1200000"), "gemini-2.5-flash", SUBMITTED_AT);
        when(loadApplicationPort.findByApplicationId(APP_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.UNDER_REVIEW)));
        when(loadApplicationDocumentPort.findByApplicationIdAndFileHash(APP_ID,
                ApplicationDocumentService.sha256Hex(CONTENT))).thenReturn(Optional.of(existing));

        ApplicationDocument result = service.attach(command());

        assertThat(result).isSameAs(existing);
        verify(extractApplicationFormPort, never()).extract(any(), any());
        verify(saveApplicationDocumentPort, never()).saveNew(any(), any());
    }

    @Test
    @DisplayName("OCR 미구성이면 503 — 기본값·추정 판독 폴백 없음")
    void ocrNotConfiguredIs503() {
        when(loadApplicationPort.findByApplicationId(APP_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.UNDER_REVIEW)));
        when(loadApplicationDocumentPort.findByApplicationIdAndFileHash(eq(APP_ID), any()))
                .thenReturn(Optional.empty());
        when(extractApplicationFormPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(ApplicationDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("청약 미존재는 404 동형 예외, 빈 파일은 400 동형 예외")
    void notFoundAndEmptyContent() {
        when(loadApplicationPort.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(ApplicationNotFoundException.class);

        assertThatThrownBy(() -> service.attach(
                new AttachDocumentCommand(APP_ID, "77", "f.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(InvalidApplicationDocumentException.class);
    }

    @Test
    @DisplayName("리뷰 확정 — NEEDS_REVIEW 서류를 MATCHED 로 종결하고 저장한다")
    void reviewMatches() {
        ApplicationDocument needsReview = ApplicationDocument.extracted(APP_ID, "77", "청약서.jpg",
                "image/jpeg", "hash", 1024L,
                new ExtractedApplicationForm("홍길동", null, null, null,
                        new BigDecimal("1200000"), null, new BigDecimal("0.50")),
                "gemini-2.5-flash", SUBMITTED_AT);
        needsReview.applyDecision(DocumentMatchDecision.needsReview("신뢰도 미달"), SUBMITTED_AT);
        when(loadApplicationDocumentPort.findById(5L)).thenReturn(Optional.of(needsReview));
        when(saveApplicationDocumentPort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationDocument reviewed =
                service.review(new ReviewDocumentCommand(5L, "99", true, "육안 대조 완료"));

        assertThat(reviewed.getStatus()).isEqualTo(ApplicationDocumentStatus.MATCHED);
        assertThat(reviewed.getReviewedBy()).isEqualTo("99");
    }

    @Test
    @DisplayName("리뷰 대상 미존재는 404 동형 예외")
    void reviewNotFound() {
        when(loadApplicationDocumentPort.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(new ReviewDocumentCommand(5L, "99", true, "note")))
                .isInstanceOf(ApplicationDocumentNotFoundException.class);
    }
}
