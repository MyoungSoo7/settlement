package github.lms.lemuel.ledger.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.ledger.adapter.out.persistence.LedgerEntryJpaEntity;
import github.lms.lemuel.ledger.adapter.out.persistence.SpringDataLedgerJpaRepository;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산→원장 자동 트리거의 end-to-end 검증 (트랜잭셔널 아웃박스 경로).
 *
 * <p>정산 확정/환불조정은 *같은 커밋* 으로 ledger_outbox 에 작업을 적재하고, 로컬 폴러가 PENDING
 * row 를 읽어 멱등 use case 를 호출해 분개를 작성한다. 본 테스트는 스케줄 폴러를 끄고
 * ({@code app.ledger-outbox.enabled=false}) 적재→처리를 동기적으로 구동해 검증한다 —
 * 통합 테스트엔 Flyway 가 없어 shedlock 테이블이 없으므로 {@code @SchedulerLock} 폴러를 직접
 * 돌리지 않는다.
 *
 * <p>Flyway 는 disable. 마이그레이션은 단일 PG 의 order-service 가 책임지므로
 * settlement-service 단독 부팅 시 schema 가 없다 — entity 기반 ddl 으로 대체.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                // application.yml 의 default_schema=opslab 을 override — 통합 테스트는 public 사용.
                // (testcontainers 2.x 의 withInitScript 가 shaded commons-io 누락으로 깨지므로 회피.)
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                // 스케줄 폴러 비활성 — 아웃박스를 테스트가 직접 드레인한다.
                "app.ledger-outbox.enabled=false",
                // JwtUtil 이 32 byte 이상의 secret 을 요구
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
class LedgerEndToEndIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POSTGRES_USER", POSTGRES::getUsername);
        registry.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired TransactionTemplate transactionTemplate;
    @Autowired SpringDataLedgerJpaRepository ledgerRepo;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired AdjustSettlementForRefundUseCase adjustUseCase;
    @Autowired EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Autowired ProcessLedgerOutboxPort processLedgerOutboxPort;

    @Test
    void 정산_확정_아웃박스_적재_후_폴러처리시_ledger_2건_작성() {
        // 1) DONE 정산을 직접 INSERT
        Long settlementId = transactionTemplate.execute(status -> {
            SettlementJpaEntity s = newSettlement(1001L, 2001L, "10000.00", "300.00", "9700.00", "DONE");
            return settlementRepo.save(s).getId();
        });

        // 2) 확정 트랜잭션이 하던 것처럼 CREATE_ENTRY 작업을 아웃박스에 적재
        enqueueLedgerTaskPort.enqueueCreate(List.of(settlementId));

        // 3) 폴러가 하던 처리를 동기적으로 구동
        drainLedgerOutbox();

        // 4) 검증
        List<LedgerEntryJpaEntity> rows =
                ledgerRepo.findByReferenceIdAndReferenceType(settlementId, "SETTLEMENT");
        assertThat(rows).hasSize(2);

        BigDecimal sum = rows.stream()
                .map(LedgerEntryJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10000.00");

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEntryType()).isEqualTo(LedgerEntryType.SETTLEMENT_CONFIRMED.name());
            assertThat(r.getStatus()).isEqualTo("POSTED");
        });

        // row 종류 검증 — PAYABLE/REVENUE 와 COMMISSION_EXPENSE/COMMISSION_REVENUE 한 쌍
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDebitAccount()).isEqualTo("ACCOUNTS_PAYABLE");
            assertThat(r.getCreditAccount()).isEqualTo("REVENUE");
            assertThat(r.getAmount()).isEqualByComparingTo("9700.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDebitAccount()).isEqualTo("COMMISSION_EXPENSE");
            assertThat(r.getCreditAccount()).isEqualTo("COMMISSION_REVENUE");
            assertThat(r.getAmount()).isEqualByComparingTo("300.00");
        });
    }

    @Test
    void 환불_정산조정_아웃박스_적재_후_폴러처리시_ledger_역분개_2건_작성() {
        // 1) PROCESSING 정산 INSERT (환불 가능 상태)
        Long paymentId = 3001L;
        transactionTemplate.executeWithoutResult(status -> {
            SettlementJpaEntity s = newSettlement(paymentId, 4001L, "10000.00", "300.00", "9700.00", "PROCESSING");
            settlementRepo.save(s);
        });

        // 2) 환불 정산조정 호출 — 같은 트랜잭션 안에서 REVERSE_ENTRY 작업을 아웃박스에 적재
        Long refundId = 99L;
        adjustUseCase.adjustSettlementForRefund(paymentId, new BigDecimal("5000.00"), refundId);

        // 3) 폴러가 하던 처리를 동기적으로 구동
        drainLedgerOutbox();

        // 4) 검증
        List<LedgerEntryJpaEntity> rows =
                ledgerRepo.findByReferenceIdAndReferenceType(refundId, "REFUND");
        assertThat(rows).hasSize(2);

        BigDecimal sum = rows.stream()
                .map(LedgerEntryJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("5000.00");

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEntryType()).isEqualTo(LedgerEntryType.REFUND_REVERSED.name());
            assertThat(r.getDebitAccount()).isEqualTo("SALES_REFUND");
            assertThat(r.getStatus()).isEqualTo("POSTED");
        });

        // 5,000 환불 × commission rate 3% 기준
        //   refundedCommission = 5000 × 300/10000 = 150
        //   refundedNet        = 5000 − 150       = 4850
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getCreditAccount()).isEqualTo("ACCOUNTS_PAYABLE");
            assertThat(r.getAmount()).isEqualByComparingTo("4850.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getCreditAccount()).isEqualTo("COMMISSION_REVENUE");
            assertThat(r.getAmount()).isEqualByComparingTo("150.00");
        });
    }

    /** 폴러 1 사이클을 동기적으로 실행 — PENDING 작업을 처리하고 완료 마킹. */
    private void drainLedgerOutbox() {
        List<LedgerOutboxTask> pending = processLedgerOutboxPort.fetchPending(100);
        for (LedgerOutboxTask task : pending) {
            processLedgerOutboxPort.execute(task);
            processLedgerOutboxPort.markDone(task.id());
        }
    }

    private SettlementJpaEntity newSettlement(Long paymentId, Long orderId,
                                              String paymentAmount, String commission, String netAmount,
                                              String status) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(orderId);
        s.setPaymentAmount(new BigDecimal(paymentAmount));
        s.setCommission(new BigDecimal(commission));
        s.setNetAmount(new BigDecimal(netAmount));
        s.setStatus(status);
        s.setSettlementDate(LocalDate.now());
        return s;
    }
}
