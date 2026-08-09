package github.lms.lemuel.account.banking.savings.domain;

import github.lms.lemuel.account.banking.savings.domain.exception.DuplicateInstallmentRoundException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidInstallmentAmountException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidInstallmentRoundException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidSavingsTermsException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAccessDeniedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAlreadyClosedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 적금(積金) 계약 애그리거트 — 순수 POJO(Spring·JPA·Lombok 의존 0).
 *
 * <p>자식 {@link SavingsInstallment}(회차)를 소유하며, 회차 추가·해지·이자 확정의 모든 불변식이
 * 이 클래스 안에서 닫힌다. 응용 계층은 이 애그리거트를 불러 메서드를 호출하고 저장할 뿐,
 * 어떤 검증도 자기 손으로 하지 않는다.
 *
 * <h3>FIXED vs FLEXIBLE</h3>
 * <ul>
 *   <li>{@link SavingsType#FIXED}(정액적립식·정기적금): 매 회차 {@code monthlyAmount} 와
 *       <b>정확히 같은 금액</b>만 받는다. {@code paymentLimit} 는 개념적으로 존재할 수 없다(약정액이 곧 한도).</li>
 *   <li>{@link SavingsType#FLEXIBLE}(자유적립식): 회차 금액이 자유. {@code monthlyAmount} 는 존재할 수 없고,
 *       {@code paymentLimit} 가 있으면 회차당 그 금액까지만 받는다.</li>
 * </ul>
 * 두 필드를 상품 유형과 어긋나게 채워 넣는 것 자체를 {@link #open} 이 거절한다 — "쓰이지 않는 값이
 * 조용히 들어와 있는" 상태가 나중에 잘못된 검증 분기를 타게 만드는 전형적 결함이기 때문이다.
 *
 * <h3>연체(연체일) 처리 결정</h3>
 * 회차 납입기일은 {@code openedOn.plusMonths(round - 1)} 이다. 기일을 넘겨 내면 그 회차에
 * {@code overdueDays} 가 기록되지만, <b>만기일은 밀지 않는다</b>. 대신 그 회차의 예치일수
 * (paidOn → 만기일)가 줄어들어 <b>그 회차의 이자만 자연스럽게 감소</b>한다.
 *
 * <p>만기를 미루는(만기이연) 방식도 실무에 있으나 여기서는 택하지 않았다. 만기를 밀면
 * (1) 계약 종료일이 회차 납입 이력에 따라 계속 변해 만기 통지·정산 배치가 계약별로 갈라지고,
 * (2) 다른 정상 회차의 이자까지 함께 늘어나 "늦게 낸 사람이 이득"이 되는 역전이 생긴다.
 * 실제 납입일을 그대로 이자 기산일로 쓰는 쪽이 계약 종료일을 고정하면서 연체 불이익을
 * 정확히 해당 회차에만 국한시킨다.
 *
 * <h3>이자·지급액</h3>
 * 이자는 주기 accrual 없이 <b>해지 시점에 일괄 확정</b>한다(계정계 수신상품 공통 규약).
 * {@code payoutAmount = Σ 납입액 + settledInterest} 이며, 이 값이 그대로 {@code savingsClosed}
 * 전표의 금액이 되어 수신부채를 0 으로 닫는다.
 */
public class InstallmentSavings {

    private final Long id;
    private final String depositorId;
    private final String productName;
    private final SavingsType savingsType;
    private final BigDecimal monthlyAmount;
    private final BigDecimal paymentLimit;
    private final BigDecimal annualRate;
    private final BigDecimal earlyTerminationRate;
    private final int termMonths;
    private final LocalDate openedOn;
    private final LocalDate maturityDate;
    private final List<SavingsInstallment> installments;

    private SavingsStatus status;
    private LocalDate closedOn;
    private BigDecimal settledInterest;
    private BigDecimal payoutAmount;

    private InstallmentSavings(Long id, String depositorId, String productName, SavingsType savingsType,
                               BigDecimal monthlyAmount, BigDecimal paymentLimit,
                               BigDecimal annualRate, BigDecimal earlyTerminationRate,
                               int termMonths, LocalDate openedOn, LocalDate maturityDate,
                               SavingsStatus status, LocalDate closedOn,
                               BigDecimal settledInterest, BigDecimal payoutAmount,
                               List<SavingsInstallment> installments) {
        this.id = id;
        this.depositorId = depositorId;
        this.productName = productName;
        this.savingsType = savingsType;
        this.monthlyAmount = monthlyAmount;
        this.paymentLimit = paymentLimit;
        this.annualRate = annualRate;
        this.earlyTerminationRate = earlyTerminationRate;
        this.termMonths = termMonths;
        this.openedOn = openedOn;
        this.maturityDate = maturityDate;
        this.status = status;
        this.closedOn = closedOn;
        this.settledInterest = settledInterest;
        this.payoutAmount = payoutAmount;
        // 방어적 복사 — 생성자에 넘어온 목록을 그대로 들고 있으면 호출자가 나중에 회차를 끼워 넣을 수 있다.
        this.installments = new ArrayList<>(installments);
    }

    /**
     * 신규 계약 개설. 상품 유형과 금액 필드의 정합, 기간·이율 범위를 여기서 전부 닫는다.
     *
     * @param monthlyAmount        FIXED 필수(양수) / FLEXIBLE 이면 반드시 null
     * @param paymentLimit         FLEXIBLE 선택(양수) / FIXED 이면 반드시 null
     * @param annualRate           약정 연이율 — [0, 1) (0.035 = 연 3.5%)
     * @param earlyTerminationRate 중도해지 연이율 — [0, 1)
     */
    public static InstallmentSavings open(String depositorId, String productName, SavingsType savingsType,
                                          BigDecimal monthlyAmount, BigDecimal paymentLimit,
                                          BigDecimal annualRate, BigDecimal earlyTerminationRate,
                                          int termMonths, LocalDate openedOn) {
        requireText(depositorId, "예금주 식별자");
        requireText(productName, "상품명");
        if (savingsType == null) {
            throw new InvalidSavingsTermsException("적립 방식(savingsType)은 필수입니다");
        }
        if (openedOn == null) {
            throw new InvalidSavingsTermsException("개설일(openedOn)은 필수입니다");
        }
        if (termMonths <= 0) {
            throw new InvalidSavingsTermsException("계약 기간(개월)은 1 이상이어야 합니다: " + termMonths);
        }
        requireRate(annualRate, "약정이율");
        requireRate(earlyTerminationRate, "중도해지이율");

        if (savingsType == SavingsType.FIXED) {
            if (monthlyAmount == null || monthlyAmount.signum() <= 0) {
                throw new InvalidSavingsTermsException(
                        "정액적립식은 월 약정액이 양수여야 합니다: " + monthlyAmount);
            }
            if (paymentLimit != null) {
                // 약정액이 곧 회차 금액이므로 별도 한도는 의미가 없다 — 조용히 무시하지 않고 거절한다.
                throw new InvalidSavingsTermsException("정액적립식에는 회차 한도(paymentLimit)를 둘 수 없습니다");
            }
        } else {
            if (monthlyAmount != null) {
                throw new InvalidSavingsTermsException("자유적립식에는 월 약정액(monthlyAmount)을 둘 수 없습니다");
            }
            if (paymentLimit != null && paymentLimit.signum() <= 0) {
                throw new InvalidSavingsTermsException("회차 한도는 양수여야 합니다: " + paymentLimit);
            }
        }

        return new InstallmentSavings(null, depositorId, productName, savingsType,
                monthlyAmount, paymentLimit, annualRate, earlyTerminationRate,
                termMonths, openedOn, openedOn.plusMonths(termMonths),
                SavingsStatus.ACTIVE, null, null, null, List.of());
    }

    /** DB 복원 — 이미 검증을 통과해 저장된 상태이므로 불변식을 재검증하지 않는다. */
    public static InstallmentSavings reconstitute(Long id, String depositorId, String productName,
                                                  SavingsType savingsType, BigDecimal monthlyAmount,
                                                  BigDecimal paymentLimit, BigDecimal annualRate,
                                                  BigDecimal earlyTerminationRate, int termMonths,
                                                  LocalDate openedOn, LocalDate maturityDate,
                                                  SavingsStatus status, LocalDate closedOn,
                                                  BigDecimal settledInterest, BigDecimal payoutAmount,
                                                  List<SavingsInstallment> installments) {
        return new InstallmentSavings(id, depositorId, productName, savingsType, monthlyAmount,
                paymentLimit, annualRate, earlyTerminationRate, termMonths, openedOn, maturityDate,
                status, closedOn, settledInterest, payoutAmount,
                installments == null ? List.of() : installments);
    }

    /**
     * 회차 납입.
     *
     * <p>기일(dueDate)은 {@code openedOn.plusMonths(round - 1)} 이다 — 1회차는 개설일 당일이 기일이다.
     * {@code paidOn} 이 기일을 넘으면 연체일수가 그 회차에 기록되지만 만기일은 그대로다(클래스 주석 참조).
     *
     * @return 방금 추가된 회차
     */
    public SavingsInstallment pay(int round, BigDecimal amount, LocalDate paidOn) {
        requireActive();
        if (round < 1 || round > termMonths) {
            throw new InvalidInstallmentRoundException(round, termMonths);
        }
        if (paidOn == null) {
            throw new InvalidSavingsTermsException("납입일(paidOn)은 필수입니다");
        }
        if (paidOn.isBefore(openedOn)) {
            throw new InvalidSavingsTermsException(
                    "납입일은 개설일 이전일 수 없습니다: paidOn=" + paidOn + ", openedOn=" + openedOn);
        }
        if (hasRound(round)) {
            // 멱등은 GL 자연키(SV-{savingsId}-{round})와 uq_savings_installment_round 가 최종 보장하지만,
            // 도메인이 먼저 거절하지 않으면 애그리거트의 납입 누계·이자 대상이 그 전에 이미 오염된다.
            throw new DuplicateInstallmentRoundException(round);
        }
        requirePayableAmount(amount);

        SavingsInstallment installment =
                SavingsInstallment.paid(round, amount, openedOn.plusMonths(round - 1L), paidOn);
        installments.add(installment);
        return installment;
    }

    /**
     * 만기 해지 — <b>약정이율</b>로 이자를 확정하고 이자 기산 종료일은 <b>만기일</b>이다.
     * 만기 이후 언제 해지하더라도 만기일 이후 기간에는 이자가 붙지 않는다(만기후 이율 미도입).
     */
    public void closeOnMaturity(LocalDate closedOn) {
        requireActive();
        requireCloseDate(closedOn);
        if (closedOn.isBefore(maturityDate)) {
            throw new InvalidSavingsTermsException(
                    "만기 해지는 만기일 이후여야 합니다: closedOn=" + closedOn + ", maturityDate=" + maturityDate);
        }
        settle(annualRate, maturityDate, closedOn);
    }

    /**
     * 중도 해지 — <b>중도해지이율</b>을 <b>모든 회차에</b> 적용하고 이자 기산 종료일은 해지일이다.
     * 회차별로 이율을 섞지 않는다(약정이율 구간 + 중도이율 구간 분할 없음) — 중도해지는 계약 전체를
     * 되돌리는 사건이라 회차마다 다른 이율을 주면 "먼저 낸 회차만 우대"라는 근거 없는 차등이 생긴다.
     */
    public void closeEarly(LocalDate closedOn) {
        requireActive();
        requireCloseDate(closedOn);
        if (!closedOn.isBefore(maturityDate)) {
            throw new InvalidSavingsTermsException(
                    "중도 해지는 만기일 이전이어야 합니다: closedOn=" + closedOn + ", maturityDate=" + maturityDate);
        }
        settle(earlyTerminationRate, closedOn, closedOn);
    }

    /** 예금주 본인 확인 — 아니면 403. IDOR 차단은 응용 계층이 아니라 여기서 한 번만 정의한다. */
    public void assertOwnedBy(String candidateDepositorId) {
        if (!depositorId.equals(candidateDepositorId)) {
            throw new SavingsAccessDeniedException(id);
        }
    }

    /** 납입 누계 — 지급액의 원금 부분. */
    public BigDecimal totalPaidAmount() {
        return installments.stream()
                .map(SavingsInstallment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 연체 납입이 하나라도 있는지. */
    public boolean hasOverdueInstallment() {
        return installments.stream().anyMatch(SavingsInstallment::isOverdue);
    }

    /** 이미 납입된 회차인지. */
    public boolean hasRound(int round) {
        return installments.stream().anyMatch(i -> i.getRound() == round);
    }

    // ── 내부 규칙 ─────────────────────────────────────────────────────────────

    private void settle(BigDecimal rate, LocalDate interestEndDate, LocalDate closedOn) {
        this.settledInterest = InstallmentSavingsInterest.calculate(installments, rate, interestEndDate);
        this.payoutAmount = totalPaidAmount().add(this.settledInterest);
        this.closedOn = closedOn;
        this.status = SavingsStatus.CLOSED;
    }

    private void requireActive() {
        if (status != SavingsStatus.ACTIVE) {
            throw new SavingsAlreadyClosedException(id);
        }
    }

    private void requireCloseDate(LocalDate candidate) {
        if (candidate == null) {
            throw new InvalidSavingsTermsException("해지일(closedOn)은 필수입니다");
        }
        if (candidate.isBefore(openedOn)) {
            throw new InvalidSavingsTermsException(
                    "해지일은 개설일 이전일 수 없습니다: closedOn=" + candidate + ", openedOn=" + openedOn);
        }
    }

    private void requirePayableAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidInstallmentAmountException(amount, "양수여야 합니다");
        }
        if (savingsType == SavingsType.FIXED) {
            // compareTo — 100 과 100.00 은 같은 금액이다. equals 를 쓰면 scale 차이로 오탐한다.
            if (monthlyAmount.compareTo(amount) != 0) {
                throw new InvalidInstallmentAmountException(amount,
                        "정액적립식은 월 약정액 " + monthlyAmount + " 와 정확히 같아야 합니다");
            }
            return;
        }
        if (paymentLimit != null && amount.compareTo(paymentLimit) > 0) {
            throw new InvalidInstallmentAmountException(amount, "회차 한도 " + paymentLimit + " 초과");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new InvalidSavingsTermsException(label + "은(는) 필수입니다");
        }
    }

    private static void requireRate(BigDecimal rate, String label) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new InvalidSavingsTermsException(label + "은 0 이상 1 미만이어야 합니다: " + rate);
        }
    }

    // ── getter (setter 없음 — 상태 변경은 pay/close 로만) ─────────────────────

    public Long getId() { return id; }
    public String getDepositorId() { return depositorId; }
    public String getProductName() { return productName; }
    public SavingsType getSavingsType() { return savingsType; }
    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public BigDecimal getPaymentLimit() { return paymentLimit; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public BigDecimal getEarlyTerminationRate() { return earlyTerminationRate; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public SavingsStatus getStatus() { return status; }
    public LocalDate getClosedOn() { return closedOn; }
    public BigDecimal getSettledInterest() { return settledInterest; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }

    /** 회차 목록(회차 오름차순, 수정 불가 사본) — 내부 리스트를 절대 노출하지 않는다. */
    public List<SavingsInstallment> getInstallments() {
        return installments.stream()
                .sorted(Comparator.comparingInt(SavingsInstallment::getRound))
                .toList();
    }
}
