package github.lms.lemuel.settlement.adapter.out.batch;

import github.lms.lemuel.settlement.application.port.out.RunSettlementConfirmBatchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 정산 확정 배치 실행 어댑터 — Spring Batch {@code JobOperator} 를 아웃바운드 포트 뒤에 가둔다.
 *
 * <p>{@code SettlementScheduler} 의 실행 방식과 동일하다: {@code requestedAt} 을 파라미터에 넣어
 * 같은 일자를 여러 번 트리거해도 각기 다른 JobInstance 가 되게 하고(재실행 허용), 중복 확정은
 * 리더가 REQUESTED 만 읽는 <b>데이터 수준 멱등</b>으로 막는다.
 *
 * <p>실행은 동기다 — 운영자가 재실행 API 를 호출하면 응답에 실제 확정 건수가 담겨야
 * "복구됐는지"를 그 자리에서 판단할 수 있다.
 */
@Slf4j
@Component
public class SpringBatchSettlementConfirmAdapter implements RunSettlementConfirmBatchPort {

    private final JobOperator jobOperator;

    /** 빈 이름이 곧 {@code SettlementConfirmJobConfig.JOB_NAME}("confirmSettlementJob") — 유일 Job 빈. */
    private final Job confirmSettlementJob;

    public SpringBatchSettlementConfirmAdapter(JobOperator jobOperator, Job confirmSettlementJob) {
        this.jobOperator = jobOperator;
        this.confirmSettlementJob = confirmSettlementJob;
    }

    @Override
    public BatchRunResult runFor(LocalDate targetDate) {
        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", targetDate.toString())
                .addLong("requestedAt", System.currentTimeMillis())
                .toJobParameters();

        log.info("정산 확정 Job 재실행 시작: targetDate={}", targetDate);
        try {
            JobExecution execution = jobOperator.start(confirmSettlementJob, params);

            long read = execution.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
            long written = execution.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
            log.info("정산 확정 Job 재실행 종료: targetDate={}, status={}, read={}, written={}",
                    targetDate, execution.getStatus(), read, written);

            return new BatchRunResult(execution.getStatus().name(), read, written);
        } catch (Exception e) {
            // 체크 예외(JobExecutionException 계열)를 런타임으로 승격 — 오케스트레이터가 단계 실패로
            // 흡수해 리포트에 남긴다. 여기서 삼키고 성공처럼 반환하면 복구 여부를 오판하게 된다.
            throw new SettlementConfirmBatchException(targetDate, e);
        }
    }

    /** 확정 배치 기동 자체가 실패한 경우 — 배치가 돌다 FAILED 로 끝난 것과 구분된다. */
    public static class SettlementConfirmBatchException extends RuntimeException {
        public SettlementConfirmBatchException(LocalDate targetDate, Throwable cause) {
            super("정산 확정 Job 기동 실패: targetDate=" + targetDate, cause);
        }
    }
}
