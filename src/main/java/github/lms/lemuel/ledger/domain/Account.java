package github.lms.lemuel.ledger.domain;

import java.time.LocalDateTime;

public class Account {

    private Long id;
    private String code;
    private String name;
    private AccountType type;
    private LocalDateTime createdAt;

    private Account() {}

    public static Account create(String code, String name, AccountType type) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Account code cannot be empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Account type cannot be null");
        }
        Account account = new Account();
        account.code = code;
        account.name = name;
        account.type = type;
        account.createdAt = LocalDateTime.now();
        return account;
    }

    public static Account createSellerPayable(Long sellerId) {
        return create("SELLER_PAYABLE:" + sellerId, "판매자 지급 의무 #" + sellerId, AccountType.LIABILITY);
    }

    public static Account createPlatformCash() {
        return create("PLATFORM_CASH", "플랫폼 보유 현금", AccountType.ASSET);
    }

    public static Account createPlatformCommission() {
        return create("PLATFORM_COMMISSION", "플랫폼 수수료 수익", AccountType.REVENUE);
    }

    public static Account createPlatformOwnersEquity() {
        return create("PLATFORM_OWNERS_EQUITY", "플랫폼 자본금", AccountType.OWNERS_EQUITY);
    }

    // Reconstitution constructor for persistence
    public Account(Long id, String code, String name, AccountType type, LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
