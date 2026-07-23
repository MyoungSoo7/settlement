package github.lms.lemuel.account.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * owner 별 계정 잔액 요약(도메인 계산).
 *
 * <p>한 owner(셀러/법인)의 전표들을 계정과목별로 접어, 계정 성격({@link AccountSide})에 맞는 부호로
 * 잔액을 낸다 — 차변성 계정은 {@code DR−CR}, 대변성 계정은 {@code CR−DR}. 등장한 계정만 노출한다.
 */
public final class AccountSummary {

    /** 계정과목별 잔액 한 줄 — side 는 계정 성격(차변/대변). */
    public record Balance(GlAccount account, AccountSide side, BigDecimal balance) { }

    private final OwnerType ownerType;
    private final String ownerId;
    private final List<Balance> balances;
    private final int entryCount;

    private AccountSummary(OwnerType ownerType, String ownerId, List<Balance> balances, int entryCount) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.balances = balances;
        this.entryCount = entryCount;
    }

    /**
     * @param entries 반드시 동일 owner 의 전표만. (조회 계층에서 owner 로 필터링해 전달)
     */
    public static AccountSummary of(OwnerType ownerType, String ownerId, List<AccountEntry> entries) {
        Map<GlAccount, BigDecimal> debit = new EnumMap<>(GlAccount.class);
        Map<GlAccount, BigDecimal> credit = new EnumMap<>(GlAccount.class);

        for (AccountEntry e : entries) {
            debit.merge(e.getDebitAccount(), e.getAmount(), BigDecimal::add);
            credit.merge(e.getCreditAccount(), e.getAmount(), BigDecimal::add);
        }

        List<Balance> balances = new ArrayList<>();
        for (GlAccount account : GlAccount.values()) {
            BigDecimal dr = debit.getOrDefault(account, BigDecimal.ZERO);
            BigDecimal cr = credit.getOrDefault(account, BigDecimal.ZERO);
            if (dr.signum() == 0 && cr.signum() == 0) {
                continue;
            }
            BigDecimal balance = account.side() == AccountSide.DEBIT
                    ? dr.subtract(cr)
                    : cr.subtract(dr);
            balances.add(new Balance(account, account.side(), balance));
        }
        return new AccountSummary(ownerType, ownerId, List.copyOf(balances), entries.size());
    }

    public OwnerType ownerType() { return ownerType; }
    public String ownerId() { return ownerId; }
    public List<Balance> balances() { return balances; }
    public int entryCount() { return entryCount; }

    /** 셀러 통제계정(미지급/유보/회수채권). 완전정산 봉합 판정 대상(ADR 0026 Option ①). */
    private static final java.util.Set<GlAccount> SELLER_CONTROL_ACCOUNTS = java.util.EnumSet.of(
            GlAccount.SELLER_PAYABLE, GlAccount.HOLDBACK_PAYABLE, GlAccount.SELLER_RECOVERY_RECEIVABLE);

    /**
     * 완전정산 봉합 여부(ADR 0026 Option ① HIGH-1 제거) — 이 owner 의 세 통제계정
     * (SELLER_PAYABLE·HOLDBACK_PAYABLE·SELLER_RECOVERY_RECEIVABLE) 순잔액이 모두 0 이면 참.
     *
     * <p>완전정산 셀러는 즉시분·유보분이 지급/해제/소진/취소로 전액 discharge 되고 회수채권도 상계 완료돼
     * 세 통제계정이 산식적으로 0 으로 닫혀야 한다. 등장하지 않은 계정은 0 으로 본다. (CASH 는 플랫폼 전역
     * 계정이라 셀러 단위 봉합 대상에서 제외 — 회수 미종료 등 정상 사유로 셀러별 0 이 아닐 수 있다.)
     */
    public boolean fullySettled() {
        for (Balance b : balances) {
            if (SELLER_CONTROL_ACCOUNTS.contains(b.account()) && b.balance().signum() != 0) {
                return false;
            }
        }
        return true;
    }
}
