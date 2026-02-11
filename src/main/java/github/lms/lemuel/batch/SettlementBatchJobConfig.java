package github.lms.lemuel.batch;

import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.PaymentRepository;
import github.lms.lemuel.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Spring Batch 기반 정산 생성 Job
 * Chunk 방식으로 대용량 데이터 처리
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SettlementBatchJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;

    private static final int CHUNK_SIZE = 1000; // 1000건씩 처리

    @Bean
    public Job createSettlementJob() {
        return new JobBuilder("createSettlementJob", jobRepository)
                .start(createSettlementStep())
                .build();
    }

    @Bean
    public Step createSettlementStep() {
        return new StepBuilder("createSettlementStep", jobRepository)
                .<Payment, Settlement>chunk(CHUNK_SIZE, transactionManager)
                .reader(paymentReader())
                .processor(settlementProcessor())
                .writer(settlementWriter())
                .build();
    }

    /**
     * Reader: CAPTURED 상태의 Payment를 읽어옴
     * - Pagination 방식으로 메모리 효율적으로 처리
     */
    @Bean
    public RepositoryItemReader<Payment> paymentReader() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

        return new RepositoryItemReaderBuilder<Payment>()
                .name("paymentReader")
                .repository(paymentRepository)
                .methodName("findByCapturedAtBetweenAndStatus")
                .arguments(yesterday, today, "CAPTURED")
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }

    /**
     * Processor: Payment -> Settlement 변환
     * - 이미 정산이 생성된 Payment는 skip
     */
    @Bean
    public ItemProcessor<Payment, Settlement> settlementProcessor() {
        return payment -> {
            // 이미 정산이 존재하면 skip
            if (settlementRepository.findByPaymentId(payment.getId()).isPresent()) {
                log.debug("Settlement already exists for payment: {}", payment.getId());
                return null;
            }

            Settlement settlement = new Settlement();
            settlement.setPaymentId(payment.getId());
            settlement.setOrderId(payment.getOrderId());
            settlement.setStatus(Settlement.SettlementStatus.PENDING);
            settlement.setAmount(payment.getAmount().subtract(payment.getRefundedAmount()));
            settlement.setSettlementDate(LocalDate.now());

            log.debug("Created settlement for payment: {}", payment.getId());
            return settlement;
        };
    }

    /**
     * Writer: Settlement 일괄 저장
     * - Batch Insert로 성능 최적화
     */
    @Bean
    public ItemWriter<Settlement> settlementWriter() {
        return settlements -> {
            if (settlements.isEmpty()) {
                return;
            }

            settlementRepository.saveAll(settlements.getItems());
            log.info("Saved {} settlements", settlements.size());
        };
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
                .<Settlement, Settlement>chunk(CHUNK_SIZE, transactionManager)
                .reader(pendingSettlementReader())
                .processor(confirmProcessor())
                .writer(confirmWriter())
                .build();
    }

    @Bean
    public RepositoryItemReader<Settlement> pendingSettlementReader() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        return new RepositoryItemReaderBuilder<Settlement>()
                .name("pendingSettlementReader")
                .repository(settlementRepository)
                .methodName("findBySettlementDateAndStatus")
                .arguments(yesterday, Settlement.SettlementStatus.PENDING)
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }

    @Bean
    public ItemProcessor<Settlement, Settlement> confirmProcessor() {
        return settlement -> {
            settlement.setStatus(Settlement.SettlementStatus.CONFIRMED);
            settlement.setConfirmedAt(LocalDateTime.now());
            log.debug("Confirmed settlement: {}", settlement.getId());
            return settlement;
        };
    }

    @Bean
    public ItemWriter<Settlement> confirmWriter() {
        return settlements -> {
            if (settlements.isEmpty()) {
                return;
            }

            settlementRepository.saveAll(settlements.getItems());
            log.info("Confirmed {} settlements", settlements.size());
        };
    }
}
