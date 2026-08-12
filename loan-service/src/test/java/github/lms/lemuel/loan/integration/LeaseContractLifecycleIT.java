package github.lms.lemuel.loan.integration;

import github.lms.lemuel.LoanServiceApplication;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase.ApplyLeaseCommand;
import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseStatus;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스·할부 계약 E2E — 실 Flyway 체인 · 실 DB 제약 · 실 JPA 매핑.
 *
 * <p>단위 테스트는 페이크 포트로 도메인을 검증하지만, <b>DDL 과 엔티티 매핑이 어긋나는 사고는 실 DB
 * 에서만 드러난다</b>(컬럼 타입·정밀도·NOT NULL·CHECK). 특히 이 계약은 <b>회차표를 저장하지 않고
 * 산정 입력값만 저장</b>하므로, 저장 → 복원 후 회차표가 같은 값으로 재현되는지가 계약의 핵심이다.
 */
@SpringBootTest(
        classes = LoanServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.loan.economics.base-url=http://localhost:9"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class LeaseContractLifecycleIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_loan")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Long BORROWER = 8484L;

    @Autowired ManageLeaseContractUseCase leaseUseCase;
    @Autowired LoadLeaseContractPort loadPort;
    @Autowired JdbcTemplate jdbc;

    private LeaseContract applyFinanceLease() {
        return leaseUseCase.apply(new ApplyLeaseCommand(BORROWER, "㈜통합테스트", "1234567890",
                AssetFinanceType.FINANCE_LEASE, "지게차 3톤", new BigDecimal("30000000"),
                BigDecimal.ZERO, new BigDecimal("3000000"), new BigDecimal("6000000"),
                36, new BigDecimal("6.0")));
    }

    @Test
    @DisplayName("저장 → 복원 후 회차표가 같은 값으로 재현된다 — 회차를 저장하지 않는 설계의 핵심")
    void scheduleIsReproducedFromStoredInputs() {
        LeaseContract applied = applyFinanceLease();

        LeaseContract restored = loadPort.findById(applied.getId()).orElseThrow();

        assertThat(restored.getSchedule()).isEqualTo(applied.getSchedule());
        assertThat(restored.getSchedule().installments()).hasSize(36);
        assertThat(restored.getSchedule().installments().getLast().remainingBalance())
                .isEqualByComparingTo("6000000");
        assertThat(restored.getBorrower().registrationNo()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("전 생명주기가 실 DB 를 거쳐 이어진다 — 신청→승인→개시→수납→만기")
    void fullLifecycleThroughRealDatabase() {
        LeaseContract applied = applyFinanceLease();
        leaseUseCase.approve(applied.getId());
        leaseUseCase.activate(applied.getId());
        for (int i = 0; i < 36; i++) {
            leaseUseCase.payInstallment(applied.getId());
        }
        LeaseContract matured = leaseUseCase.mature(applied.getId());

        assertThat(matured.getStatus()).isEqualTo(LeaseStatus.MATURED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM opslab.lease_contracts WHERE id = ?", String.class, applied.getId()))
                .isEqualTo("MATURED");
        assertThat(jdbc.queryForObject(
                "SELECT paid_installments FROM opslab.lease_contracts WHERE id = ?", Integer.class, applied.getId()))
                .isEqualTo(36);
        assertThat(jdbc.queryForObject(
                "SELECT closed_at IS NOT NULL FROM opslab.lease_contracts WHERE id = ?", Boolean.class, applied.getId()))
                .isTrue();
    }

    @Test
    @DisplayName("중도해지 정산이 실 DB 상태에 반영된다")
    void earlyTerminationPersists() {
        LeaseContract applied = applyFinanceLease();
        leaseUseCase.approve(applied.getId());
        leaseUseCase.activate(applied.getId());
        leaseUseCase.payInstallment(applied.getId());

        EarlyTerminationQuote quote = leaseUseCase.terminateEarly(applied.getId(), new BigDecimal("3"));

        assertThat(quote.payable()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM opslab.lease_contracts WHERE id = ?", String.class, applied.getId()))
                .isEqualTo("EARLY_TERMINATED");
    }

    @Test
    @DisplayName("도메인 불변식은 DB CHECK 제약이 최종 방어한다 — 할부에 잔존가치를 직접 INSERT 하면 거부")
    void databaseRejectsInstallmentWithResidualValue() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.lease_contracts (borrower_type, borrower_user_id, borrower_name,
                    finance_type, asset_description, acquisition_cost, down_payment, deposit,
                    residual_value, term_months, annual_rate_percent, status, paid_installments, applied_at)
                VALUES ('INDIVIDUAL', ?, '홍길동', 'INSTALLMENT', '노트북', 10000000, 0, 0,
                    1000000, 12, 6.0, 'APPLIED', 0, NOW())
                """, BORROWER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("잔존가치가 리스 원금 이상이면 도메인과 DB 양쪽이 막는다")
    void residualBeyondFinancedAmountIsRejectedByBothLayers() {
        assertThatThrownBy(() -> leaseUseCase.apply(new ApplyLeaseCommand(BORROWER, "㈜통합테스트",
                "1234567890", AssetFinanceType.FINANCE_LEASE, "굴착기", new BigDecimal("10000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000000"), 12, new BigDecimal("6.0"))))
                .isInstanceOf(LoanInvariantViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.lease_contracts (borrower_type, borrower_user_id, borrower_name,
                    finance_type, asset_description, acquisition_cost, down_payment, deposit,
                    residual_value, term_months, annual_rate_percent, status, paid_installments, applied_at)
                VALUES ('CORPORATE', ?, '㈜통합테스트', 'FINANCE_LEASE', '굴착기', 10000000, 0, 0,
                    10000000, 12, 6.0, 'APPLIED', 0, NOW())
                """, BORROWER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("차주 스코핑 조회는 본인 계약만 돌려준다")
    void borrowerScopedQueryReturnsOwnContractsOnly() {
        applyFinanceLease();
        leaseUseCase.apply(new ApplyLeaseCommand(9999L, "남의회사", "9998887776",
                AssetFinanceType.OPERATING_LEASE, "복합기", new BigDecimal("12000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000000"), 24, new BigDecimal("5.0")));

        assertThat(loadPort.findByBorrower(BORROWER, 50))
                .isNotEmpty()
                .allMatch(contract -> contract.getBorrower().userId().equals(BORROWER));
    }
}
