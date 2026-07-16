package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock JobOperator jobOperator;
    @Mock Job confirmSettlementJob;
    @Mock AuditLogger auditLogger;

    // UTC 로는 2026-07-15, KST(+9) 로는 2026-07-16 인 순간 — targetDate 가 KST 기준(어제=07-15)으로
    // 나오는지로 zone 처리를 검증한다. JVM 기본(UTC)을 썼다면 07-14 가 나와 실패한다.
    private static final Clock FIXED_KST = Clock.fixed(
            Instant.parse("2026-07-15T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    SettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SettlementScheduler(jobOperator, confirmSettlementJob, FIXED_KST, auditLogger);
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
        // KST 어제 = 2026-07-15 (UTC 였다면 07-14 로 하루 어긋남)
        assertThat(usedParams.getString("targetDate")).isEqualTo("2026-07-15");
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

    @Test
    @DisplayName("확정 배치 실행을 SETTLEMENT_CONFIRMED 잡 요약으로 감사 기록한다")
    void scheduledConfirmDailySettlements_recordsAudit() throws Exception {
        JobInstance jobInstance = new JobInstance(3L, "confirmSettlementJob");
        JobExecution execution = new JobExecution(3L, jobInstance, new JobParameters());
        StepExecution step = new StepExecution("confirmStep", execution);
        step.setReadCount(7);
        step.setWriteCount(6);
        execution.addStepExecution(step);
        when(jobOperator.start(eq(confirmSettlementJob), any(JobParameters.class))).thenReturn(execution);

        scheduler.scheduledConfirmDailySettlements();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).record(eq(AuditAction.SETTLEMENT_CONFIRMED), eq("SettlementConfirmJob"),
                eq("2026-07-15"), detail.capture());
        assertThat(detail.getValue()).contains("\"confirmed\":6").contains("\"targetDate\":\"2026-07-15\"");
    }
}
