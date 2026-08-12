package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase.ApplyLeaseCommand;
import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.application.port.out.SaveLeaseContractPort;
import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseStatus;
import github.lms.lemuel.loan.domain.exception.LeaseContractNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스·할부 유스케이스 배선 검증 — 계산은 도메인이 하고, 이 서비스는 조회·전이·저장의 순서와
 * <b>락 조회 여부</b>·<b>소유권 대조</b>를 책임진다. 그 셋만 본다.
 */
class LeaseContractServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
    private static final Long BORROWER = 7L;

    private FakeLeasePort port;
    private LeaseContractService service;

    @BeforeEach
    void setUp() {
        port = new FakeLeasePort();
        service = new LeaseContractService(port, port, CLOCK);
    }

    private static ApplyLeaseCommand command(AssetFinanceType type, BigDecimal residual, String registrationNo) {
        return new ApplyLeaseCommand(BORROWER, "㈜테스트", registrationNo, type, "지게차 3톤",
                new BigDecimal("30000000"), BigDecimal.ZERO, new BigDecimal("3000000"), residual,
                36, new BigDecimal("6.0"));
    }

    private LeaseContract activeContract() {
        LeaseContract applied = service.apply(command(AssetFinanceType.FINANCE_LEASE,
                new BigDecimal("6000000"), "1234567890"));
        service.approve(applied.getId());
        return service.activate(applied.getId());
    }

    @Test
    @DisplayName("신청 시 스케줄이 산정되어 저장된다 — 사업자번호가 있으면 법인 차주")
    void applyBuildsScheduleAndPersists() {
        LeaseContract contract = service.apply(command(AssetFinanceType.FINANCE_LEASE,
                new BigDecimal("6000000"), "1234567890"));

        assertThat(contract.getStatus()).isEqualTo(LeaseStatus.APPLIED);
        assertThat(contract.getBorrower().type()).isEqualTo(BorrowerType.CORPORATE);
        assertThat(contract.getSchedule().financedAmount()).isEqualByComparingTo("27000000");
        assertThat(contract.getSchedule().installments()).hasSize(36);
        assertThat(contract.getAppliedAt()).isEqualTo(CLOCK.instant().atOffset(ZoneOffset.UTC));
        assertThat(port.saved).hasSize(1);
    }

    @Test
    @DisplayName("사업자번호가 없으면 개인 차주로 만든다")
    void applyCreatesIndividualBorrowerWithoutRegistrationNo() {
        LeaseContract contract = service.apply(command(AssetFinanceType.INSTALLMENT, BigDecimal.ZERO, null));

        assertThat(contract.getBorrower().type()).isEqualTo(BorrowerType.INDIVIDUAL);
        assertThat(contract.getType()).isEqualTo(AssetFinanceType.INSTALLMENT);
    }

    @Test
    @DisplayName("승인 → 개시 → 수납 → 만기 종료가 저장까지 이어진다")
    void lifecycleTransitionsArePersisted() {
        LeaseContract contract = activeContract();
        assertThat(contract.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(contract.getActivatedAt()).isEqualTo(CLOCK.instant().atOffset(ZoneOffset.UTC));

        for (int i = 0; i < 36; i++) {
            service.payInstallment(contract.getId());
        }
        LeaseContract matured = service.mature(contract.getId());

        assertThat(matured.getStatus()).isEqualTo(LeaseStatus.MATURED);
        assertThat(matured.getPaidInstallments()).isEqualTo(36);
        assertThat(matured.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("상태를 바꾸는 조작은 전부 락 조회로 시작한다 — 수납·해지 경합 차단")
    void mutationsUseLockedRead() {
        LeaseContract contract = activeContract();
        int lockedBefore = port.lockedReads;

        service.payInstallment(contract.getId());
        service.markOverdue(contract.getId());
        service.terminateEarly(contract.getId(), new BigDecimal("3"));

        assertThat(port.lockedReads - lockedBefore).isEqualTo(3);
    }

    @Test
    @DisplayName("중도해지는 정산서를 돌려주고 계약을 종결한다")
    void terminateEarlyReturnsQuoteAndClosesContract() {
        LeaseContract contract = activeContract();
        service.payInstallment(contract.getId());

        EarlyTerminationQuote quote = service.terminateEarly(contract.getId(), new BigDecimal("3"));

        assertThat(quote.settledInstallmentNo()).isEqualTo(1);
        assertThat(quote.payable()).isPositive();
        assertThat(port.findById(contract.getId()).orElseThrow().getStatus())
                .isEqualTo(LeaseStatus.EARLY_TERMINATED);
    }

    @Test
    @DisplayName("정산 조회는 상태를 바꾸지 않는다")
    void quoteDoesNotMutate() {
        LeaseContract contract = activeContract();

        service.quoteEarlyTermination(contract.getId(), new BigDecimal("3"), BORROWER);

        assertThat(port.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    @DisplayName("남의 계약 정산 조회는 403 이 아니라 404 — 번호를 훑어 존재를 확인할 수 없게 한다")
    void otherBorrowerGetsNotFoundNotForbidden() {
        LeaseContract contract = activeContract();

        assertThatThrownBy(() -> service.quoteEarlyTermination(contract.getId(), new BigDecimal("3"), 999L))
                .isInstanceOf(LeaseContractNotFoundException.class);
    }

    @Test
    @DisplayName("운영자 조회(요청자 null)는 소유권 대조를 건너뛴다")
    void operatorQuoteSkipsOwnershipCheck() {
        LeaseContract contract = activeContract();

        EarlyTerminationQuote quote = service.quoteEarlyTermination(
                contract.getId(), new BigDecimal("3"), null);

        assertThat(quote.outstandingBalance()).isPositive();
    }

    @Test
    @DisplayName("없는 계약을 만지면 404 예외")
    void missingContractThrowsNotFound() {
        assertThatThrownBy(() -> service.approve(404L)).isInstanceOf(LeaseContractNotFoundException.class);
        assertThatThrownBy(() -> service.terminateEarly(404L, BigDecimal.ONE))
                .isInstanceOf(LeaseContractNotFoundException.class);
        assertThatThrownBy(() -> service.quoteEarlyTermination(404L, BigDecimal.ONE, BORROWER))
                .isInstanceOf(LeaseContractNotFoundException.class);
    }

    @Test
    @DisplayName("무산 경로(거절·취소)도 저장된다")
    void rejectAndCancelArePersisted() {
        LeaseContract applied = service.apply(command(AssetFinanceType.FINANCE_LEASE,
                new BigDecimal("6000000"), "1234567890"));
        assertThat(service.reject(applied.getId()).getStatus()).isEqualTo(LeaseStatus.REJECTED);

        LeaseContract second = service.apply(command(AssetFinanceType.OPERATING_LEASE,
                new BigDecimal("6000000"), "1234567890"));
        service.approve(second.getId());
        assertThat(service.cancel(second.getId()).getStatus()).isEqualTo(LeaseStatus.CANCELLED);
    }

    @Test
    @DisplayName("연체 → 기한이익상실도 유스케이스로 이어진다")
    void overdueThenDefault() {
        LeaseContract contract = activeContract();

        service.markOverdue(contract.getId());
        LeaseContract defaulted = service.markDefaulted(contract.getId());

        assertThat(defaulted.getStatus()).isEqualTo(LeaseStatus.DEFAULTED);
    }

    /** 인메모리 포트 — 락 조회 횟수를 세어 "상태 변경은 락 조회로 시작한다"를 검증한다. */
    private static final class FakeLeasePort implements LoadLeaseContractPort, SaveLeaseContractPort {

        private final List<LeaseContract> saved = new ArrayList<>();
        private long sequence = 0;
        private int lockedReads = 0;

        @Override
        public LeaseContract save(LeaseContract contract) {
            LeaseContract stored = contract.getId() == null
                    ? LeaseContract.reconstitute(++sequence, contract.getBorrower(), contract.getType(),
                            contract.getAssetDescription(), contract.getSchedule(), contract.getStatus(),
                            contract.getPaidInstallments(), contract.getAppliedAt(), contract.getActivatedAt(),
                            contract.getClosedAt())
                    : contract;
            saved.removeIf(existing -> existing.getId().equals(stored.getId()));
            saved.add(stored);
            return stored;
        }

        @Override
        public Optional<LeaseContract> findById(Long contractId) {
            return saved.stream().filter(c -> c.getId().equals(contractId)).findFirst().map(FakeLeasePort::copy);
        }

        @Override
        public Optional<LeaseContract> findByIdForUpdate(Long contractId) {
            lockedReads++;
            return findById(contractId);
        }

        @Override
        public List<LeaseContract> findByBorrower(Long borrowerUserId, int limit) {
            return saved.stream()
                    .filter(c -> c.getBorrower().userId().equals(borrowerUserId))
                    .limit(limit)
                    .toList();
        }

        /** 저장소가 돌려준 인스턴스를 서비스가 변형해도 저장 전 상태가 오염되지 않도록 복제한다. */
        private static LeaseContract copy(LeaseContract contract) {
            return LeaseContract.reconstitute(contract.getId(), contract.getBorrower(), contract.getType(),
                    contract.getAssetDescription(), contract.getSchedule(), contract.getStatus(),
                    contract.getPaidInstallments(), contract.getAppliedAt(), contract.getActivatedAt(),
                    contract.getClosedAt());
        }
    }
}
