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

    /**
     * 각 계정의 순잔액 부호가 계정 정상방향({@link GlAccount#side()})을 준수하는지 검증한다(ADR 0026 3a).
     *
     * <p>{@link #balanced()} 는 전표의 구성적 균형 때문에 항상 참이라 실검증력이 없다. 이 메서드는 그와
     * 달리 <b>계정별 순잔액의 방향</b>을 본다 — 차변성 계정(CASH·SETTLEMENT_SCHEDULED 등)은 순차변(DR≥CR),
     * 대변성 계정(SELLER_PAYABLE)은 순대변(CR≥DR)이어야 한다. 위반(예: SELLER_PAYABLE 이 순차변 = 지급이
     * 인식보다 많아 미지급금이 음수)이 하나라도 있으면 {@code false} — GL 현금 흐름이 코히런트해진 Option A
     * 에서만 의미를 갖는 이상 탐지값이다. (순잔액 0 은 준수로 본다 — 폐루프로 닫힌 정상 상태.)
     */
    /**
     * 한 계정의 <b>정상방향 순잔액</b>(side-adjusted) — 차변성은 DR−CR, 대변성은 CR−DR. 등장하지 않은
     * 계정은 0. 통제계정 대사(/control-recon)에서 GL 잔액을 서브원장 Σ 와 대조할 때의 기준값이다
     * (ADR 0026 Option ①: net(SELLER_PAYABLE)=Σ미지급 immediate, net(HOLDBACK_PAYABLE)=Σ미해제 holdback,
     * net(SELLER_RECOVERY_RECEIVABLE)=Σ OPEN 회수채권).
     */
    public BigDecimal normalBalance(GlAccount account) {
        for (Line line : lines) {
            if (line.account() == account) {
                return account.side() == AccountSide.DEBIT
                        ? line.debitTotal().subtract(line.creditTotal())
                        : line.creditTotal().subtract(line.debitTotal());
            }
        }
        return BigDecimal.ZERO;
    }

    public boolean normalBalanceRespected() {
        for (Line line : lines) {
            int net = line.debitTotal().compareTo(line.creditTotal()); // >0: 순차변, <0: 순대변
            AccountSide side = line.account().side();
            if (side == AccountSide.DEBIT && net < 0) {
                return false; // 차변성 계정이 순대변 → 정상방향 위반
            }
            if (side == AccountSide.CREDIT && net > 0) {
                return false; // 대변성 계정이 순차변 → 정상방향 위반
            }
        }
        return true;
    }
}
