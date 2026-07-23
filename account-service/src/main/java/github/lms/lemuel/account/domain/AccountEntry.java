package github.lms.lemuel.account.domain;

import github.lms.lemuel.account.domain.exception.NonPositiveEntryAmountException;
import github.lms.lemuel.account.domain.exception.UnbalancedAccountEntryException;
import github.lms.lemuel.common.ledger.LedgerInvariants;
import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 전사 GL 분개(순수 POJO).
 *
 * <p>한 전표 = 차변 1 계정 + 대변 1 계정 + 금액 1 로 구성된 균형 분개다. 한 전표 안에서
 * 차변금액 = 대변금액(=amount) 이므로 차대 균형이 구성적으로 보장된다(loan {@code LoanLedgerEntry} 패턴).
 *
 * <p>정적 팩토리 6종이 loan·investment·settlement 이벤트를 계정과목 조합으로 매핑한다 — 이 매핑이
 * 계정계의 핵심 도메인 규칙이다. {@code sourceTopic}·{@code refType}·{@code refId} 는 전표의 자연키를
 * 이루어(=중복 수신 시 스키마 UNIQUE 로 멱등) 어느 이벤트에서 파생됐는지 추적한다.
 *
 * <p>현금 흐름 모델은 ADR 0026 Option ①(지급액 기준 인식 + 유보 별도 부채계정 + 감액 사건 GL mirror)을
 * 따른다. 정산 생성 시 즉시지급분(I=net−holdback)과 유보분(H)을 <b>서로 다른 부채계정</b>으로 분리 인식하고,
 * 지급·회수·유보 해제/소진·조정·취소 등 SELLER_PAYABLE/HOLDBACK_PAYABLE/CASH 를 움직이는 모든 사건을 각자
 * 차1·대1 전표로 mirror 한다(한 사건이 두 계정쌍이면 2전표, 각기 다른 refType 자연키 — compound 금지). 정산
 * 확정(settlement.confirmed)은 GL 무전표다(상태 전이·멱등 마커만). {@code settlementConfirmed} 팩토리는 과거
 * (Option A 이전) 전기의 역사적 매핑을 문서화·계약 검증용으로 보존한다(cut-over 이전 적재분의 refType 이
 * CHECK 를 통과해야 하므로).
 *
 * <pre>
 * settlementCreatedImmediate  : DR CASH                       / CR SELLER_PAYABLE              I=net−holdback  (SETTLEMENT_CREATED)
 * settlementHoldbackRecognized: DR CASH                       / CR HOLDBACK_PAYABLE           H               (SETTLEMENT_HOLDBACK_RECOGNIZED)
 * payoutCompleted             : DR SELLER_PAYABLE             / CR CASH                       실지급           (PAYOUT_COMPLETED)
 * recoveryOpened              : DR SELLER_RECOVERY_RECEIVABLE / CR CASH                       R               (RECOVERY_OPENED)
 * recoveryOffset              : DR SELLER_PAYABLE             / CR SELLER_RECOVERY_RECEIVABLE O               (RECOVERY_OFFSET)
 * holdbackReleased            : DR HOLDBACK_PAYABLE           / CR SELLER_PAYABLE             Hr(재분류)       (HOLDBACK_RELEASED)
 * holdbackConsumed            : DR HOLDBACK_PAYABLE           / CR CASH                       Hc              (HOLDBACK_CONSUMED)
 * settlementAdjusted          : DR SELLER_PAYABLE|HOLDBACK_PAYABLE / CR CASH                  Δ(targetLeg 분기) (SETTLEMENT_ADJUSTED)
 * settlementCanceledPayable   : DR SELLER_PAYABLE             / CR CASH                       I잔여            (SETTLEMENT_CANCELED_PAYABLE)
 * settlementCanceledHoldback  : DR HOLDBACK_PAYABLE           / CR CASH                       H잔여            (SETTLEMENT_CANCELED_HOLDBACK)
 * settlementScheduledClearing : DR CASH                       / CR SETTLEMENT_SCHEDULED       cut-over 잔존 청산 백필
 * settlementConfirmed         : DR SELLER_PAYABLE             / CR SETTLEMENT_SCHEDULED       (역사적 — 현재 GL 무전표)
 * loanDisbursed               : DR LOAN_RECEIVABLE            / CR CASH
 * loanRepaid                  : DR CASH                       / CR LOAN_RECEIVABLE
 * corporateLoanDisbursed      : DR CORPORATE_LOAN_RECEIVABLE  / CR CASH   (owner=CORPORATE)
 * investmentExecuted          : DR INVESTMENT_ASSET           / CR CASH
 * withholdingAccrued          : DR SELLER_PAYABLE             / CR WITHHOLDING_PAYABLE         W(원천징수반제)   (WITHHOLDING_ACCRUED)
 * </pre>
 *
 * <p><b>완전정산 균형 증명</b>: SELLER_PAYABLE = +I −(I−O−W) −O −W +Hr −Hr = 0, HOLDBACK_PAYABLE = +H −Hc −Hr = 0,
 * SELLER_RECOVERY_RECEIVABLE = +R −ΣO = 0, CASH 는 회수 종료 시 0 으로 닫힌다. (W 는 원천징수 발생 시에만
 * 항 추가 — payoutCompleted 의 DR 액수가 이미 {@code I−O−W} 로 줄어 있으므로 withholdingAccrued 의 DR W 가
 * 정확히 이 잔여를 상쇄한다.)
 */
