package github.lms.lemuel.payout.adapter.out.seller;

import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 셀러 지급 계좌 해석의 MVP 플레이스홀더 어댑터.
 *
 * <p>셀러 계좌 원천(온보딩/조직 서비스 프로젝션)이 아직 없어, sellerId 기반의 결정적 플레이스홀더 계좌를
 * 돌려준다. 같은 셀러는 항상 같은 계좌 스냅샷을 얻으므로 Payout 배선·멱등 테스트가 결정적으로 동작한다.
 * 실제 계좌 프로젝션이 붙으면 이 어댑터를 그 조회로 교체한다(@Primary 로 대체 또는 본 클래스 대치).
 *
 * <p>계좌번호·예금주는 마스킹·암호화 경계를 통과하는 형식만 만족하는 더미다 —
 * {@link SellerBankAccount} 도메인 주석의 포트폴리오 단순화 원칙과 동일 선상.
 */
@Component
public class PlaceholderSellerBankAccountAdapter implements LoadSellerBankAccountPort {

    @Override
    public Optional<SellerBankAccount> findBySellerId(Long sellerId) {
        if (sellerId == null) {
            return Optional.empty();
        }
        String accountNumber = String.format("000-%010d", sellerId);
        return Optional.of(new SellerBankAccount("KB", accountNumber, "SELLER-" + sellerId));
    }
}
