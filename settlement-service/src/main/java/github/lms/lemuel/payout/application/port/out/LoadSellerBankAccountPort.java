package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.SellerBankAccount;

import java.util.Optional;

/**
 * 셀러 지급 계좌 해석 포트 — Payout 생성 시 송금 대상 계좌 스냅샷을 얻는다.
 *
 * <p>운영에서는 셀러 온보딩/조직 서비스가 소유한 계좌 정보를 이벤트 프로젝션으로 적재해 해석해야 한다.
 * 현재는 그 상류 프로젝션이 없어 기본 어댑터가 결정적 플레이스홀더를 제공한다(SellerBankAccount 도메인
 * 주석과 동일한 포트폴리오 단순화 경계). 계좌를 해석하지 못하면 {@link Optional#empty()} 를 반환하고,
 * 서비스는 Payout 생성을 생략한다(반쪽 지급 방지).
 */
public interface LoadSellerBankAccountPort {

    /** @return 셀러 지급 계좌. 해석 불가 시 {@code Optional.empty()}. */
    Optional<SellerBankAccount> findBySellerId(Long sellerId);
}
