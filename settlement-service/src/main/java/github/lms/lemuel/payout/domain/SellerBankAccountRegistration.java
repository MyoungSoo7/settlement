package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 셀러 지급 계좌 레지스트리 애그리거트 — 셀러가 등록·정정한 송금 대상 계좌의 정본.
 *
 * <p>{@link SellerBankAccount} 는 Payout 에 <b>박제되는 스냅샷 VO</b> 인 반면, 이 애그리거트는
 * 시점에 따라 정정될 수 있는 <b>가변 원천</b>이다. Payout 생성 시점에 {@link #toBankAccount()} 로
 * 스냅샷을 떠서 Payout 에 넘긴다 — 이후 계좌가 정정돼도 이미 생성된 Payout 스냅샷은 불변.
 *
 * <p>계좌번호는 도메인에선 평문으로 다루고, 영속 어댑터({@code PayoutFieldEncryptionConverter})가
 * 저장 시 암호화한다(도메인 무오염). 생성·정정은 팩토리/도메인 메서드 전용 — public setter 없음.
 */
public class SellerBankAccountRegistration {

    private final Long sellerId;
    private String bankCode;
    private String accountNumber;
    private String accountHolder;
    private LocalDateTime updatedAt;

    private SellerBankAccountRegistration(Long sellerId, String bankCode, String accountNumber,
                                          String accountHolder, LocalDateTime updatedAt) {
        if (sellerId == null) {
            throw new PayoutInvariantViolationException("sellerId 는 필수입니다");
        }
        validateAccountFields(bankCode, accountNumber, accountHolder);
        this.sellerId = sellerId;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.updatedAt = updatedAt;
    }

    /** 신규 등록 — 지금 시각을 갱신 시각으로 찍는다. */
    public static SellerBankAccountRegistration register(Long sellerId, String bankCode,
                                                         String accountNumber, String accountHolder) {
        return new SellerBankAccountRegistration(sellerId, bankCode, accountNumber, accountHolder,
                LocalDateTime.now());
    }

    /** 영속 복원 전용 — 저장된 갱신 시각을 그대로 보존한다. */
    public static SellerBankAccountRegistration rehydrate(Long sellerId, String bankCode,
                                                          String accountNumber, String accountHolder,
                                                          LocalDateTime updatedAt) {
        return new SellerBankAccountRegistration(sellerId, bankCode, accountNumber, accountHolder, updatedAt);
    }

    /** 계좌 정정 — 반송(bounce) 후 올바른 계좌로 교체하는 유일한 경로. */
    public void changeAccount(String bankCode, String accountNumber, String accountHolder) {
        validateAccountFields(bankCode, accountNumber, accountHolder);
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.updatedAt = LocalDateTime.now();
    }

    /** Payout 에 박제할 계좌 스냅샷을 뜬다. */
    public SellerBankAccount toBankAccount() {
        return new SellerBankAccount(bankCode, accountNumber, accountHolder);
    }

    private static void validateAccountFields(String bankCode, String accountNumber, String accountHolder) {
        if (bankCode == null || bankCode.isBlank()) {
            throw new PayoutInvariantViolationException("bankCode 는 필수입니다");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new PayoutInvariantViolationException("accountNumber 는 필수입니다");
        }
        if (accountHolder == null || accountHolder.isBlank()) {
            throw new PayoutInvariantViolationException("accountHolder 는 필수입니다");
        }
    }

    public Long getSellerId() { return sellerId; }
    public String getBankCode() { return bankCode; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountHolder() { return accountHolder; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
