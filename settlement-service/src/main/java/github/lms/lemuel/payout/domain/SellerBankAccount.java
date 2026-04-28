package github.lms.lemuel.payout.domain;

import java.util.Objects;

/**
 * 송금 대상 계좌 — 정산 시점 스냅샷으로 Payout 에 영구 저장된다.
 *
 * <p>운영 환경에서는 {@code bankAccountNumber} 를 KMS column-level encryption 으로 암호화.
 * 본 포트폴리오는 도메인 모델 시그니처만 정의 (실 암호화는 별도 보안 계층 책임).
 */
public record SellerBankAccount(
        String bankCode,            // KB / SHINHAN / WOORI / TOSS / KAKAO 등
        String bankAccountNumber,   // 운영: KMS 암호화
        String accountHolderName
) {
    public SellerBankAccount {
        Objects.requireNonNull(bankCode, "bankCode");
        Objects.requireNonNull(bankAccountNumber, "bankAccountNumber");
        Objects.requireNonNull(accountHolderName, "accountHolderName");
        if (bankCode.isBlank()) throw new IllegalArgumentException("bankCode 필수");
        if (bankAccountNumber.isBlank()) throw new IllegalArgumentException("bankAccountNumber 필수");
        if (accountHolderName.isBlank()) throw new IllegalArgumentException("accountHolderName 필수");
    }

    /**
     * 로그·운영자 콘솔 노출용 마스킹 — 마지막 4자리만 노출.
     */
    public String maskedAccountNumber() {
        String acct = bankAccountNumber;
        if (acct.length() <= 4) return "****";
        return "****" + acct.substring(acct.length() - 4);
    }
}
