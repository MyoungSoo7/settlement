package github.lms.lemuel.account.banking.pension.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 퇴직연금 서브원장 거래(자식 엔티티) — 순수 POJO, 생성 후 불변.
 *
 * <p>{@code seq} 는 계약 단위로 단조 증가하는 거래 일련번호다. GL 전표의 자연키가
 * {@code RP-{pensionId}-{seq}} 이므로(=AccountEntry 팩토리 규약) 이 값이 곧 <b>전표의 유일성</b>이다.
 * 같은 날 같은 금액의 납입이 두 번 들어와도 seq 가 다르면 두 전표로, 재수신이면 같은 seq 로 멱등 흡수된다.
 *
 * <p>{@code contributionSource} 는 CONTRIBUTION 에만, {@code midWithdrawalReason} 은 MID_WITHDRAWAL 에만
 * 실린다 — 팩토리가 그 조합만 만들 수 있게 열어두므로 다른 조합은 표현 불가능하다.
 */
public class PensionTransaction {

    private final Long id;
    private final long seq;
    private final PensionTransactionType type;
    private final BigDecimal amount;
    private final ContributionSource contributionSource;
    private final MidWithdrawalReason midWithdrawalReason;
    private final LocalDate occurredOn;

    private PensionTransaction(Long id, long seq, PensionTransactionType type, BigDecimal amount,
                               ContributionSource contributionSource, MidWithdrawalReason midWithdrawalReason,
                               LocalDate occurredOn) {
        this.id = id;
        this.seq = seq;
        this.type = type;
        this.amount = amount;
        this.contributionSource = contributionSource;
        this.midWithdrawalReason = midWithdrawalReason;
        this.occurredOn = occurredOn;
    }

    /** 부담금 납입 — 납입 주체 필수. 금액 양수·제도별 주체 허용은 애그리게이트가 이미 강제했다. */
    static PensionTransaction contribution(long seq, BigDecimal amount, ContributionSource source, LocalDate on) {
        return new PensionTransaction(null, seq, PensionTransactionType.CONTRIBUTION, amount, source, null, on);
    }

    /** 운용수익(원리금보장 이자) 확정. */
    static PensionTransaction interest(long seq, BigDecimal amount, LocalDate on) {
        return new PensionTransaction(null, seq, PensionTransactionType.INTEREST, amount, null, null, on);
    }

    /** 퇴직급여 지급(연금·일시금 공통). */
    static PensionTransaction benefit(long seq, BigDecimal amount, LocalDate on) {
        return new PensionTransaction(null, seq, PensionTransactionType.BENEFIT, amount, null, null, on);
    }

    /** 법정 사유 중도인출 — 인출 사유 필수. */
    static PensionTransaction midWithdrawal(long seq, BigDecimal amount, MidWithdrawalReason reason, LocalDate on) {
        return new PensionTransaction(null, seq, PensionTransactionType.MID_WITHDRAWAL, amount, null, reason, on);
    }

    /** 영속 상태에서 도메인으로 복원 (id 포함). */
    public static PensionTransaction reconstitute(Long id, long seq, PensionTransactionType type, BigDecimal amount,
                                                  ContributionSource contributionSource,
                                                  MidWithdrawalReason midWithdrawalReason, LocalDate occurredOn) {
        return new PensionTransaction(id, seq, type, amount, contributionSource, midWithdrawalReason, occurredOn);
    }

    public Long getId() { return id; }
    public long getSeq() { return seq; }
    public PensionTransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public ContributionSource getContributionSource() { return contributionSource; }
    public MidWithdrawalReason getMidWithdrawalReason() { return midWithdrawalReason; }
    public LocalDate getOccurredOn() { return occurredOn; }
}
