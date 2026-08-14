package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.ExpenseWorkflowAdapter.ApproveRequest;
import github.lms.lemuel.card.adapter.in.web.ExpenseWorkflowAdapter.BudgetUtilizationResponse;
import github.lms.lemuel.card.adapter.in.web.ExpenseWorkflowAdapter.ExpenseReportResponse;
import github.lms.lemuel.card.adapter.in.web.ExpenseWorkflowAdapter.RejectRequest;
import github.lms.lemuel.card.adapter.in.web.ExpenseWorkflowAdapter.SubmitRequest;
import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase.ApproveExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase.BudgetUtilization;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase.RejectExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase.SubmitExpenseReportCommand;
import github.lms.lemuel.card.domain.ExpenseReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지출 워크플로 REST 어댑터 (Phase 2).
 *
 * <p>어댑터의 계약은 두 가지다. ① 요청을 커맨드로 옮길 때 값을 잃거나 바꾸지 않을 것,
 * ② 금액·소진율을 <b>지수표기 없는 십진 문자열</b>로 내보낼 것(DATA-STANDARD N5).
 * 특히 소진율은 백분율이라 {@code 1E+2} 같은 표기가 새면 화면이 그대로 깨진다.
 */
class ExpenseWorkflowAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");

    private SubmitExpenseReportUseCase submitUseCase;
    private ApproveExpenseReportUseCase approveUseCase;
    private RejectExpenseReportUseCase rejectUseCase;
    private QueryBudgetUtilizationUseCase budgetUseCase;
    private ExpenseWorkflowAdapter adapter;

    @BeforeEach
    void setUp() {
        submitUseCase = mock(SubmitExpenseReportUseCase.class);
        approveUseCase = mock(ApproveExpenseReportUseCase.class);
        rejectUseCase = mock(RejectExpenseReportUseCase.class);
        budgetUseCase = mock(QueryBudgetUtilizationUseCase.class);
        adapter = new ExpenseWorkflowAdapter(submitUseCase, approveUseCase, rejectUseCase, budgetUseCase);
    }

    private static ExpenseReport report() {
        return ExpenseReport.createFromCapture("RPT-1", "CAP-1", "AUTH-1", 3L, 5L, 7L, "DEPT-1",
                77L, new BigDecimal("12000"), "스타벅스 강남점", NOW);
    }

    @Test
    @DisplayName("제출은 영수증·카테고리·메모를 그대로 커맨드로 옮긴다")
    void submitMapsCommand() {
        when(submitUseCase.submit(any(SubmitExpenseReportCommand.class))).thenReturn(report());

        var response = adapter.submit("RPT-1", new SubmitRequest("https://s3/r.jpg", "식비", "팀 회식"));

        ArgumentCaptor<SubmitExpenseReportCommand> captor =
                ArgumentCaptor.forClass(SubmitExpenseReportCommand.class);
        verify(submitUseCase).submit(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo("RPT-1");
        assertThat(captor.getValue().receiptUrl()).isEqualTo("https://s3/r.jpg");
        assertThat(captor.getValue().expenseCategory()).isEqualTo("식비");
        assertThat(captor.getValue().memo()).isEqualTo("팀 회식");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("응답의 금액은 지수표기 없는 십진 문자열이다")
    void responseAmountIsPlainString() {
        when(submitUseCase.submit(any(SubmitExpenseReportCommand.class))).thenReturn(report());

        ExpenseReportResponse body = adapter.submit("RPT-1", new SubmitRequest(null, "식비", null)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.amount()).isEqualTo("12000");
        assertThat(body.reportId()).isEqualTo("RPT-1");
        assertThat(body.captureId()).isEqualTo("CAP-1");
        assertThat(body.merchantName()).isEqualTo("스타벅스 강남점");
        assertThat(body.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("승인은 승인자 식별자를 함께 넘긴다")
    void approveMapsCommand() {
        when(approveUseCase.approve(any(ApproveExpenseReportCommand.class))).thenReturn(report());

        adapter.approve("RPT-1", new ApproveRequest(99L));

        ArgumentCaptor<ApproveExpenseReportCommand> captor =
                ArgumentCaptor.forClass(ApproveExpenseReportCommand.class);
        verify(approveUseCase).approve(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo("RPT-1");
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("반려는 사유를 함께 넘긴다 — 왜 반려됐는지가 남아야 한다")
    void rejectMapsCommand() {
        when(rejectUseCase.reject(any(RejectExpenseReportCommand.class))).thenReturn(report());

        adapter.reject("RPT-1", new RejectRequest(99L, "영수증 불명확"));

        ArgumentCaptor<RejectExpenseReportCommand> captor =
                ArgumentCaptor.forClass(RejectExpenseReportCommand.class);
        verify(rejectUseCase).reject(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo("RPT-1");
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);
        assertThat(captor.getValue().rejectReason()).isEqualTo("영수증 불명확");
    }

    @Test
    @DisplayName("예산 소진율은 조직·부서·연월로 조회하고 금액·비율을 plain string 으로 낸다")
    void budgetUtilizationIsPlainString() {
        when(budgetUseCase.getUtilization(7L, "DEPT-1", 2026, 8)).thenReturn(
                new BudgetUtilization(7L, "DEPT-1", 2026, 8,
                        new BigDecimal("10000000.00"), new BigDecimal("2500000.00"),
                        new BigDecimal("25.00")));

        BudgetUtilizationResponse body =
                adapter.getBudgetUtilization(7L, "DEPT-1", 2026, 8).getBody();

        assertThat(body).isNotNull();
        assertThat(body.organizationId()).isEqualTo(7L);
        assertThat(body.departmentId()).isEqualTo("DEPT-1");
        assertThat(body.year()).isEqualTo(2026);
        assertThat(body.month()).isEqualTo(8);
        assertThat(body.totalBudget()).isEqualTo("10000000.00");
        assertThat(body.approvedAmount()).isEqualTo("2500000.00");
        assertThat(body.utilizationPercent()).isEqualTo("25.00");
    }

    @Test
    @DisplayName("소진율 100% 같은 값도 지수표기로 새지 않는다")
    void utilizationKeepsPlainNotation() {
        when(budgetUseCase.getUtilization(7L, "DEPT-1", 2026, 8)).thenReturn(
                new BudgetUtilization(7L, "DEPT-1", 2026, 8,
                        new BigDecimal("1E+2"), new BigDecimal("1E+2"), new BigDecimal("1E+2")));

        BudgetUtilizationResponse body =
                adapter.getBudgetUtilization(7L, "DEPT-1", 2026, 8).getBody();

        assertThat(body).isNotNull();
        assertThat(body.utilizationPercent()).isEqualTo("100");
        assertThat(body.totalBudget()).doesNotContain("E");
    }
}