public class AccountEntry {

    public static final String TOPIC_SETTLEMENT_CREATED = "lemuel.settlement.created";
    public static final String TOPIC_SETTLEMENT_CONFIRMED = "lemuel.settlement.confirmed";
    public static final String TOPIC_PAYOUT_COMPLETED = "lemuel.payout.completed";
    public static final String TOPIC_HOLDBACK_RELEASED = "lemuel.settlement.holdback_released";
    public static final String TOPIC_HOLDBACK_CONSUMED = "lemuel.settlement.holdback_consumed";
    public static final String TOPIC_SETTLEMENT_ADJUSTED = "lemuel.settlement.adjusted";
    public static final String TOPIC_SETTLEMENT_CANCELED = "lemuel.settlement.canceled";
    public static final String TOPIC_RECOVERY_OPENED = "lemuel.seller_recovery.opened";
    public static final String TOPIC_RECOVERY_OFFSET = "lemuel.seller_recovery.offset";
    public static final String TOPIC_WITHHOLDING_ACCRUED = "lemuel.settlement.withholding_accrued";
    public static final String TOPIC_LOAN_DISBURSED = "lemuel.loan.disbursement_requested";
    public static final String TOPIC_LOAN_REPAID = "lemuel.loan.repayment_applied";
    public static final String TOPIC_CORPORATE_LOAN_DISBURSED = "lemuel.loan.corporate_loan_disbursed";
    public static final String TOPIC_INVESTMENT_EXECUTED = "lemuel.investment.executed";
    /** 백필 청산 분개의 합성 source_topic — 실제 Kafka 토픽이 아니라 자연키 구성용(멱등). */
    public static final String SOURCE_SCHEDULED_CLEARING = "lemuel.account.backfill";

    private final Long id;
    private final OwnerType ownerType;
    private final String ownerId;
    private final GlAccount debitAccount;
    private final GlAccount creditAccount;
    private final BigDecimal amount;
    private final String refType;
    private final String refId;
    private final String sourceTopic;
    private final LocalDateTime occurredAt;

