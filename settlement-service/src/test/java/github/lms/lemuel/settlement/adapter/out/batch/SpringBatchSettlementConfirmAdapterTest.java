package github.lms.lemuel.settlement.adapter.out.batch;

import github.lms.lemuel.settlement.application.port.out.RunSettlementConfirmBatchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 확정 배치 실행 어댑터 — JobParameters 조립과 결과 변환이 핵심 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class SpringBatchSettlementConfirmAdapterTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 5);

    @Mock JobOperator jobOperator;
    @Mock Job confirmSettlementJob;

    SpringBatchSettlementConfirmAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringBatchSettlementConfirmAdapter(jobOperator, confirmSettlementJob);
    }

    private static JobExecution execution(BatchStatus status, long read, long write) {
        JobExecution execution =
                new JobExecution(1L, new JobInstance(1L, "confirmSettlementJob"), new JobParameters());
        StepExecution step = new StepExecution("confirmStep", execution);
        step.setReadCount(read);
        step.setWriteCount(write);
        execution.addStepExecutions(java.util.List.of(step));
        execution.setStatus(status);
        return execution;
    }

    @Test
    @DisplayName("targetDate 를 JobParameters 로 넘기고 read/write 를 결과로 반환")
    void passesTargetDateAndMapsCounts() throws Exception {
        when(jobOperator.start(eq(confirmSettlementJob), any()))
                .thenReturn(execution(BatchStatus.COMPLETED, 20, 12));

        RunSettlementConfirmBatchPort.BatchRunResult result = adapter.runFor(TARGET);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(confirmSettlementJob), captor.capture());
        assertThat(captor.getValue().getString("targetDate")).isEqualTo("2026-08-05");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.read()).isEqualTo(20);
        assertThat(result.written()).isEqualTo(12);
        assertThat(result.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("같은 일자를 다시 실행해도 requestedAt 이 달라 별개 JobInstance 가 된다 — 재실행 허용")
    void addsRequestedAtSoRerunIsAllowed() throws Exception {
        when(jobOperator.start(eq(confirmSettlementJob), any()))
                .thenReturn(execution(BatchStatus.COMPLETED, 1, 1));

        adapter.runFor(TARGET);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(confirmSettlementJob), captor.capture());
        assertThat(captor.getValue().getLong("requestedAt")).isNotNull();
    }

    @Test
    @DisplayName("배치가 FAILED 로 끝나면 상태를 그대로 전달 — 성공으로 포장하지 않는다")
    void propagatesFailedStatus() throws Exception {
        when(jobOperator.start(eq(confirmSettlementJob), any()))
                .thenReturn(execution(BatchStatus.FAILED, 20, 0));

        RunSettlementConfirmBatchPort.BatchRunResult result = adapter.runFor(TARGET);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("Job 기동 자체가 실패하면 런타임 예외로 승격 — 오케스트레이터가 단계 실패로 흡수한다")
    void wrapsStartFailure() throws Exception {
        when(jobOperator.start(eq(confirmSettlementJob), any()))
                .thenThrow(new IllegalStateException("repository down"));

        assertThatThrownBy(() -> adapter.runFor(TARGET))
                .isInstanceOf(SpringBatchSettlementConfirmAdapter.SettlementConfirmBatchException.class)
                .hasMessageContaining("2026-08-05")
                .hasRootCauseMessage("repository down");
    }
}
