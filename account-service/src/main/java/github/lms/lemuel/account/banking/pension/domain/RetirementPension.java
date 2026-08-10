package github.lms.lemuel.account.banking.pension.domain;

import github.lms.lemuel.account.banking.pension.domain.exception.BenefitEligibilityNotMetException;
import github.lms.lemuel.account.banking.pension.domain.exception.ContributionSourceNotAllowedException;
import github.lms.lemuel.account.banking.pension.domain.exception.EmployerNameNotAllowedException;
import github.lms.lemuel.account.banking.pension.domain.exception.EmployerNameRequiredException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidBirthDateException;
import github.lms.lemuel.account.banking.pension.domain.exception.MidWithdrawalNotPermittedException;
import github.lms.lemuel.account.banking.pension.domain.exception.NonPositivePensionAmountException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionAmountExceedsAccumulatedException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionStatusNotAllowedException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 퇴직연금 계약 애그리게이트 (순수 POJO — Spring/JPA/Lombok 무의존).
 *
 * <p>제도({@link PensionScheme})가 규칙의 축이다. 부담금 주체 허용 조합·사업장 필수 여부·중도인출
 * 가능 여부가 모두 제도 상수에서 나오므로, 이 클래스는 제도별 if 분기를 갖지 않는다.
 *
 * <p><b>금액 규약</b>: 모든 금액은 원 단위(소수 0자리, HALF_UP)로 정규화해 보관한다. 계정계 전표
 * ({@code AccountEntry})는 소수 2자리를 허용하지만 반올림을 거부하므로, 원 단위로 미리 닫아두는 쪽이
 * 서브원장↔GL 드리프트를 원천 차단한다. 나눗셈은 언제나 스케일·반올림을 명시한다.
 *
 * <p><b>seq 규약</b>: {@code nextSeq} 는 계약당 단조 증가하는 거래 일련번호다. 모든 상태 변경
 * 메서드는 방금 만든 {@link PensionTransaction} 을 반환하는데, 애플리케이션 서비스가 그 seq 를
 * GL 전표 자연키({@code RP-{pensionId}-{seq}})로 그대로 넘겨야 하기 때문이다 — 서비스가 seq 를
 * 스스로 세지 않게 하는 것이 이 반환값의 목적이다.
 *
 * <h2>클라이언트가 정할 수 없는 것</h2>
 * <ul>
 *   <li><b>이자 금액</b> — {@link #settleInterest(LocalDate)} 는 금액을 인자로 받지 않는다.
 *       계약 기준 이율과 적립금·경과일수로 애그리게이트가 직접 산출한다. 금액을 요청에서 받으면
 *       인증된 가입자가 자기 적립금을 임의로 부풀릴 수 있고, 그대로 GL 로 전기된다.</li>
 *   <li><b>만 나이·가입기간</b> — {@link #startBenefit(BenefitType, LocalDate)} 는 둘 다 인자로 받지
 *       않는다. 가입 시 기록한 {@code birthDate} 와 {@code openedOn} 에서 파생한다. 요청에서 받으면
 *       법정 수급요건(만 55세·가입 10년) 검사가 아무것도 막지 못한다.</li>
 *   <li><b>날짜</b> — 모든 날짜 인자는 응용 서비스가 주입한 {@code Clock} 에서 나온다.
 *       도메인은 순수성을 위해 인자로만 받는다(테스트 결정성).</li>
 * </ul>
 */
public class RetirementPension {

    /** 원 단위(₩) — 금액은 소수 0자리로 닫는다. */
    private static final int WON_SCALE = 0;

    /** 나눗셈 중간 스케일 — 무반올림 divide 금지 규약에 따라 항상 명시한다. */
    private static final int DIVISION_SCALE = 10;

    /** ACT/365 — 분모 고정(윤년 366 으로 바꾸지 않는다). 형제 수신 상품과 동일 규약. */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    /** 근로기준법상 취업 최저연령 — 가입 시점에 이 나이 미만이면 계약이 성립하지 않는다. */
    static final int MINIMUM_SUBSCRIPTION_AGE = 15;

    private final Long id;
    private final String subscriberId;
    private final PensionScheme scheme;
    private final String employerName;
    private final LocalDate birthDate;
    private final BigDecimal annualRate;
    private final LocalDate openedOn;
    private final List<PensionTransaction> transactions;

    private PrincipalGuaranteedProduct principalGuaranteedProduct;
    private PensionStatus status;
    private LocalDate lastInterestSettledOn;
    private LocalDate benefitStartedOn;
    private BenefitType benefitType;
    private BigDecimal accumulatedAmount;
    private long nextSeq;

    private RetirementPension(Long id, String subscriberId, PensionScheme scheme, String employerName,
                              LocalDate birthDate, BigDecimal annualRate,
                              PrincipalGuaranteedProduct principalGuaranteedProduct,
                              PensionStatus status, LocalDate openedOn, LocalDate lastInterestSettledOn,
                              LocalDate benefitStartedOn, BenefitType benefitType,
                              BigDecimal accumulatedAmount, long nextSeq,
                              List<PensionTransaction> transactions) {
        this.id = id;
        this.subscriberId = subscriberId;
        this.scheme = scheme;
        this.employerName = employerName;
        this.birthDate = birthDate;
        this.annualRate = annualRate;
        this.principalGuaranteedProduct = principalGuaranteedProduct;
        this.status = status;
        this.openedOn = openedOn;
        this.lastInterestSettledOn = lastInterestSettledOn;
        this.benefitStartedOn = benefitStartedOn;
        this.benefitType = benefitType;
        this.accumulatedAmount = accumulatedAmount;
        this.nextSeq = nextSeq;
        this.transactions = new ArrayList<>(transactions);
    }

    /**
     * 신규 가입. 사업장명은 제도가 요구하면 필수, 요구하지 않으면 <b>거부</b>한다(IRP 에 사업장을
     * 붙이면 그 자체로 잘못된 계약이므로 조용히 버리지 않는다).
     *
     * @param birthDate  생년월일 — 수급 개시 연령 판정의 유일한 근거. 가입 시점에 만 15세 이상이어야
     *                   하며 가입일보다 미래일 수 없다.
     *                   신뢰 한계는 {@link #startBenefit(BenefitType, LocalDate)} 참고.
     * @param annualRate 원리금보장 운용이율 — {@code [0,1)} 소수(연 3.5% = 0.035)
     */
    public static RetirementPension open(String subscriberId, PensionScheme scheme, String employerName,
                                         LocalDate birthDate, BigDecimal annualRate, LocalDate openedOn,
                                         String productName, BigDecimal productRate) {
        String normalizedEmployer = normalizeEmployerName(employerName);
        if (scheme.requiresEmployerName() && normalizedEmployer == null) {
            throw new EmployerNameRequiredException(scheme);
        }
        if (!scheme.requiresEmployerName() && normalizedEmployer != null) {
            throw new EmployerNameNotAllowedException(scheme);
        }
        return new RetirementPension(null, subscriberId, scheme, normalizedEmployer,
                requireEligibleBirthDate(birthDate, openedOn),
                PrincipalGuaranteedProduct.normalizeRate(annualRate),
                new PrincipalGuaranteedProduct(productName, productRate),
                PensionStatus.ACCUMULATING, openedOn, null, null, null,
                BigDecimal.ZERO.setScale(WON_SCALE), 1L, List.of());
    }

    /** 영속 상태에서 도메인으로 복원 (id·거래이력 포함). */
    public static RetirementPension reconstitute(Long id, String subscriberId, PensionScheme scheme,
                                                 String employerName, LocalDate birthDate, BigDecimal annualRate,
                                                 PrincipalGuaranteedProduct principalGuaranteedProduct,
                                                 PensionStatus status, LocalDate openedOn,
                                                 LocalDate lastInterestSettledOn, LocalDate benefitStartedOn,
                                                 BenefitType benefitType, BigDecimal accumulatedAmount, long nextSeq,
                                                 List<PensionTransaction> transactions) {
        return new RetirementPension(id, subscriberId, scheme, employerName, birthDate, annualRate,
                principalGuaranteedProduct, status, openedOn, lastInterestSettledOn, benefitStartedOn, benefitType,
                accumulatedAmount, nextSeq, transactions == null ? List.of() : transactions);
    }

    /**
     * 부담금 납입 — 적립 중에만, 제도가 허용하는 주체만.
     *
     * @return 방금 적재된 거래(서비스가 이 seq 로 GL 전표를 만든다)
     */
    public PensionTransaction contribute(BigDecimal amount, ContributionSource source, LocalDate on) {
        requireStatus(PensionStatus.ACCUMULATING, "부담금 납입");
        if (!scheme.permitsContributionFrom(source)) {
            throw new ContributionSourceNotAllowedException(scheme, source);
        }
        BigDecimal won = requirePositiveWon(amount);
        accumulatedAmount = accumulatedAmount.add(won);
        return append(PensionTransaction.contribution(nextSeq, won, source, on));
    }

    /**
     * 운용수익(원리금보장 이자) 확정 — 적립 중·수급 중 모두 가능하다. 수급이 시작돼도 잔여 적립금은
     * 계속 운용되므로 이자가 붙는다.
     *
     * <p><b>금액을 받지 않는다.</b> 직전 이자 확정일(없으면 개설일)부터 {@code on} 까지의 실경과일수를
     * ACT/365 단리로 환산해 애그리게이트가 산출한다({@link #accruedInterest(LocalDate)}). 클라이언트가
     * 금액을 보낼 수 있으면 인증된 가입자가 자기 적립금을 임의로 늘리고 그것이 그대로
     * {@code DR 이자비용 / CR 수신부채} 로 기표된다 — 사실상 무제한 발권이다.
     *
     * <p>산출 이자가 0원이면 거래를 만들지 않고 <b>확정일 마커도 옮기지 않는다</b>. 마커를 옮기면
     * 반올림으로 0이 된 그 구간의 경과일수가 영구히 소멸해 가입자가 조용히 손해를 본다.
     *
     * @return 확정된 거래. 산출 이자가 0원이면 {@link Optional#empty()}
     */
    public Optional<PensionTransaction> settleInterest(LocalDate on) {
        requireNotClosed("운용수익 확정");
        BigDecimal won = accruedInterest(on);
        if (won.signum() <= 0) {
            return Optional.empty();
        }
        accumulatedAmount = accumulatedAmount.add(won);
        lastInterestSettledOn = on;
        return Optional.of(append(PensionTransaction.interest(nextSeq, won, on)));
    }

    /**
     * 미확정 운용수익 — 직전 이자 확정일(없으면 개설일)부터 {@code on} 까지의 ACT/365 단리, 원 단위 HALF_UP.
     *
     * <p>{@code 적립금 × 계약이율 × 경과일수 / 365}. 나눗셈은 스케일 10·HALF_UP 을
     * 명시한다 — 무반올림 divide 는 1/365 같은 무한소수에서 {@code ArithmeticException} 으로 터진다.
     * 경과일수가 0 이하거나 이율이 0 이거나 적립금이 0 이면 0을 돌려준다(양수 금액만 허용하는 GL 팩토리 보호).
     */
    public BigDecimal accruedInterest(LocalDate on) {
        long days = ChronoUnit.DAYS.between(interestAccrualStart(), on);
        if (days <= 0 || annualRate.signum() == 0 || accumulatedAmount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(WON_SCALE);
        }
        return accumulatedAmount.multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_IN_YEAR, DIVISION_SCALE, RoundingMode.HALF_UP)
                .setScale(WON_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 운용지시 변경 — 종료되지 않은 계약이면 언제든 가능하다(수급 중에도 잔여 적립금 운용은 이어진다).
     * 계약 기준 이율({@link #getAnnualRate()})은 개설 시점에 확정된 값이라 여기서 바뀌지 않는다.
     */
    public void changeInvestmentInstruction(String productName, BigDecimal rate) {
        requireNotClosed("운용지시 변경");
        this.principalGuaranteedProduct = new PrincipalGuaranteedProduct(productName, rate);
    }

    /**
     * 수급 개시 — 적립 중 계약에만, 수급 형태별 요건(연금: 만 55세 + 가입기간 10년 / 일시금: 만 55세)을
     * 충족해야 한다. 경계값은 "이상"이므로 54세는 불가, 55세는 가능하다.
     *
     * <p><b>만 나이와 가입기간을 인자로 받지 않는다.</b> 각각 가입 시 기록한 {@code birthDate} 와
     * {@code openedOn} 에서 {@code on} 기준으로 파생한다. 이 둘을 요청에서 받으면 아무나
     * {@code age=60, subscribedYears=20} 을 보내 법정 요건을 그대로 통과시킬 수 있어, 이 검사 전체가
     * 장식이 된다.
     *
     * <p><b>신뢰 한계</b>: account-service 는 DB-per-service 라 사용자 서비스의 프로필을 조회할 수 없고,
     * 따라서 {@code birthDate} 는 <b>가입 시 자기신고 값</b>이다. 다만 "가입 때 한 번 기록되어 감사에
     * 남는 사실"과 "수급 신청마다 자유 입력"은 위험도가 전혀 다르다 — 전자는 계약 이력·전표와 대조해
     * 사후 적발이 가능하다. 검증된 신원 소스(사용자 서비스의 실명확인 생년월일)가 붙으면 가입 경로의
     * 자기신고 값을 그것으로 대체해야 한다.
     */
    public void startBenefit(BenefitType type, LocalDate on) {
        requireStatus(PensionStatus.ACCUMULATING, "수급 개시");
        int age = ageOn(on);
        int subscribedYears = subscribedYearsOn(on);
        if (!type.isEligible(age, subscribedYears)) {
            throw new BenefitEligibilityNotMetException(type, age, subscribedYears);
        }
        this.status = PensionStatus.RECEIVING;
        this.benefitStartedOn = on;
        this.benefitType = type;
    }

    /** {@code on} 시점의 만 나이 — 생일이 지나야 한 살 오른다({@link Period} 규칙). */
    public int ageOn(LocalDate on) {
        return Period.between(birthDate, on).getYears();
    }

    /** {@code on} 시점의 가입기간(년) — 만 단위 절사라 9년 11개월은 9년이다. */
    public int subscribedYearsOn(LocalDate on) {
        return Math.toIntExact(ChronoUnit.YEARS.between(openedOn, on));
    }

    /**
     * 퇴직급여 지급 — 수급 중에만, 적립금 잔액 한도 내에서. 잔액이 0 이 되는 순간 계약을 닫는다
     * (연금 분할 수령의 마지막 회차, 또는 일시금 전액 지급이 그 지점이다).
     */
    public PensionTransaction payBenefit(BigDecimal amount, LocalDate on) {
        requireStatus(PensionStatus.RECEIVING, "퇴직급여 지급");
        BigDecimal won = requireWithinAccumulated(amount);
        accumulatedAmount = accumulatedAmount.subtract(won);
        PensionTransaction tx = append(PensionTransaction.benefit(nextSeq, won, on));
        if (accumulatedAmount.signum() == 0) {
            this.status = PensionStatus.CLOSED;
        }
        return tx;
    }

    /**
     * 법정 사유 중도인출 — 적립 중에만, 제도가 허용할 때만({@code DB} 형은 불가), 적립금 한도 내에서.
     *
     * <p>인출로 잔액이 0 이 되어도 계약을 닫지 않는다. 중도인출은 해지가 아니라 적립 중 일부 인출이며,
     * 이후 부담금 납입이 계속될 수 있기 때문이다(해지는 수급 개시 → 전액 지급 경로다).
     */
    public PensionTransaction withdrawMidway(BigDecimal amount, MidWithdrawalReason reason, LocalDate on) {
        requireStatus(PensionStatus.ACCUMULATING, "중도인출");
        if (!scheme.permitsMidWithdrawal()) {
            throw new MidWithdrawalNotPermittedException(scheme);
        }
        BigDecimal won = requireWithinAccumulated(amount);
        accumulatedAmount = accumulatedAmount.subtract(won);
        return append(PensionTransaction.midWithdrawal(nextSeq, won, reason, on));
    }

    /** 이 계약의 소유자인가 — IDOR 판정의 단일 출처(비교 규칙을 서비스에 흩지 않는다). */
    public boolean isOwnedBy(String candidateSubscriberId) {
        return subscriberId != null && subscriberId.equals(candidateSubscriberId);
    }

    /** 이자 기산일 — 직전 확정일이 있으면 그 날, 없으면 개설일. */
    private LocalDate interestAccrualStart() {
        return lastInterestSettledOn != null ? lastInterestSettledOn : openedOn;
    }

    private PensionTransaction append(PensionTransaction transaction) {
        transactions.add(transaction);
        nextSeq++;
        return transaction;
    }

    private void requireStatus(PensionStatus required, String operation) {
        if (status != required) {
            throw new PensionStatusNotAllowedException(status, operation);
        }
    }

    private void requireNotClosed(String operation) {
        if (status == PensionStatus.CLOSED) {
            throw new PensionStatusNotAllowedException(status, operation);
        }
    }

    /** 금액을 원 단위로 정규화하고 양수를 강제한다 — 반올림 결과가 0 인 금액도 거절한다. */
    private static BigDecimal requirePositiveWon(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new NonPositivePensionAmountException(amount);
        }
        BigDecimal won = amount.setScale(WON_SCALE, RoundingMode.HALF_UP);
        if (won.signum() <= 0) {
            throw new NonPositivePensionAmountException(amount);
        }
        return won;
    }

    private BigDecimal requireWithinAccumulated(BigDecimal amount) {
        BigDecimal won = requirePositiveWon(amount);
        if (won.compareTo(accumulatedAmount) > 0) {
            throw new PensionAmountExceedsAccumulatedException(won, accumulatedAmount);
        }
        return won;
    }

    /** 생년월일 불변식 — 가입일보다 미래일 수 없고, 가입 시점에 만 15세 이상. */
    private static LocalDate requireEligibleBirthDate(LocalDate birthDate, LocalDate openedOn) {
        if (birthDate == null || birthDate.isAfter(openedOn)
                || Period.between(birthDate, openedOn).getYears() < MINIMUM_SUBSCRIPTION_AGE) {
            throw new InvalidBirthDateException(birthDate);
        }
        return birthDate;
    }

    private static String normalizeEmployerName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public Long getId() { return id; }
    public String getSubscriberId() { return subscriberId; }
    public PensionScheme getScheme() { return scheme; }
    public String getEmployerName() { return employerName; }
    public LocalDate getBirthDate() { return birthDate; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public PrincipalGuaranteedProduct getPrincipalGuaranteedProduct() { return principalGuaranteedProduct; }
    public PensionStatus getStatus() { return status; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getLastInterestSettledOn() { return lastInterestSettledOn; }
    public LocalDate getBenefitStartedOn() { return benefitStartedOn; }
    public BenefitType getBenefitType() { return benefitType; }
    public BigDecimal getAccumulatedAmount() { return accumulatedAmount; }
    public long getNextSeq() { return nextSeq; }

    /** 거래 이력(불변 뷰) — 애그리게이트 밖에서 컬렉션을 변경할 수 없다. */
    public List<PensionTransaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }
}
