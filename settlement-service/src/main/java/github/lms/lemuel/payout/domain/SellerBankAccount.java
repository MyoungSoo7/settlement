package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;

/**
 * 송금 대상 계좌 — 정산 시점 스냅샷으로 Payout 에 영구 저장된다.
 *
 * <p>도메인은 계좌번호를 평문으로 다루고, 저장 시 암호화(AES-GCM, {@code PAYOUT_ENC_KEY})는 영속
 * 어댑터의 {@code FieldEncryptionConverter} 가 담당한다(도메인 무오염). 노출 경로(로그·API)는
 * {@link #maskedAccountNumber()} 만 사용한다.
 */
public record SellerBankAccount(
        String bankCode,            // KB / SHINHAN / WOORI / TOSS / KAKAO 등
        String bankAccountNumber,   // 저장 시 FieldEncryptionConverter 가 암호화
        String accountHolderName
) {
    public SellerBankAccount {
        if (bankCode == null || bankCode.isBlank()) throw new PayoutInvariantViolationException("bankCode 필수");
        if (bankAccountNumber == null || bankAccountNumber.isBlank()) throw new PayoutInvariantViolationException("bankAccountNumber 필수");
        if (accountHolderName == null || accountHolderName.isBlank()) throw new PayoutInvariantViolationException("accountHolderName 필수");
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
