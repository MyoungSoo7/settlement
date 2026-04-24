package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account_balance_snapshots")
@Getter
@NoArgsConstructor
public class AccountBalanceSnapshotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDate snapshotAt;

    public AccountBalanceSnapshotJpaEntity(Long accountId, BigDecimal balance, LocalDate snapshotAt) {
        this.accountId = accountId;
        this.balance = balance;
        this.snapshotAt = snapshotAt;
    }
}
