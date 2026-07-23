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

    /** 셀러 미지급금 (부채, 대변성) — 즉시지급 대상 정산금에 대한 지급 의무(ADR 0026 Option ①: net 전액 아님, 즉시분만). */
    SELLER_PAYABLE(AccountSide.CREDIT),

    /** 셀러 유보 미지급금 (부채, 대변성) — 홀드백(유보) 지급 의무. 소진·해제·취소로 감소(ADR 0026 Option ①). */
    HOLDBACK_PAYABLE(AccountSide.CREDIT),

    /** 지급후 회수채권 (자산, 차변성) — 지급 완료 후 발생한 감액분에 대한 셀러 회수채권(P0-6 GL mirror). */
    SELLER_RECOVERY_RECEIVABLE(AccountSide.DEBIT),

    /** 정산 예정 (자산성 클리어링, 차변성) — cut-over 이전 역사적 클리어링. Option ① 이후 신규 전기 없음(백필 청산 대상). */
    SETTLEMENT_SCHEDULED(AccountSide.DEBIT),

    /**
     * 원천징수 예수금 (부채, 대변성) — 개인 셀러 사업소득에서 실 지급액 공제로 예수한 원천세
     * (ADR 0026 Option ① 확장, ADR 0027 §B 2026-07-24 정정 — HIGH #4 실지급 통합 봉합).
     * settlement 가 payout 산정 시 원천징수를 실제 공제하면서 남는 SELLER_PAYABLE 잔여를
     * {@code Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE} 로 닫아 통제계정 폐루프를 유지한다.
     */
    WITHHOLDING_PAYABLE(AccountSide.CREDIT);

    private final AccountSide side;

    GlAccount(AccountSide side) {
        this.side = side;
    }

    /** 이 계정의 정상 잔액 방향(차변성/대변성). */
    public AccountSide side() {
        return side;
    }
}
