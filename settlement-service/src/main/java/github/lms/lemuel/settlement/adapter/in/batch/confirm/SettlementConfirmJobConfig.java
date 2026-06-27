package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.settlement.domain.Settlement;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 정산 확정 Spring Batch Job — chunk-oriented(Reader/Processor/Writer) 청크 처리.
 *
 * <p>과거 단일 Tasklet(하루치 단일 트랜잭션)을 청크 스텝으로 전환해, {@code chunk-size} 마다 커밋하여
 * 롱 트랜잭션과 비관적 락 보유 시간을 제한한다. 한 청크가 실패하면 그 청크만 롤백되고 Job 은 FAILED
 * 로 남아 재시작 가능(이전 청크는 커밋 보존) — 기존 all-or-nothing 보다 진행/복원성이 좋다.
 *
 * <p>스케줄러({@code SettlementScheduler})가 {@code JobLauncher} 로 {@code targetDate} 파라미터와
 * 함께 실행한다. startup 자동 실행은 {@code spring.batch.job.enabled=false} 로 차단한다.
 */
@Configuration
public class SettlementConfirmJobConfig {

    public static final String JOB_NAME = "confirmSettlementJob";
    public static final String STEP_NAME = "confirmSettlementStep";

    @Bean
    public Job confirmSettlementJob(JobRepository jobRepository, Step confirmSettlementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(confirmSettlementStep)
                .build();
    }

    @Bean
    public Step confirmSettlementStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SettlementConfirmItemReader reader,
            SettlementConfirmProcessor processor,
            SettlementConfirmItemWriter writer,
            @Value("${app.settlement.confirm.chunk-size:100}") int chunkSize) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Settlement, Settlement>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
}
