package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.application.port.out.PublishLeaseEventPort;
import github.lms.lemuel.loan.application.port.out.SaveLeaseContractPort;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import github.lms.lemuel.loan.domain.exception.LeaseContractNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

/**
 * 리스·할부 계약 유스케이스 구현.
 *
 * <p><b>상태를 바꾸는 조작은 전부 비관적 락 조회로 시작한다.</b> 회차 수납과 중도해지가 동시에 들어오면
 * 납입 회차가 덮어써지거나 이미 종료된 계약에 손해금이 다시 매겨질 수 있다 — 돈이 걸린 경합이라
 * 낙관적 재시도가 아니라 직렬화가 맞다.
 *
 * <p>계산·전이 판단은 전부 도메인({@link LeaseContract}·{@link LeaseSchedule})에 있다. 이 서비스는
 * 조회 → 도메인 호출 → 저장의 배선과 소유권 대조만 한다.
 */
@Service
public class LeaseContractService implements ManageLeaseContractUseCase {

    private final LoadLeaseContractPort loadPort;
    private final SaveLeaseContractPort savePort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishLeaseEventPort publishLeaseEventPort;
    private final Clock clock;

    public LeaseContractService(LoadLeaseContractPort loadPort, SaveLeaseContractPort savePort,
                                AppendLedgerPort appendLedgerPort, PublishLeaseEventPort publishLeaseEventPort,
                                Clock clock) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishLeaseEventPort = publishLeaseEventPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LeaseContract apply(ApplyLeaseCommand command) {
        LeaseSchedule schedule = LeaseSchedule.of(command.financeType(), command.acquisitionCost(),
                command.downPayment(), command.deposit(), command.residualValue(),
                command.termMonths(), command.annualRatePercent());
        Borrower borrower = command.isCorporate()
                ? Borrower.corporate(command.borrowerUserId(), command.borrowerName(),
                        command.borrowerRegistrationNo())
                : Borrower.individual(command.borrowerUserId(), command.borrowerName());

        return savePort.save(LeaseContract.apply(
                borrower, command.assetDescription(), schedule, OffsetDateTime.now(clock)));
    }

    @Override
    @Transactional
    public LeaseContract approve(Long contractId) {
        return mutate(contractId, LeaseContract::approve);
    }

    @Override
    @Transactional
    public LeaseContract reject(Long contractId) {
        return mutate(contractId, LeaseContract::reject);
    }

    @Override
    @Transactional
    public LeaseContract cancel(Long contractId) {
        return mutate(contractId, LeaseContract::cancel);
    }

    /**
     * 물건 인도 → 계약 개시. <b>도메인 저장·원장 기표·이벤트 발행이 한 트랜잭션</b>이다 —
     * 셋 중 하나만 남으면 채권과 원장이, 또는 원장과 소비측이 어긋난다.
     *
     * <p>이중 기표 방어는 3중이다: ① 상태머신이 {@code APPROVED → ACTIVE} 를 한 번만 허용 ②
     * 락 조회로 동시 진입 차단 ③ {@code uq_loan_ledger_reference_accounts} 유니크(DB 최종선).
     */
    @Override
    @Transactional
    public LeaseContract activate(Long contractId) {
        LeaseContract contract = mutate(contractId, c -> c.activate(OffsetDateTime.now(clock)));
        appendLedgerPort.append(
                LoanLedgerEntry.leaseActivation(contract.getId(), contract.getSchedule().financedAmount()));
        publishLeaseEventPort.publishActivated(contract);
        return contract;
    }

    @Override
    @Transactional
    public LeaseContract payInstallment(Long contractId) {
        return mutate(contractId, LeaseContract::payInstallment);
    }

    @Override
    @Transactional
    public LeaseContract markOverdue(Long contractId) {
        return mutate(contractId, LeaseContract::markOverdue);
    }

    @Override
    @Transactional
    public LeaseContract markDefaulted(Long contractId) {
        return mutate(contractId, LeaseContract::markDefaulted);
    }

    @Override
    @Transactional
    public LeaseContract mature(Long contractId) {
        return mutate(contractId, contract -> contract.mature(OffsetDateTime.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public EarlyTerminationQuote quoteEarlyTermination(Long contractId, BigDecimal penaltyRatePercent,
                                                       Long requesterUserId) {
        LeaseContract contract = loadPort.findById(contractId).orElseThrow(() -> notFound(contractId));
        if (requesterUserId != null && !requesterUserId.equals(contract.getBorrower().userId())) {
            // 남의 계약은 존재 자체를 알리지 않는다 — 번호를 훑어 존재를 확인하는 것을 막는다.
            throw notFound(contractId);
        }
        return contract.quoteEarlyTermination(penaltyRatePercent);
    }

    @Override
    @Transactional
    public EarlyTerminationQuote terminateEarly(Long contractId, BigDecimal penaltyRatePercent) {
        LeaseContract contract = loadPort.findByIdForUpdate(contractId)
                .orElseThrow(() -> notFound(contractId));
        EarlyTerminationQuote quote = contract.terminateEarly(penaltyRatePercent, OffsetDateTime.now(clock));
        savePort.save(contract);
        return quote;
    }

    private LeaseContract mutate(Long contractId, Consumer<LeaseContract> transition) {
        LeaseContract contract = loadPort.findByIdForUpdate(contractId)
                .orElseThrow(() -> notFound(contractId));
        transition.accept(contract);
        return savePort.save(contract);
    }

    private static LeaseContractNotFoundException notFound(Long contractId) {
        return new LeaseContractNotFoundException("리스·할부 계약을 찾을 수 없습니다: " + contractId);
    }
}
