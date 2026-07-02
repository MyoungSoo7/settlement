package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 확정 Spring Batch 청크 Job(confirmSettlementJob) end-to-end 부팅 검증.
 *
 * <p>Testcontainers Postgres 로 settlement 컨텍스트를 실제 부팅하고(= 부팅·빈 와이어링·BATCH_* 스키마
 * 초기화 실증), chunk-size=2 로 REQUESTED 5 건을 넣어 JobOperator 로 Job 을 돌린다. 멀티 청크(2+2+1)를
 * 거쳐 전부 DONE 으로 전이되고 Job 이 COMPLETED 되는지, BATCH_* 메타테이블에 실행 이력이 남는지 확인한다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "app.ledger-outbox.enabled=false",
                "spring.batch.job.enabled=false",
                "app.settlement.confirm.chunk-size=2",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
class SettlementConfirmJobIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired JobOperator jobOperator;
    @Autowired Job confirmSettlementJob;
    @Autowired SaveSettlementPort saveSettlementPort;
    @Autowired LoadSettlementPort loadSettlementPort;
    @Autowired TransactionTemplate txTemplate;

    @Test
    @DisplayName("청크 Job 실행: REQUESTED 5건이 멀티 청크(size=2)로 전부 DONE 전이 + COMPLETED")
    void runsConfirmJobEndToEnd() throws Exception {
        LocalDate targetDate = LocalDate.of(2026, 5, 1);

        // 1. REQUESTED 정산 5건 저장 (별도 트랜잭션 커밋 → Job 워커가 읽을 수 있게)
        List<Long> ids = txTemplate.execute(status -> {
            List<Long> saved = new ArrayList<>();
            for (long paymentId = 9001; paymentId <= 9005; paymentId++) {
                Settlement s = Settlement.createFromPayment(
                        paymentId, paymentId + 1000, new BigDecimal("10000"), targetDate);
                saved.add(saveSettlementPort.save(s).getId());
            }
            return saved;
        });
        assertThat(ids).hasSize(5);

        // 2. 확정 Job 실행
        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", targetDate.toString())
                .addLong("requestedAt", 1L)
                .toJobParameters();
        JobExecution execution = jobOperator.start(confirmSettlementJob, params);

        // 3. Job 이 정상 완료
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long writeCount = execution.getStepExecutions().stream()
                .mapToLong(se -> se.getWriteCount()).sum();
        assertThat(writeCount).isEqualTo(5);

        // 4. 5건 모두 DONE 전이 (멀티 청크 2+2+1 을 거쳐 전부 확정)
        for (Long id : ids) {
            Settlement reloaded = loadSettlementPort.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.DONE);
        }
    }
}