    private AccountEntry(Long id, OwnerType ownerType, String ownerId,
                         GlAccount debitAccount, GlAccount creditAccount, BigDecimal amount,
                         String refType, String refId, String sourceTopic, LocalDateTime occurredAt) {
        // 구성적 균형 불변식(양수 금액 + 차변≠대변)은 공용 LedgerInvariants 단일 출처로 강제한다.
        // account_entries.amount 는 numeric(19,2) 라 Money(scale 2 HALF_UP) 저장 표현이 동일하다.
        Money money = LedgerInvariants.requirePositiveAmount(amount, () -> new NonPositiveEntryAmountException(amount));
        LedgerInvariants.requireDistinctAccounts(debitAccount, creditAccount,
                () -> new UnbalancedAccountEntryException(debitAccount));
        this.id = id;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = money.toBigDecimal();   // 영속 경계로는 원시 BigDecimal 로 환원
        this.refType = refType;
        this.refId = refId;
        this.sourceTopic = sourceTopic;
        this.occurredAt = occurredAt;
    }

    private static AccountEntry of(OwnerType ownerType, String ownerId,
                                   GlAccount debit, GlAccount credit, BigDecimal amount,
                                   String refType, String refId, String sourceTopic) {
        return new AccountEntry(null, ownerType, ownerId, debit, credit, amount,
                refType, refId, sourceTopic, LocalDateTime.now());
    }

