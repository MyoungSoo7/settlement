package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.settlement.adapter.in.batch.tasklet.ConfirmSettlementsTasklet;
import github.lms.lemuel.settlement.adapter.in.batch.tasklet.CreateSettlementsTasklet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Settlement Batch Job Configuration
 * Spring Batch Job/Step 구성만 담당 (비즈니스 로직 없음)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SettlementJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CreateSettlementsTasklet createSettlementsTasklet;
    private final ConfirmSettlementsTasklet confirmSettlementsTasklet;

    /**
     * 정산 생성 Job
     */
    @Bean
    public Job createSettlementJob() {
        return new JobBuilder("createSettlementJob", jobRepository)
                .start(createSettlementStep())
                .build();
    }

    @Bean
    public Step createSettlementStep() {
        return new StepBuilder("createSettlementStep", jobRepository)
                .tasklet(createSettlementsTasklet, transactionManager)
                .build();
    }

    /**
     * 정산 확정 Job
     */
    @Bean
    public Job confirmSettlementJob() {
        return new JobBuilder("confirmSettlementJob", jobRepository)
                .start(confirmSettlementStep())
                .build();
    }

    @Bean
    public Step confirmSettlementStep() {
        return new StepBuilder("confirmSettlementStep", jobRepository)
                .tasklet(confirmSettlementsTasklet, transactionManager)
                .build();
    }
}
