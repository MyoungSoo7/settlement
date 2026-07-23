package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;

/**
 * 셀러 지급 계좌 등록·정정 유스케이스 (운영자 콘솔).
 *
 * <p>인가: 셀러 식별자를 관리자 입력으로 받으므로 ADMIN/MANAGER 권한 게이트로 보호한다(IDOR 방지 —
 * 셀러 리소스 식별자를 무권한 요청 파라미터로 신뢰하지 않는다).
 */
public interface RegisterSellerBankAccountUseCase {

    /** 신규 등록 또는 기존 정정(upsert) — 이미 있으면 계좌를 정정, 없으면 새로 등록한다. */
    SellerBankAccountRegistration register(Long sellerId, String bankCode,
                                           String accountNumber, String accountHolder);
}
