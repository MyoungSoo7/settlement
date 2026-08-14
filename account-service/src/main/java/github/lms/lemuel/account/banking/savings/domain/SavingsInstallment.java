package github.lms.lemuel.account.banking.savings.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 적금 회차(納入) — {@link InstallmentSavings} 애그리거트의 자식. 순수 POJO, 전 필드 불변.
 *
 * <p>회차는 <b>납입된 사실</b>만 표현한다 — 미납 회차는 행으로 존재하지 않는다. 미납을 빈 행으로
 * 만들면 "납입 안 한 회차"와 "0 원 납입한 회차"가 구분되지 않고, 이자 계산이 paidOn 이 null 인
 * 행을 계속 걸러내야 한다.
 *
 * <p>{@code dueDate} = {@code openedOn.plusMonths(round - 1)} 로 회차 개설 시 확정되며,
 * {@code overdueDays} 는 dueDate 를 넘겨 낸 일수다(정상 납입이면 0).
 */
public class SavingsInstallment {

    private final Long id;
    private final int round;
    private final BigDecimal amount;
    private final LocalDate dueDate;
    private final LocalDate paidOn;
    private final int overdueDays;

    private SavingsInstallment(Long id, int round, BigDecimal amount,
                               LocalDate dueDate, LocalDate paidOn, int overdueDays) {
        this.id = id;
        this.round = round;
        this.amount = amount;
        this.dueDate = dueDate;
        this.paidOn = paidOn;
        this.overdueDays = overdueDays;
    }

    /**
     * 납입 사실로부터 회차를 만든다 — overdueDays 는 dueDate 와 paidOn 으로 <b>유도</b>되며 외부에서
     * 주입하지 않는다(주입 가능하면 연체 사실을 요청자가 지울 수 있다).
     */
    static SavingsInstallment paid(int round, BigDecimal amount, LocalDate dueDate, LocalDate paidOn) {
        long overdue = ChronoUnit.DAYS.between(dueDate, paidOn);
        return new SavingsInstallment(null, round, amount, dueDate, paidOn,
                overdue > 0 ? Math.toIntExact(overdue) : 0);
    }

    /** DB 복원 — 저장된 overdueDays 를 그대로 신뢰한다(저장 시점에 이미 유도된 값). */
    public static SavingsInstallment reconstitute(Long id, int round, BigDecimal amount,
                                                  LocalDate dueDate, LocalDate paidOn, int overdueDays) {
        return new SavingsInstallment(id, round, amount, dueDate, paidOn, overdueDays);
    }

    /** 연체 납입 여부. */
    public boolean isOverdue() {
        return overdueDays > 0;
    }

    public Long getId() { return id; }
    public int getRound() { return round; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getPaidOn() { return paidOn; }
    public int getOverdueDays() { return overdueDays; }
}
