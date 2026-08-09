package github.lms.lemuel.account.banking.pension.adapter.out.persistence;

import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 퇴직연금 거래 영속 엔티티 (pension_transactions) — append-only.
 *
 * <p>{@code (pension_id, seq)} 가 UNIQUE 다. seq 는 GL 전표 자연키
 * {@code RP-{pensionId}-{seq}} 의 마지막 조각이므로, 이 유일성이 곧 전표 유일성이다.
 */
@Entity
@Table(name = "pension_transactions")
public class PensionTransactionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pension_id", nullable = false)
    private Long pensionId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 20)
    private PensionTransactionType txType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** CONTRIBUTION 에만 실린다 — 그 외 종류에서는 null(CHECK 가 강제). */
    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_source", length = 10)
    private ContributionSource contributionSource;

    /** MID_WITHDRAWAL 에만 실린다 — 법정 사유 6종 중 하나(CHECK 가 강제). */
    @Enumerated(EnumType.STRING)
    @Column(name = "mid_withdrawal_reason", length = 40)
    private MidWithdrawalReason midWithdrawalReason;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    protected PensionTransactionJpaEntity() { }

    public PensionTransactionJpaEntity(Long id, Long pensionId, long seq, PensionTransactionType txType,
                                       BigDecimal amount, ContributionSource contributionSource,
                                       MidWithdrawalReason midWithdrawalReason, LocalDate occurredOn) {
        this.id = id;
        this.pensionId = pensionId;
        this.seq = seq;
        this.txType = txType;
        this.amount = amount;
        this.contributionSource = contributionSource;
        this.midWithdrawalReason = midWithdrawalReason;
        this.occurredOn = occurredOn;
    }

    public Long getId() { return id; }
    public Long getPensionId() { return pensionId; }
    public long getSeq() { return seq; }
    public PensionTransactionType getTxType() { return txType; }
    public BigDecimal getAmount() { return amount; }
    public ContributionSource getContributionSource() { return contributionSource; }
    public MidWithdrawalReason getMidWithdrawalReason() { return midWithdrawalReason; }
    public LocalDate getOccurredOn() { return occurredOn; }
}
