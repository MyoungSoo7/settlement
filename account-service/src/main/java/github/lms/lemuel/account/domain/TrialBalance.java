package github.lms.lemuel.account.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 시산표(도메인 계산) — 전표 목록을 계정별 차변합/대변합으로 집계하고 총차변==총대변 균형을 판정한다.
 *
 * <p>각 전표가 차변금액 = 대변금액(=amount) 이므로 총차변합은 항상 총대변합과 같다(구성적 균형).
 * {@link #balanced()} 는 이 불변식을 방어적으로 재검증하는 값이다.
 */
public final class TrialBalance {

    /** 시산표 한 줄 — 계정과목별 차변합/대변합. */
    public record Line(GlAccount account, BigDecimal debitTotal, BigDecimal creditTotal) { }

    private final List<Line> lines;
    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;

    private TrialBalance(List<Line> lines, BigDecimal totalDebit, BigDecimal totalCredit) {
        this.lines = lines;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public static TrialBalance of(List<AccountEntry> entries) {
        Map<GlAccount, BigDecimal> debit = new EnumMap<>(GlAccount.class);
        Map<GlAccount, BigDecimal> credit = new EnumMap<>(GlAccount.class);

        for (AccountEntry e : entries) {
            debit.merge(e.getDebitAccount(), e.getAmount(), BigDecimal::add);
            credit.merge(e.getCreditAccount(), e.getAmount(), BigDecimal::add);
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        // 계정 정의 순서(enum) 로 안정 출력 — 등장한 계정만 노출
        for (GlAccount account : GlAccount.values()) {
            BigDecimal dr = debit.getOrDefault(account, BigDecimal.ZERO);
            BigDecimal cr = credit.getOrDefault(account, BigDecimal.ZERO);
            if (dr.signum() == 0 && cr.signum() == 0) {
                continue;
            }
            lines.add(new Line(account, dr, cr));
            totalDebit = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);
        }
        return new TrialBalance(List.copyOf(lines), totalDebit, totalCredit);
    }

    public List<Line> lines() { return lines; }
    public BigDecimal totalDebit() { return totalDebit; }
    public BigDecimal totalCredit() { return totalCredit; }
    public boolean balanced() { return totalDebit.compareTo(totalCredit) == 0; }
}
