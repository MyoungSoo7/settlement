package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase.ApproveExpenseReportCommand;
import github.lms.lemuel.card.application.port.out.LoadDepartmentBudgetPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReceiptPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReportPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReportPort;
import github.lms.lemuel.card.application.port.out.UpdateDepartmentBudgetPort;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.card.domain.ExpenseReportStatus;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.card.domain.ReceiptMatchDecision;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인 게이트(ADR 0036) — 영수증이 첨부돼 있으면 최신 영수증이 MATCHED 여야 승인이 통과한다.
 *
 * <p>고정하는 것: ① 대사 미통과(MISMATCHED/NEEDS_REVIEW)는 422 로 거절되고 보고서 상태가 움직이지
 * 않는다 ② 영수증이 없으면 기존 경로 그대로 통과(점진 도입) ③ 게이트는 상태 전이보다 먼저다.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseWorkflowReceiptGateTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-10T03:00:00Z");

    @Mock LoadExpenseReportPort loadExpenseReportPort;
    @Mock SaveExpenseReportPort saveExpenseReportPort;
    @Mock LoadDepartmentBudgetPort loadDepartmentBudgetPort;
    @Mock UpdateDepartmentBudgetPort updateDepartmentBudgetPort;
    @Mock LoadExpenseReceiptPort loadExpenseReceiptPort;

    private ExpenseWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseWorkflowService(loadExpenseReportPort, saveExpenseReportPort,
                loadDepartmentBudgetPort, updateDepartmentBudgetPort, loadExpenseReceiptPort);
    }

    private static ExpenseReport submittedReport() {
        ExpenseReport report = ExpenseReport.createFromCapture("RPT-1", "CAP-1", "AUTH-1",
                1L, 2L, 10L, null, 77L, new BigDecimal("12000"), "김밥천국", CAPTURED_AT);
        report.submit(null, "MEAL", null);
        return report;
    }

    private static ExpenseReceipt receiptIn(ExpenseReceiptStatus status) {
        ExpenseReceipt receipt = ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "receipt.jpg", "image/jpeg", "hash", 1024L,
                new ExtractedReceipt("김밥천국", LocalDate.of(2026, 8, 10),
                        new BigDecimal("12000"), new BigDecimal("0.93")),
                "gemini-2.5-flash", CAPTURED_AT);
        switch (status) {
            case MATCHED -> receipt.applyDecision(ReceiptMatchDecision.matched(), CAPTURED_AT);
            case MISMATCHED -> receipt.applyDecision(ReceiptMatchDecision.mismatched("총액 불일치"), CAPTURED_AT);
            case NEEDS_REVIEW -> receipt.applyDecision(ReceiptMatchDecision.needsReview("신뢰도 미달"), CAPTURED_AT);
            case EXTRACTED -> { }
        }
        return receipt;
    }

    @Test
    @DisplayName("최신 영수증이 MATCHED 면 승인 통과")
    void approvesWithMatchedReceipt() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(submittedReport()));
        when(loadExpenseReceiptPort.findLatestByReportId("RPT-1"))
                .thenReturn(Optional.of(receiptIn(ExpenseReceiptStatus.MATCHED)));
        when(saveExpenseReportPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReport approved = service.approve(new ApproveExpenseReportCommand("RPT-1", 99L));

        assertThat(approved.getStatus()).isEqualTo(ExpenseReportStatus.APPROVED);
    }

    @Test
    @DisplayName("MISMATCHED 영수증으로는 승인 불가(422) — 보고서 상태 불변")
    void blocksMismatchedReceipt() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(submittedReport()));
        when(loadExpenseReceiptPort.findLatestByReportId("RPT-1"))
                .thenReturn(Optional.of(receiptIn(ExpenseReceiptStatus.MISMATCHED)));

        assertThatThrownBy(() -> service.approve(new ApproveExpenseReportCommand("RPT-1", 99L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_RECEIPT_NOT_MATCHED));

        verify(saveExpenseReportPort, never()).save(any());
    }

    @Test
    @DisplayName("NEEDS_REVIEW·EXTRACTED 영수증도 승인 차단 — 리뷰 종결이 먼저다")
    void blocksPendingReceipt() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(submittedReport()));

        when(loadExpenseReceiptPort.findLatestByReportId("RPT-1"))
                .thenReturn(Optional.of(receiptIn(ExpenseReceiptStatus.NEEDS_REVIEW)));
        assertThatThrownBy(() -> service.approve(new ApproveExpenseReportCommand("RPT-1", 99L)))
                .isInstanceOf(BusinessException.class);

        when(loadExpenseReceiptPort.findLatestByReportId("RPT-1"))
                .thenReturn(Optional.of(receiptIn(ExpenseReceiptStatus.EXTRACTED)));
        assertThatThrownBy(() -> service.approve(new ApproveExpenseReportCommand("RPT-1", 99L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("영수증이 없으면 기존 경로 그대로 승인 통과 (점진 도입)")
    void approvesWithoutReceipt() {
        when(loadExpenseReportPort.findByReportId("RPT-1")).thenReturn(Optional.of(submittedReport()));
        when(loadExpenseReceiptPort.findLatestByReportId("RPT-1")).thenReturn(Optional.empty());
        when(saveExpenseReportPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReport approved = service.approve(new ApproveExpenseReportCommand("RPT-1", 99L));

        assertThat(approved.getStatus()).isEqualTo(ExpenseReportStatus.APPROVED);
    }
}
