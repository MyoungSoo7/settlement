package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    /** ReferenceType.name() 저장 — V45 chk_ledger_ref_type 참조. */
    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    /** LedgerEntryType.name() 저장. */
    @Column(name = "entry_type", nullable = false, length = 50)
    private String entryType;

    /** AccountType.name() 저장. */
    @Column(name = "debit_account", nullable = false, length = 50)
    private String debitAccount;

    /** AccountType.name() 저장. */
    @Column(name = "credit_account", nullable = false, length = 50)
    private String creditAccount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** LedgerStatus.name() 저장 — V45 chk_ledger_status 참조. */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(length = 500)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
