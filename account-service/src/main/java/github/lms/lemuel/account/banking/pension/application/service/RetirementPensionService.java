package github.lms.lemuel.account.banking.pension.application.service;

import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionQuery;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase;
import github.lms.lemuel.account.banking.pension.application.port.out.LoadRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.application.port.out.SaveRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.domain.PensionTransaction;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionAccessDeniedException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionNotFoundException;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 퇴직연금 서브원장 유스케이스 — 도메인 판정 → 적재 → <b>인프로세스 GL 전기</b> 순으로 묶는다.
 *
 * <p><b>이벤트를 발행하지 않는다.</b> account-service 는 소비 전용 경계라 Kafka·Outbox 로 나가는 길이
 * 없다(ArchUnit 이 하드스톱). 퇴직연금은 계정계 <i>안에</i> 사는 서브도메인이므로 GL 전기는
 * {@link RecordAccountEntryUseCase#record(AccountEntry)} 를 같은 트랜잭션에서 직접 호출해 끝낸다 —
 * 서브원장과 GL 이 원자적으로 함께 움직이는 것이 이 배선의 목적이다.
 *
 * <p><b>seq 는 도메인이 센다.</b> 각 상태 변경 메서드가 방금 만든 거래를 돌려주고, 서비스는 그
 * {@code seq} 를 GL 전표 자연키로 넘긴다. 서비스가 seq 를 따로 계산하면 서브원장과 전표의 유일성
 * 근거가 둘로 갈라진다.
 *
 * <p><b>소유권(IDOR)</b>: {@code subscriberId} 는 커맨드에 실려 오지만 그 값을 채우는 곳은 웹 어댑터의
 * JWT 주체뿐이다. 계약을 읽자마자 소유자 대조를 하고 불일치면 403 으로 닫는다.
 *
 * <p><b>"오늘" 은 서버가 정한다.</b> 모든 발생일은 주입받은 {@link Clock}(계정계 표준시 빈,
 * {@code account.config.TimeConfig})에서 나온다. 날짜를 요청에서 받으면 소급 일자로 이자 경과일수를
 * 늘리거나 수급 개시일을 조작할 수 있다 — 정기예금·적금과 동일한 방어다. 도메인 메서드는 여전히
 * 날짜를 인자로 받는 순수 형태를 유지해 테스트 결정성을 지킨다.
 */
@Service
public class RetirementPensionService implements RetirementPensionUseCase, RetirementPensionQuery {

    private final SaveRetirementPensionPort saveRetirementPensionPort;
    private final LoadRetirementPensionPort loadRetirementPensionPort;
    private final RecordAccountEntryUseCase recordAccountEntryUseCase;
    private final Clock clock;

    public RetirementPensionService(SaveRetirementPensionPort saveRetirementPensionPort,
                                    LoadRetirementPensionPort loadRetirementPensionPort,
                                    RecordAccountEntryUseCase recordAccountEntryUseCase,
                                    Clock clock) {
        this.saveRetirementPensionPort = saveRetirementPensionPort;
        this.loadRetirementPensionPort = loadRetirementPensionPort;
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RetirementPension open(OpenPensionCommand command) {
        // 가입 자체는 자금 이동이 아니다 — 적립금 0 으로 시작하므로 GL 전표가 없다.
        RetirementPension pension = RetirementPension.open(
                command.subscriberId(), command.scheme(), command.employerName(), command.birthDate(),
                command.annualRate(), today(), command.productName(), command.productRate());
        return saveRetirementPensionPort.save(pension);
    }

    @Override
    @Transactional
    public RetirementPension contribute(ContributeCommand command) {
        RetirementPension pension = loadOwned(command.subscriberId(), command.pensionId());
        PensionTransaction tx = pension.contribute(command.amount(), command.source(), today());
        RetirementPension saved = saveRetirementPensionPort.save(pension);
        post(tx.getAmount(), () -> AccountEntry.pensionContributionPaid(
                pension.getSubscriberId(), pensionRef(command.pensionId()), tx.getSeq(), tx.getAmount()));
        return saved;
    }

    /**
     * 운용수익 확정 — <b>금액을 받지 않는다</b>. 애그리게이트가 계약이율·적립금·경과일수로 산출하며,
     * 산출 이자가 0원이면 거래도 전표도 만들지 않는다({@code AccountEntry} 팩토리가 0을 거부한다).
     *
     * <p><b>운영자 경로</b>: 라우트가 ADMIN/MANAGER 로 잠겨 있으므로(SecurityConfig) 호출자 신원으로
     * 소유권을 대조하지 않는다 — 대조하면 운영자는 어떤 계약도 만질 수 없어 경로 자체가 죽는다.
     * 대신 전표의 owner 를 <b>계약에 적힌 가입자</b>에서 뽑아, 운영자가 남의 이자를 자기 앞으로
     * 기표시킬 여지를 없앤다.
     */
    @Override
    @Transactional
    public RetirementPension settleInterest(SettleInterestCommand command) {
        RetirementPension pension = load(command.pensionId());
        Optional<PensionTransaction> settled = pension.settleInterest(today());
        RetirementPension saved = saveRetirementPensionPort.save(pension);
        settled.ifPresent(tx -> post(tx.getAmount(), () -> AccountEntry.pensionInterestSettled(
                pension.getSubscriberId(), pensionRef(command.pensionId()), tx.getSeq(), tx.getAmount())));
        return saved;
    }

    @Override
    @Transactional
    public RetirementPension changeInvestmentInstruction(ChangeInvestmentInstructionCommand command) {
        RetirementPension pension = loadOwned(command.subscriberId(), command.pensionId());
        pension.changeInvestmentInstruction(command.productName(), command.rate());
        return saveRetirementPensionPort.save(pension);
    }

    @Override
    @Transactional
    public RetirementPension startBenefit(StartBenefitCommand command) {
        RetirementPension pension = loadOwned(command.subscriberId(), command.pensionId());
        pension.startBenefit(command.benefitType(), today());
        return saveRetirementPensionPort.save(pension);
    }

    /**
     * 퇴직급여 지급 — <b>운영자 경로</b>(ADMIN/MANAGER). 지급 대상은 언제나 계약에 적힌 가입자이며,
     * 금액은 적립금 잔액을 넘을 수 없다(애그리게이트가 강제). 두 제약 때문에 운영자가 남의 급여를
     * 자기 앞으로 돌리거나 없는 돈을 지급할 수 없다.
     */
    @Override
    @Transactional
    public RetirementPension payBenefit(PayBenefitCommand command) {
        RetirementPension pension = load(command.pensionId());
        PensionTransaction tx = pension.payBenefit(command.amount(), today());
        RetirementPension saved = saveRetirementPensionPort.save(pension);
        post(tx.getAmount(), () -> AccountEntry.pensionBenefitPaid(
                pension.getSubscriberId(), pensionRef(command.pensionId()), tx.getSeq(), tx.getAmount()));
        return saved;
    }

    @Override
    @Transactional
    public RetirementPension withdrawMidway(WithdrawMidwayCommand command) {
        RetirementPension pension = loadOwned(command.subscriberId(), command.pensionId());
        PensionTransaction tx = pension.withdrawMidway(command.amount(), command.reason(), today());
        RetirementPension saved = saveRetirementPensionPort.save(pension);
        post(tx.getAmount(), () -> AccountEntry.pensionMidWithdrawn(
                pension.getSubscriberId(), pensionRef(command.pensionId()), tx.getSeq(), tx.getAmount()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public RetirementPension get(String subscriberId, Long pensionId) {
        return loadOwned(subscriberId, pensionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetirementPension> listMine(String subscriberId) {
        return loadRetirementPensionPort.findBySubscriberId(subscriberId);
    }

    /** 계약을 읽고 <b>즉시</b> 소유자를 대조한다 — 이 순서가 IDOR 방어의 전부다(가입자 경로 전용). */
    private RetirementPension loadOwned(String subscriberId, Long pensionId) {
        RetirementPension pension = load(pensionId);
        if (!pension.isOwnedBy(subscriberId)) {
            throw new PensionAccessDeniedException(pensionId);
        }
        return pension;
    }

    /**
     * 소유자 대조 없이 계약을 읽는다 — <b>운영자 경로 전용</b>(이자 확정·급여 지급).
     * 가입자 경로에서는 절대 쓰지 않는다. 그쪽은 {@link #loadOwned(String, Long)} 만 통과한다.
     */
    private RetirementPension load(Long pensionId) {
        return loadRetirementPensionPort.findById(pensionId)
                .orElseThrow(() -> new PensionNotFoundException(pensionId));
    }

    /**
     * 금액이 양수일 때만 GL 로 전기한다. {@code AccountEntry} 팩토리는 0·음수를 예외로 거절하므로
     * 전표를 <b>만들기 전에</b> 걸러야 한다 — 그래서 팩토리 호출을 지연 평가(Supplier)로 감싼다.
     * (도메인이 이미 원 단위 양수를 강제하지만, GL 경계에서 한 번 더 닫아두는 쪽이 싸다.)
     */
    private void post(BigDecimal amount, Supplier<AccountEntry> entry) {
        if (amount != null && amount.signum() > 0) {
            recordAccountEntryUseCase.record(entry.get());
        }
    }

    /** GL 전표 자연키의 pensionId 부분 — 팩토리가 {@code RP-{pensionId}-{seq}} 로 조립한다. */
    private static String pensionRef(Long pensionId) {
        return String.valueOf(pensionId);
    }

    /** 업무 발생일 — 서버 시계만이 정한다(요청 본문에서 날짜를 받지 않는 이유). */
    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
