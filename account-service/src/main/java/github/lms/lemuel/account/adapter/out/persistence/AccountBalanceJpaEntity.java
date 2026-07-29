package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 실체화 통제계정 잔액 (account_balances) — ADR 0030 Phase 1.
 *
 * <p>정본은 원장({@code account_entries})이고 이 테이블은 <b>파생 캐시</b>다. 값은
 * {@code Σcredit − Σdebit}(credit-positive) 이며, 계정의 정상잔액 방향 해석은 {@link GlAccount} 가 맡는다.
 *
 * <p>쓰기는 어댑터의 네이티브 UPSERT 한 곳으로만 들어온다 — JPA 로 이 엔티티를 직접 save 하지 마라.
 * 델타 누적(+=)이 아니라 통째 덮어쓰기가 되어 동시 기표 중 하나가 유실된다.
 */
@Entity
@Table(name = "account_balances")
public class AccountBalanceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, length = 40)
    private GlAccount account;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AccountBalanceJpaEntity() {
    }

    public Long getId() {
        return id;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public GlAccount getAccount() {
        return account;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
