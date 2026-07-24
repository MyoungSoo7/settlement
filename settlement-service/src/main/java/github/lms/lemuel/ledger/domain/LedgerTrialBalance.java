package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 기간별 확정 시산표 — 한 회계 기간(월)의 POSTED 분개를 계정과목(AccountType)별 차/대 합계로 집계한 값 객체.
 *
 * <p>각 분개 row 는 (차변계정, 대변계정, 금액) 한 쌍이므로 계정별 차변 합 = Σ(debitAccount=X 인 amount),
 * 대변 합 = Σ(creditAccount=X 인 amount). 모든 row 가 구성적으로 균형(차금액=대금액=amount)이므로
 * 전체 차변 합계 == 전체 대변 합계가 항상 성립해야 한다 — {@link #isBalanced()} 는 반쪽 전표 등 데이터
 * 손상을 잡는 안전망이다.
 *
 * <p>불변(순수) 값 객체 — 집계 결과를 계정별 라인 + 총합으로 담는다. 금액 산술은 공용 {@link Money} VO
 * (scale 2 HALF_UP)로 수행한다.
 */
public class LedgerTrialBalance {

    private final YearMonth period;
    private final List<Line> lines;
    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;
    private final boolean balanced;

    private LedgerTrialBalance(YearMonth period, List<Line> lines,
                              BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {
        this.period = period;
        this.lines = List.copyOf(lines);
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.balanced = balanced;
    }

    /**
     * 계정별 차변 합·대변 합 맵으로부터 시산표를 조립한다. 두 맵에 등장하는 모든 계정에 대해
     * 라인을 생성하고(누락 측은 0), 총합·균형 여부를 계산한다. 라인 순서는 {@link AccountType} 정의 순.
     */
    public static LedgerTrialBalance of(YearMonth period,
                                        Map<AccountType, BigDecimal> debitByAccount,
                                        Map<AccountType, BigDecimal> creditByAccount) {
        Map<AccountType, BigDecimal> debit = normalize(debitByAccount);
        Map<AccountType, BigDecimal> credit = normalize(creditByAccount);

        List<Line> lines = new ArrayList<>();
        Money totalDebit = Money.ZERO;
        Money totalCredit = Money.ZERO;

        for (AccountType account : AccountType.values()) {
            Money d = Money.of(debit.getOrDefault(account, BigDecimal.ZERO));
            Money c = Money.of(credit.getOrDefault(account, BigDecimal.ZERO));
            if (d.isZero() && c.isZero()) {
                continue; // 활동 없는 계정은 라인 생략
            }
            lines.add(new Line(account, d.toBigDecimal(), c.toBigDecimal(), d.minus(c).toBigDecimal()));
            totalDebit = totalDebit.plus(d);
            totalCredit = totalCredit.plus(c);
        }

        boolean balanced = totalDebit.toBigDecimal().compareTo(totalCredit.toBigDecimal()) == 0;
        return new LedgerTrialBalance(period, lines, totalDebit.toBigDecimal(), totalCredit.toBigDecimal(), balanced);
    }

    private static Map<AccountType, BigDecimal> normalize(Map<AccountType, BigDecimal> src) {
        Map<AccountType, BigDecimal> out = new EnumMap<>(AccountType.class);
        if (src != null) {
            src.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    public YearMonth getPeriod() {
        return period;
    }

    public String getPeriodYm() {
        return period.toString();
    }

    public List<Line> getLines() {
        return lines;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }

    public boolean isBalanced() {
        return balanced;
    }

    /** 계정과목별 한 줄 — 차변 합, 대변 합, 순액(차−대). */
    public record Line(AccountType account, BigDecimal debit, BigDecimal credit, BigDecimal net) {
    }
}
