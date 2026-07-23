package github.lms.lemuel.payout.adapter.out.seller;

import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountPort;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 셀러 지급 계좌 해석의 정본 어댑터 — 레지스트리 우선, 미등록 시 플레이스홀더 폴백.
 *
 * <p>{@code @Primary} 로 기존 {@link PlaceholderSellerBankAccountAdapter} 를 대체한다: 셀러가 계좌를
 * 등록·정정했으면 그 정본을 스냅샷으로 반환하고, 아직 없으면 결정적 플레이스홀더로 폴백한다. 폴백을
 * 남겨 기존 결정적 테스트·배선의 회귀를 막는다(반송 재지급은 정정된 등록 계좌를 이 경로로 신선 로드한다).
 */
@Primary
@Component
public class RegistrySellerBankAccountAdapter implements LoadSellerBankAccountPort {

    private final LoadSellerBankAccountRegistrationPort registrationPort;
    private final PlaceholderSellerBankAccountAdapter fallback;

    public RegistrySellerBankAccountAdapter(LoadSellerBankAccountRegistrationPort registrationPort,
                                            PlaceholderSellerBankAccountAdapter fallback) {
        this.registrationPort = registrationPort;
        this.fallback = fallback;
    }

    @Override
    public Optional<SellerBankAccount> findBySellerId(Long sellerId) {
        if (sellerId == null) {
            return Optional.empty();
        }
        return registrationPort.findBySellerId(sellerId)
                .map(SellerBankAccountRegistration::toBankAccount)
                .or(() -> fallback.findBySellerId(sellerId));
    }
}
