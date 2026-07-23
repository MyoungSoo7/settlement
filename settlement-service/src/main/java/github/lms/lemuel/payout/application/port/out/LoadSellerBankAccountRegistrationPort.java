package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;

import java.util.Optional;

/**
 * 셀러 지급 계좌 레지스트리 조회 포트 — 등록된 계좌 정본을 로드한다.
 *
 * <p>{@link LoadSellerBankAccountPort}(스냅샷 해석) 와 구분된다: 이 포트는 <b>가변 원천</b> 애그리거트를
 * 반환하고, 계좌 스냅샷 해석은 레지스트리 우선 + 플레이스홀더 폴백 어댑터가 이 포트를 소비해 수행한다.
 */
public interface LoadSellerBankAccountRegistrationPort {

    Optional<SellerBankAccountRegistration> findBySellerId(Long sellerId);
}
