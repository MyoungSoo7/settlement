package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase.AttachReceiptCommand;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase.ReviewReceiptCommand;
import github.lms.lemuel.card.application.port.out.ExtractReceiptFieldsPort;
import github.lms.lemuel.card.application.port.out.LoadCardCapturePort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReceiptPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReportPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReceiptPort;
import github.lms.lemuel.card.config.ReceiptOcrProperties;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.card.domain.ReceiptMatchDecision;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영수증 첨부·대사·리뷰 유스케이스 테스트.
 *
 * <p>고정하는 것: ① 소유권 대조가 OCR 호출보다 먼저다(남의 보고서로 비용 유발 불가)
 * ② 같은 파일 재업로드는 OCR 을 다시 부르지 않는다(멱등 + 비용 방어) ③ 판독 실패는 503 무폴백.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseReceiptServiceTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-10T03:00:00Z");
    private static final byte[] CONTENT = "receipt-image-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock LoadExpenseReportPort loadExpenseReportPort;
    @Mock LoadCardCapturePort loadCardCapturePort;
    @Mock ExtractReceiptFieldsPort extractReceiptFieldsPort;
    @Mock SaveExpenseReceiptPort saveExpenseReceiptPort;
    @Mock LoadExpenseReceiptPort loadExpenseReceiptPort;

    private ExpenseReceiptService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseReceiptService(loadExpenseReportPort, loadCardCapturePort,
                extractReceiptFieldsPort, saveExpenseReceiptPort, loadExpenseReceiptPort,
                new ReceiptOcrProperties("key", null, null, null, null));
    }

    private static ExpenseReport report() {
        return ExpenseReport.createFromCapture("RPT-1", "CAP-1", "AUTH-1",
                1L, 2L, 10L, "D1", 77L, new BigDecimal("12000"), "김밥천국", CAPTURED_AT);
    }

    private static CardCapture capture() {
        return CardCapture.create("CAP-1", "AUTH-1", 1L, 2L, 77L,
                new BigDecimal("12000"), "김밥천국", CAPTURED_AT);
    }

    private static AttachReceiptCommand command() {
        return new AttachReceiptCommand("RPT-1", 77L, "receipt.jpg", "image/jpeg", CONTENT);
    }

    @Test
    @DisplayName("업로드 → OCR → 매입 대사 일치 → MATCHED 저장")
    void attachAndMatch() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));
        when(loadExpenseReceiptPort.findByReportIdAndFileHash(eq("RPT-1"), any()))
                .thenReturn(Optional.empty());
        when(extractReceiptFieldsPort.isConfigured()).thenReturn(true);
        when(extractReceiptFieldsPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractReceiptFieldsPort.extract(CONTENT, "image/jpeg")).thenReturn(new ExtractedReceipt(
                "김밥천국", LocalDate.of(2026, 8, 10), new BigDecimal("12000"), new BigDecimal("0.93")));
        when(loadCardCapturePort.findByCaptureId("CAP-1")).thenReturn(Optional.of(capture()));
        when(saveExpenseReceiptPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        ExpenseReceipt saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(saved.getReportId()).isEqualTo("RPT-1");
        assertThat(saved.getCaptureId()).isEqualTo("CAP-1");
        assertThat(saved.getOcrModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("총액이 다르면 MISMATCHED 로 저장된다 — 판정 근거 보존")
    void attachMismatched() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));
        when(loadExpenseReceiptPort.findByReportIdAndFileHash(eq("RPT-1"), any()))
                .thenReturn(Optional.empty());
        when(extractReceiptFieldsPort.isConfigured()).thenReturn(true);
        when(extractReceiptFieldsPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractReceiptFieldsPort.extract(CONTENT, "image/jpeg")).thenReturn(new ExtractedReceipt(
                "김밥천국", LocalDate.of(2026, 8, 10), new BigDecimal("15000"), new BigDecimal("0.93")));
        when(loadCardCapturePort.findByCaptureId("CAP-1")).thenReturn(Optional.of(capture()));
        when(saveExpenseReceiptPort.saveNew(any(), eq(CONTENT)))
                .thenAnswer(inv -> inv.getArgument(0));

        ExpenseReceipt saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(ExpenseReceiptStatus.MISMATCHED);
        assertThat(saved.getMatchNote()).contains("총액");
    }

    @Test
    @DisplayName("업로더 ≠ 보고서 소지자면 403 — OCR 은 호출조차 되지 않는다")
    void forbiddenBeforeOcr() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));

        assertThatThrownBy(() -> service.attach(
                new AttachReceiptCommand("RPT-1", 999L, "r.jpg", "image/jpeg", CONTENT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_FORBIDDEN));

        verify(extractReceiptFieldsPort, never()).extract(any(), any());
    }

    @Test
    @DisplayName("같은 파일 재업로드는 기존 영수증 반환 — OCR 재호출 없음 (멱등 + 비용 방어)")
    void idempotentReupload() {
        ExpenseReceipt existing = ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "receipt.jpg", "image/jpeg", ExpenseReceiptService.sha256Hex(CONTENT), 1024L,
                new ExtractedReceipt("김밥천국", LocalDate.of(2026, 8, 10),
                        new BigDecimal("12000"), new BigDecimal("0.93")),
                "gemini-2.5-flash", CAPTURED_AT);
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));
        when(loadExpenseReceiptPort.findByReportIdAndFileHash("RPT-1",
                ExpenseReceiptService.sha256Hex(CONTENT))).thenReturn(Optional.of(existing));

        ExpenseReceipt result = service.attach(command());

        assertThat(result).isSameAs(existing);
        verify(extractReceiptFieldsPort, never()).extract(any(), any());
        verify(saveExpenseReceiptPort, never()).saveNew(any(), any());
    }

    @Test
    @DisplayName("OCR 미구성이면 503 — 기본값·추정 판독 폴백 없음")
    void ocrNotConfiguredIs503() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));
        when(loadExpenseReceiptPort.findByReportIdAndFileHash(eq("RPT-1"), any()))
                .thenReturn(Optional.empty());
        when(extractReceiptFieldsPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE));
    }

    @Test
    @DisplayName("보고서·매입 미존재는 404 결")
    void notFoundPaths() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_NOT_FOUND));

        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(report()));
        when(loadExpenseReceiptPort.findByReportIdAndFileHash(eq("RPT-1"), any()))
                .thenReturn(Optional.empty());
        when(extractReceiptFieldsPort.isConfigured()).thenReturn(true);
        when(extractReceiptFieldsPort.extract(CONTENT, "image/jpeg")).thenReturn(new ExtractedReceipt(
                "김밥천국", LocalDate.of(2026, 8, 10), new BigDecimal("12000"), new BigDecimal("0.93")));
        when(loadCardCapturePort.findByCaptureId("CAP-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("매입");
    }

    @Test
    @DisplayName("빈 파일은 거부")
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> service.attach(
                new AttachReceiptCommand("RPT-1", 77L, "r.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("리뷰 확정 — NEEDS_REVIEW 영수증을 MATCHED 로 종결하고 저장한다")
    void reviewMatches() {
        ExpenseReceipt needsReview = ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "receipt.jpg", "image/jpeg", "hash", 1024L,
                new ExtractedReceipt("김밥천국", null, new BigDecimal("12000"), new BigDecimal("0.50")),
                "gemini-2.5-flash", CAPTURED_AT);
        needsReview.applyDecision(ReceiptMatchDecision.needsReview("거래일 판독 불가"), CAPTURED_AT);
        when(loadExpenseReceiptPort.findById(5L)).thenReturn(Optional.of(needsReview));
        when(saveExpenseReceiptPort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReceipt reviewed = service.review(new ReviewReceiptCommand(5L, 99L, true, "육안 대조 완료"));

        assertThat(reviewed.getStatus()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(reviewed.getReviewedBy()).isEqualTo(99L);
        ArgumentCaptor<ExpenseReceipt> captor = ArgumentCaptor.forClass(ExpenseReceipt.class);
        verify(saveExpenseReceiptPort).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExpenseReceiptStatus.MATCHED);
    }

    @Test
    @DisplayName("리뷰 대상 미존재는 404")
    void reviewNotFound() {
        when(loadExpenseReceiptPort.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(new ReviewReceiptCommand(5L, 99L, true, "note")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_RECEIPT_NOT_FOUND));
    }
}
