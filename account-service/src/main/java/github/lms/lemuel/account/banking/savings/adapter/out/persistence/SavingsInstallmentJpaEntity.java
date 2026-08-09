package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * savings_installments 테이블 매핑 (V20260809031500).
 *
 * <p>부모와 {@code @OneToMany} 로 묶지 않고 {@code savings_id} 를 값으로 들고 있다 — 애그리거트
 * 경계 밖에서 회차를 따로 읽어야 할 일이 없고, cascade 로 인한 예상치 못한 삭제·재삽입도 없다.
 * 회차는 append-only 이며 재납입 차단은 {@code uq_savings_installment_round} 가 맡는다.
 *
 * <p>컬럼명이 {@code round} 가 아니라 {@code round_no} 인 이유: {@code round} 는 SQL 표준 함수명이라
 * 방언·툴에 따라 인용부호를 요구해 마이그레이션·네이티브 쿼리에서 사고를 낸다.
 */
@Entity
@Table(name = "savings_installments")
public class SavingsInstallmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "savings_id", nullable = false)
    private Long savingsId;

    @Column(name = "round_no", nullable = false)
    private int roundNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "overdue_days", nullable = false)
    private int overdueDays;

    protected SavingsInstallmentJpaEntity() {
    }

    public SavingsInstallmentJpaEntity(Long id, Long savingsId, int roundNo, BigDecimal amount,
                                       LocalDate dueDate, LocalDate paidOn, int overdueDays) {
        this.id = id;
        this.savingsId = savingsId;
        this.roundNo = roundNo;
        this.amount = amount;
        this.dueDate = dueDate;
        this.paidOn = paidOn;
        this.overdueDays = overdueDays;
    }

    public static SavingsInstallmentJpaEntity fromDomain(Long savingsId, SavingsInstallment installment) {
        return new SavingsInstallmentJpaEntity(
                installment.getId(),
                savingsId,
                installment.getRound(),
                installment.getAmount(),
                installment.getDueDate(),
                installment.getPaidOn(),
                installment.getOverdueDays());
    }

    public Long getId() { return id; }
    public Long getSavingsId() { return savingsId; }
    public int getRoundNo() { return roundNo; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getPaidOn() { return paidOn; }
    public int getOverdueDays() { return overdueDays; }
}
