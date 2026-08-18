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

    /** 담보/개인신용 대출채권 (자산, 차변성) — SecuredLoan(주담대·개인신용) 계약 원금, owner=BORROWER. */
    SECURED_LOAN_RECEIVABLE(AccountSide.DEBIT),

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
     * (ADR 0026 Option ① 확장, ADR 0029 §B 2026-07-24 정정 — HIGH #4 실지급 통합 봉합).
     * settlement 가 payout 산정 시 원천징수를 실제 공제하면서 남는 SELLER_PAYABLE 잔여를
     * {@code Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE} 로 닫아 통제계정 폐루프를 유지한다.
     */
    WITHHOLDING_PAYABLE(AccountSide.CREDIT),

    /** 정기예금 수신부채 (부채, 대변성) — 예금주에게 만기 지급할 원금+기 발생 이자. owner=DEPOSITOR. */
    TIME_DEPOSIT_LIABILITY(AccountSide.CREDIT),

    /** 적금 수신부채 (부채, 대변성) — 회차 납입 누계 + 기 발생 이자. owner=DEPOSITOR. */
    INSTALLMENT_SAVINGS_LIABILITY(AccountSide.CREDIT),

    /** 퇴직연금 적립금 부채 (부채, 대변성) — DB·DC·IRP 부담금 누계 + 운용수익. owner=DEPOSITOR. */
    RETIREMENT_PENSION_LIABILITY(AccountSide.CREDIT),

    /**
     * 수신이자비용 (비용, 차변성) — 예금·적금·퇴직연금에 지급할 이자를 인식할 때의 상대계정.
     * 이자는 만기·해지·수급 시점에 일괄 확정해 부채로 전기하며(주기 accrual 미도입), 이 계정이 그 차변을 받는다.
     */
    INTEREST_EXPENSE(AccountSide.DEBIT),

    /**
     * 고객 포인트 선수금 (부채, 대변성) — 미사용 포인트는 회사가 고객에게 진 빚이다.
     * 충전·적립으로 늘고, 사용·소멸로 줄어든다. owner=CUSTOMER.
     */
    POINT_LIABILITY(AccountSide.CREDIT),

    /**
     * 포인트 판촉비 (비용, 차변성) — 충전 보너스·구매 적립처럼 회사가 얹어 준 포인트의 상대계정.
     * 현금 충전 원금은 여기가 아니라 {@link #CASH} 가 상대계정이다(고객이 실제로 낸 돈이므로).
     */
    POINT_PROMOTION_EXPENSE(AccountSide.DEBIT),

    /**
     * 포인트 소멸이익 (수익, 대변성) — 유효기간이 지나 사라진 포인트만큼 부채가 소멸한다.
     * 인식하지 않고 부채로 이월하면 시산표의 부채가 무한히 쌓여 의미를 잃는다.
     */
    POINT_BREAKAGE_INCOME(AccountSide.CREDIT);

    private final AccountSide side;

    GlAccount(AccountSide side) {
        this.side = side;
    }

    /** 이 계정의 정상 잔액 방향(차변성/대변성). */
    public AccountSide side() {
        return side;
    }
}
