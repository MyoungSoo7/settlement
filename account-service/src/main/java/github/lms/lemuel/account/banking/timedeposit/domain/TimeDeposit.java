package github.lms.lemuel.account.banking.timedeposit.domain;

import github.lms.lemuel.account.banking.timedeposit.domain.exception.InvalidTimeDepositCloseDateException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.InvalidTimeDepositTermsException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAlreadyClosedException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정기예금 계좌 애그리거트 (순수 POJO — Spring·JPA·Lombok 의존 0).
 *
 * <p><b>이자는 개설 시점에 확정하지 않는다.</b> 만기·중도해지 시점에 <b>단 한 번</b> 확정해
 * {@code settledInterest} 로 굳히고 원리금({@code payoutAmount = principal + settledInterest})을
 * 지급하며 계좌를 닫는다. 주기 accrual 테이블도, 이자 스케줄러도 두지 않는다 — 확정 시점이 하나뿐이면
 * 스케줄러 누락·중복 실행으로 이자가 새는 사고 자체가 생기지 않는다.
 *
 * <p>만기해지와 중도해지의 유일한 차이는 <b>적용 이율</b>이다({@code annualRate} vs
 * {@code earlyTerminationRate}). 산식·일수기준(ACT/365)·반올림은 {@link TimeDepositInterest} 한 곳에서
 * 공유하므로 두 경로가 갈라질 수 없다.
 *
 * <p>모든 필드가 {@code final} 이라 해지는 상태를 바꾸는 대신 <b>닫힌 새 인스턴스</b>를 만들어 돌려준다.
 * 세터가 없으니 "절반만 닫힌" 중간 상태가 존재할 수 없고, 해지 전 스냅샷이 호출자 손에 그대로 남는다.
 */
public class TimeDeposit {

    /** 원화 수신 — GL {@code numeric(19,2)} 과 동일한 최대 소수 자릿수. */
    private static final int MAX_MONEY_SCALE = 2;

    private final Long id;
    private final String depositorId;
    private final String productName;
    private final BigDecimal principal;
    private final BigDecimal annualRate;
    private final BigDecimal earlyTerminationRate;
    private final Compounding compounding;
    private final int termMonths;
    private final LocalDate openedOn;
    private final LocalDate maturityDate;
    private final TimeDepositStatus status;
    private final LocalDate closedOn;
    private final BigDecimal settledInterest;
    private final BigDecimal payoutAmount;

    private TimeDeposit(Long id, String depositorId, String productName, BigDecimal principal,
                        BigDecimal annualRate, BigDecimal earlyTerminationRate, Compounding compounding,
                        int termMonths, LocalDate openedOn, LocalDate maturityDate,
                        TimeDepositStatus status, LocalDate closedOn,
                        BigDecimal settledInterest, BigDecimal payoutAmount) {
        this.id = id;
        this.depositorId = depositorId;
        this.productName = productName;
        this.principal = principal;
        this.annualRate = annualRate;
        this.earlyTerminationRate = earlyTerminationRate;
        this.compounding = compounding;
        this.termMonths = termMonths;
        this.openedOn = openedOn;
        this.maturityDate = maturityDate;
        this.status = status;
        this.closedOn = closedOn;
        this.settledInterest = settledInterest;
        this.payoutAmount = payoutAmount;
    }

    /**
     * 신규 개설 — 상품 조건을 전수 검증하고 만기일을 확정한다.
     *
     * <p>만기일 = {@code openedOn.plusMonths(termMonths)}. {@link LocalDate#plusMonths} 의 월말 보정을
     * 그대로 쓴다(1/31 + 1개월 = 2/28) — 은행 관행과 일치하고, 직접 보정 로직을 두면 윤년에서 갈린다.
     *
     * @throws InvalidTimeDepositTermsException 원금 ≤ 0 / 원금 소수 3자리 이상 / 이율이 [0,1) 밖 /
     *                                          기간 ≤ 0 / 필수 값 누락
     */
    public static TimeDeposit open(String depositorId, String productName, BigDecimal principal,
                                   BigDecimal annualRate, BigDecimal earlyTerminationRate,
                                   Compounding compounding, int termMonths, LocalDate openedOn) {
        requireText(depositorId, "예금주 식별자");
        requireText(productName, "상품명");
        requirePositiveMoney(principal);
        requireRate(annualRate, "약정이율");
        requireRate(earlyTerminationRate, "중도해지이율");
        if (compounding == null) {
            throw new InvalidTimeDepositTermsException("이자 계산 방식은 필수입니다 (SIMPLE|MONTHLY_COMPOUND)");
        }
        if (termMonths <= 0) {
            throw new InvalidTimeDepositTermsException("예치 기간은 1개월 이상이어야 합니다: " + termMonths);
        }
        if (openedOn == null) {
            throw new InvalidTimeDepositTermsException("개설일은 필수입니다");
        }
        return new TimeDeposit(null, depositorId, productName, principal, annualRate, earlyTerminationRate,
                compounding, termMonths, openedOn, openedOn.plusMonths(termMonths),
                TimeDepositStatus.ACTIVE, null, null, null);
    }