    /**
     * 정산 생성 즉시지급분 → DR CASH / CR SELLER_PAYABLE (Option ① — 즉시지급 대상 I=net−holdback 인식).
     * 유보분(H)은 {@link #settlementHoldbackRecognized}로 별도 전기(2전표, compound 금지).
     */
    public static AccountEntry settlementCreatedImmediate(String sellerId, String settlementId, BigDecimal immediateAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.CASH, GlAccount.SELLER_PAYABLE, immediateAmount,
                "SETTLEMENT_CREATED", settlementId, TOPIC_SETTLEMENT_CREATED);
    }

    /**
     * 정산 생성 유보분 → DR CASH / CR HOLDBACK_PAYABLE (Option ① — 홀드백 지급 의무 인식).
     * {@code settlement.created} 의 두 번째 전표(refType 로 즉시분과 구분, refId=settlementId 동일).
     */
    public static AccountEntry settlementHoldbackRecognized(String sellerId, String settlementId, BigDecimal holdbackAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.CASH, GlAccount.HOLDBACK_PAYABLE, holdbackAmount,
                "SETTLEMENT_HOLDBACK_RECOGNIZED", settlementId, TOPIC_SETTLEMENT_CREATED);
    }

    /**
     * 유보 해제(재분류) → DR HOLDBACK_PAYABLE / CR SELLER_PAYABLE (Option ① — 잔여 홀드백을 즉시지급 대상으로 재분류).
     * 이 재분류 선행 덕에 후속 지급은 즉시분과 동일하게 {@code payoutCompleted}(DR SELLER_PAYABLE/CR CASH)로 전기된다.
     */
    public static AccountEntry holdbackReleased(String sellerId, String settlementId, BigDecimal releasedAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.HOLDBACK_PAYABLE, GlAccount.SELLER_PAYABLE, releasedAmount,
                "HOLDBACK_RELEASED", settlementId, TOPIC_HOLDBACK_RELEASED);
    }

    /**
     * 유보 소진(환불/클로백 흡수) → DR HOLDBACK_PAYABLE / CR CASH (Option ① — 홀드백에서 감액분 현금 정산).
     * 자연키 refId=sourceAdjustmentId(감액을 유발한 조정 id) — 조정당 1회 멱등.
     */
    public static AccountEntry holdbackConsumed(String sellerId, String sourceAdjustmentId, BigDecimal consumedAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.HOLDBACK_PAYABLE, GlAccount.CASH, consumedAmount,
                "HOLDBACK_CONSUMED", sourceAdjustmentId, TOPIC_HOLDBACK_CONSUMED);
    }

    /**
     * 확정 전 조정(환불/클로백 감액) → DR {targetLeg} / CR CASH (Option ① — 미지급 부채 감액 + 현금 조정).
     * {@code targetLeg} 는 감액이 즉시분(SELLER_PAYABLE) 인지 유보분(HOLDBACK_PAYABLE) 인지 분기한다 — 그 외 계정은 거부.
     */
    public static AccountEntry settlementAdjusted(String sellerId, String adjustmentId, BigDecimal delta, GlAccount targetLeg) {
        if (targetLeg != GlAccount.SELLER_PAYABLE && targetLeg != GlAccount.HOLDBACK_PAYABLE) {
            throw new UnbalancedAccountEntryException(targetLeg);
        }
        return of(OwnerType.SELLER, sellerId,
                targetLeg, GlAccount.CASH, delta,
                "SETTLEMENT_ADJUSTED", adjustmentId, TOPIC_SETTLEMENT_ADJUSTED);
    }

    /** 정산 취소 즉시분 → DR SELLER_PAYABLE / CR CASH (Option ① — 잔여 즉시 미지급금 소멸 + 현금 회수). */
    public static AccountEntry settlementCanceledPayable(String sellerId, String settlementId, BigDecimal immediateRemainder) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.CASH, immediateRemainder,
                "SETTLEMENT_CANCELED_PAYABLE", settlementId, TOPIC_SETTLEMENT_CANCELED);
    }

    /** 정산 취소 유보분 → DR HOLDBACK_PAYABLE / CR CASH (Option ① — 잔여 유보 미지급금 소멸 + 현금 회수). */
    public static AccountEntry settlementCanceledHoldback(String sellerId, String settlementId, BigDecimal holdbackRemainder) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.HOLDBACK_PAYABLE, GlAccount.CASH, holdbackRemainder,
                "SETTLEMENT_CANCELED_HOLDBACK", settlementId, TOPIC_SETTLEMENT_CANCELED);
    }

    /**
     * 지급후 회수채권 발생 → DR SELLER_RECOVERY_RECEIVABLE / CR CASH (Option ① — P0-6 회수채권 GL mirror).
     * 지급이 이미 나간 뒤 감액이 확정되면 셀러로부터 회수할 채권 R 을 인식한다. 자연키 refId=recoveryId.
     */
    public static AccountEntry recoveryOpened(String sellerId, String recoveryId, BigDecimal recoveredAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_RECOVERY_RECEIVABLE, GlAccount.CASH, recoveredAmount,
                "RECOVERY_OPENED", recoveryId, TOPIC_RECOVERY_OPENED);
    }

    /**
     * 회수 상계 → DR SELLER_PAYABLE / CR SELLER_RECOVERY_RECEIVABLE (Option ① — 신규 정산 미지급금으로 회수채권 상계).
     * 자연키 refId=allocationId(상계 할당 id) — 상계 건별 1회 멱등.
     */
    public static AccountEntry recoveryOffset(String sellerId, String allocationId, BigDecimal offsetAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.SELLER_RECOVERY_RECEIVABLE, offsetAmount,
                "RECOVERY_OFFSET", allocationId, TOPIC_RECOVERY_OFFSET);
    }

    /**
     * 정산 확정 → DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED (역사적 예정 상계).
     * Option A 이후 이 전기는 발생하지 않는다(확정은 GL 무전표) — cut-over 이전 적재분의 refType 검증 및
     * 역사적 매핑 문서화용으로 팩토리를 보존한다.
     */
    public static AccountEntry settlementConfirmed(String sellerId, String settlementId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.SETTLEMENT_SCHEDULED, amount,
                "SETTLEMENT_CONFIRMED", settlementId, TOPIC_SETTLEMENT_CONFIRMED);
    }

    /**
     * 셀러 정산금 실지급 완료 → DR SELLER_PAYABLE / CR CASH (Option A — 미지급금 상계 + 현금 유출).
     * settlement 가 발행한 {@code lemuel.payout.completed} 를 account 가 소비해 전기한다.
     */
    public static AccountEntry payoutCompleted(String sellerId, String payoutId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.CASH, amount,
                "PAYOUT_COMPLETED", payoutId, TOPIC_PAYOUT_COMPLETED);
    }

    /**
     * cut-over 잔존 정산예정금 청산 백필 → DR CASH / CR SETTLEMENT_SCHEDULED.
     * Option A 이전에 적재된 셀러별 {@code SETTLEMENT_SCHEDULED} 순차변 잔액을 CASH 로 재분류해
     * 계정을 0 으로 닫는 마감 조정분개다(전면 재처리 아님). 자연키 refId=sellerId 로 셀러당 1회 멱등.
     */
    public static AccountEntry settlementScheduledClearing(String sellerId, BigDecimal residual) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.CASH, GlAccount.SETTLEMENT_SCHEDULED, residual,
                "SETTLEMENT_SCHED_CLEARING", sellerId, SOURCE_SCHEDULED_CLEARING);
    }

    /** 셀러 선정산 선지급 → DR LOAN_RECEIVABLE / CR CASH. */
    public static AccountEntry loanDisbursed(String sellerId, String loanId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.LOAN_RECEIVABLE, GlAccount.CASH, amount,
                "LOAN_DISBURSED", loanId, TOPIC_LOAN_DISBURSED);
    }

    /**
     * 셀러 대출 상환 차감 → DR CASH / CR LOAN_RECEIVABLE.
     * deducted 0 이면 분개가 성립하지 않으므로 호출 전 서비스/컨슈머에서 스킵한다(팩토리는 양수만 허용).
     */
    public static AccountEntry loanRepaid(String sellerId, String settlementId, BigDecimal deducted) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.CASH, GlAccount.LOAN_RECEIVABLE, deducted,
                "LOAN_REPAID", settlementId, TOPIC_LOAN_REPAID);
    }

    /** 법인 대출 선지급(원금만) → DR CORPORATE_LOAN_RECEIVABLE / CR CASH (owner=CORPORATE). */
    public static AccountEntry corporateLoanDisbursed(String stockCode, String loanId, BigDecimal principal) {
        return of(OwnerType.CORPORATE, stockCode,
                GlAccount.CORPORATE_LOAN_RECEIVABLE, GlAccount.CASH, principal,
                "CORP_LOAN_DISBURSED", loanId, TOPIC_CORPORATE_LOAN_DISBURSED);
    }

    /**
     * 원천징수 예수 반제 → DR SELLER_PAYABLE / CR WITHHOLDING_PAYABLE (ADR 0026 Option ① 확장, ADR 0027 §B
     * 2026-07-24 정정 — HIGH #4 봉합). settlement 의 payout 산정이 원천징수를 실제 공제하면서 남는
     * SELLER_PAYABLE 잔여(= withholdingAmount)를 이 전표로 닫는다. 자연키 refId=settlementId(정산 1건당
     * 원천징수 확정은 1회이므로 멱등).
     */
    public static AccountEntry withholdingAccrued(String sellerId, String settlementId, BigDecimal withholdingAmount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.WITHHOLDING_PAYABLE, withholdingAmount,
                "WITHHOLDING_ACCRUED", settlementId, TOPIC_WITHHOLDING_ACCRUED);
    }

    /** 투자 집행 → DR INVESTMENT_ASSET / CR CASH. */
    public static AccountEntry investmentExecuted(String sellerId, String orderId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.INVESTMENT_ASSET, GlAccount.CASH, amount,
                "INVESTMENT_EXECUTED", orderId, TOPIC_INVESTMENT_EXECUTED);
    }

    /** 영속 상태에서 도메인으로 복원 (id·occurredAt 포함). */
    public static AccountEntry reconstitute(Long id, OwnerType ownerType, String ownerId,
                                            GlAccount debitAccount, GlAccount creditAccount, BigDecimal amount,
                                            String refType, String refId, String sourceTopic,
                                            LocalDateTime occurredAt) {
        return new AccountEntry(id, ownerType, ownerId, debitAccount, creditAccount, amount,
                refType, refId, sourceTopic, occurredAt);
    }

    public Long getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public String getOwnerId() { return ownerId; }
    public GlAccount getDebitAccount() { return debitAccount; }
    public GlAccount getCreditAccount() { return creditAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getRefType() { return refType; }
    public String getRefId() { return refId; }
    public String getSourceTopic() { return sourceTopic; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
