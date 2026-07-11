package github.lms.lemuel.account.domain;

/**
 * 전사 복식부기 계정원장(GL)의 계정과목.
 *
 * <p>account-service 는 loan·investment·settlement 이 발행하는 도메인 이벤트를 소비해
 * 이 계정들 사이의 분개({@link AccountEntry})로 집계한다. 각 계정은 정상 잔액 방향
 * ({@link AccountSide})을 속성으로 가지며, owner 별 잔액 계산({@link AccountSummary})의 부호를 결정한다.
 */
public enum GlAccount {

    /** 현금/funding (자산, 차변성) — 선지급·투자 집행 시 유출, 상환 시 유입. */
    CASH(AccountSide.DEBIT),

    /** 셀러 선정산 대출채권 (자산, 차변성). */
    LOAN_RECEIVABLE(AccountSide.DEBIT),

    /** 법인(상장사) 대출채권 (자산, 차변성). */
    CORPORATE_LOAN_RECEIVABLE(AccountSide.DEBIT),

    /** 투자자산 (자산, 차변성) — 주식 등 투자 집행 결과. */
    INVESTMENT_ASSET(AccountSide.DEBIT),

    /** 셀러 미지급금 (부채, 대변성) — 정산 예정금에 대한 지급 의무. */
    SELLER_PAYABLE(AccountSide.CREDIT),

    /** 정산 예정 (자산성 클리어링, 차변성) — 정산 생성 시 차변, 확정 시 대변으로 상계. */
    SETTLEMENT_SCHEDULED(AccountSide.DEBIT);

    private final AccountSide side;

    GlAccount(AccountSide side) {
        this.side = side;
    }

    /** 이 계정의 정상 잔액 방향(차변성/대변성). */
    public AccountSide side() {
        return side;
    }
}
