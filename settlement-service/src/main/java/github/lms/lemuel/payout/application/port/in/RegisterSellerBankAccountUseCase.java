package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;

/**
 * 셀러 지급 계좌 등록·정정 유스케이스 — 운영자 콘솔과 셀러 셀프서비스가 공용 호출한다.
 *
 * <p><b>인가: 이 포트는 검증하지 않는다</b> — {@code sellerId} 를 무조건 신뢰하고 저장하므로, 호출
 * 어댑터가 식별자 출처를 반드시 보증해야 한다: 관리자 콘솔은 ADMIN/MANAGER 권한 게이트 하에 관리자
 * 입력을 받고, 셀프서비스는 JWT 주체(userId)에서만 파생한다(IDOR 방지). 새 호출자를 추가할 때 이
 * 계약을 지키지 않으면 타인 계좌 탈취 경로가 열린다.
 */
public interface RegisterSellerBankAccountUseCase {

    /** 신규 등록 또는 기존 정정(upsert) — 이미 있으면 계좌를 정정, 없으면 새로 등록한다. */
    SellerBankAccountRegistration register(Long sellerId, String bankCode,
                                           String accountNumber, String accountHolder);
}
