package github.lms.lemuel.settlement.adapter.in.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock JobOperator jobOperator;
    @Mock Job confirmSettlementJob;

    SettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SettlementScheduler(jobOperator, confirmSettlementJob);
    }

    @Test
    @DisplayName("전일자를 targetDate 파라미터로 Job 을 실행하고 read/write count 를 집계한다")
    void scheduledConfirmDailySettlements_startsJobWithYesterdayTargetDate() throws Exception {
        JobInstance jobInstance = new JobInstance(1L, "confirmSettlementJob");
        JobExecution execution = new JobExecution(1L, jobInstance, new JobParameters());
        StepExecution step = new StepExecution("confirmStep", execution);
        step.setReadCount(10);
        step.setWriteCount(9);
        execution.addStepExecution(step);

        when(jobOperator.start(eq(confirmSettlementJob), any(JobParameters.class))).thenReturn(execution);

        scheduler.scheduledConfirmDailySettlements();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(confirmSettlementJob), captor.capture());
        JobParameters usedParams = captor.getValue();
        assertThat(usedParams.getString("targetDate")).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(usedParams.getLong("requestedAt")).isNotNull();
    }

    @Test
    @DisplayName("스텝이 여러 개면 read/write count 를 합산한다")
    void scheduledConfirmDailySettlements_sumsMultipleStepCounts() throws Exception {
        JobInstance jobInstance = new JobInstance(2L, "confirmSettlementJob");
        JobExecution execution = new JobExecution(2L, jobInstance, new JobParameters());
        StepExecution step1 = new StepExecution("confirmStep1", execution);
        step1.setReadCount(5);
        step1.setWriteCount(4);
        StepExecution step2 = new StepExecution("confirmStep2", execution);
        step2.setReadCount(3);
        step2.setWriteCount(2);
        execution.addStepExecution(step1);
        execution.addStepExecution(step2);

        when(jobOperator.start(eq(confirmSettlementJob), any(JobParameters.class))).thenReturn(execution);

        scheduler.scheduledConfirmDailySettlements();

        long totalRead = execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
        long totalWrite = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        assertThat(totalRead).isEqualTo(8);
        assertThat(totalWrite).isEqualTo(6);
    }
}