    /**
     * 만기 해지 — 약정이율({@code annualRate}) 전액을 적용해 이자를 확정하고 원리금을 지급 대상으로 굳힌다.
     *
     * <p>해지일이 만기일보다 이른지 여부는 <b>도메인이 판정하지 않는다</b>. "만기해지냐 중도해지냐"는
     * 적용 이율을 고르는 정책 결정이고, 그 결정은 호출자(응용 서비스)가 만기일을 보고 내린다.
     * 도메인은 어떤 이율을 쓰라고 지시받은 대로 계산할 뿐이다.
     */
    public TimeDeposit closeOnMaturity(LocalDate closedOn) {
        return close(closedOn, annualRate);
    }

    /** 중도 해지 — 중도해지이율({@code earlyTerminationRate}) 로 실제 예치일수(ACT/365) 만큼만 이자를 준다. */
    public TimeDeposit closeEarly(LocalDate closedOn) {
        return close(closedOn, earlyTerminationRate);
    }

    private TimeDeposit close(LocalDate closingDate, BigDecimal appliedRate) {
        if (status == TimeDepositStatus.CLOSED) {
            throw new TimeDepositAlreadyClosedException(id);
        }
        if (closingDate == null || closingDate.isBefore(openedOn)) {
            throw new InvalidTimeDepositCloseDateException(openedOn, closingDate);
        }
        BigDecimal interest = TimeDepositInterest.accrued(principal, appliedRate, compounding, openedOn, closingDate);
        return new TimeDeposit(id, depositorId, productName, principal, annualRate, earlyTerminationRate,
                compounding, termMonths, openedOn, maturityDate,
                TimeDepositStatus.CLOSED, closingDate, interest, principal.add(interest));
    }

    /** 만기일이 지났는지 — 응용 서비스가 만기해지/중도해지 경로를 고를 때 쓰는 판정. */
    public boolean isMatured(LocalDate asOf) {
        return !asOf.isBefore(maturityDate);
    }

    public boolean isClosed() {
        return status == TimeDepositStatus.CLOSED;
    }

    /** 이 계좌가 주어진 예금주의 것인지 — IDOR 판정의 단일 출처. */
    public boolean ownedBy(String candidateDepositorId) {
        return depositorId.equals(candidateDepositorId);
    }

    /** 영속 상태에서 도메인으로 복원 (검증 없이 그대로 — 이미 개설 시점에 통과한 값이다). */
    public static TimeDeposit reconstitute(Long id, String depositorId, String productName, BigDecimal principal,
                                           BigDecimal annualRate, BigDecimal earlyTerminationRate,
                                           Compounding compounding, int termMonths, LocalDate openedOn,
                                           LocalDate maturityDate, TimeDepositStatus status, LocalDate closedOn,
                                           BigDecimal settledInterest, BigDecimal payoutAmount) {
        return new TimeDeposit(id, depositorId, productName, principal, annualRate, earlyTerminationRate,
                compounding, termMonths, openedOn, maturityDate, status, closedOn, settledInterest, payoutAmount);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new InvalidTimeDepositTermsException(label + "은(는) 필수입니다");
        }
    }

    private static void requirePositiveMoney(BigDecimal principal) {
        if (principal == null || principal.signum() <= 0) {
            throw new InvalidTimeDepositTermsException("예치 원금은 0보다 커야 합니다: " + principal);
        }
        // 조용히 반올림하지 않는다 — GL numeric(19,2) 로 내려가며 잘리면 수신부채가 1원씩 어긋난다
        if (principal.stripTrailingZeros().scale() > MAX_MONEY_SCALE) {
            throw new InvalidTimeDepositTermsException(
                    "예치 원금의 소수 자릿수는 " + MAX_MONEY_SCALE + " 이하여야 합니다: " + principal);
        }
    }

    private static void requireRate(BigDecimal rate, String label) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new InvalidTimeDepositTermsException(label + "은(는) 0 이상 1 미만이어야 합니다: " + rate);
        }
    }

    public Long getId() { return id; }
    public String getDepositorId() { return depositorId; }
    public String getProductName() { return productName; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public BigDecimal getEarlyTerminationRate() { return earlyTerminationRate; }
    public Compounding getCompounding() { return compounding; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public TimeDepositStatus getStatus() { return status; }
    public LocalDate getClosedOn() { return closedOn; }
    public BigDecimal getSettledInterest() { return settledInterest; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
}
