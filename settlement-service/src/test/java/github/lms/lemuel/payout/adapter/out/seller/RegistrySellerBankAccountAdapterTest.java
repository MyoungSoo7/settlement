package github.lms.lemuel.payout.adapter.out.seller;

import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrySellerBankAccountAdapterTest {

    private final LoadSellerBankAccountRegistrationPort registrationPort =
            mock(LoadSellerBankAccountRegistrationPort.class);
    private final PlaceholderSellerBankAccountAdapter fallback = new PlaceholderSellerBankAccountAdapter();
    private final RegistrySellerBankAccountAdapter adapter =
            new RegistrySellerBankAccountAdapter(registrationPort, fallback);

    @Test
    @DisplayName("등록 계좌가 있으면 레지스트리 계좌를 스냅샷으로 반환 (폴백 미사용)")
    void prefersRegisteredAccount() {
        when(registrationPort.findBySellerId(7L)).thenReturn(Optional.of(
                SellerBankAccountRegistration.register(7L, "SHINHAN", "222-22-222222", "홍길동")));

        Optional<SellerBankAccount> result = adapter.findBySellerId(7L);

        assertThat(result).isPresent();
        assertThat(result.get().bankCode()).isEqualTo("SHINHAN");
        assertThat(result.get().bankAccountNumber()).isEqualTo("222-22-222222");
        assertThat(result.get().accountHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("미등록이면 플레이스홀더로 폴백 (결정적 계좌)")
    void fallsBackToPlaceholder() {
        when(registrationPort.findBySellerId(42L)).thenReturn(Optional.empty());

        Optional<SellerBankAccount> result = adapter.findBySellerId(42L);

        assertThat(result).isPresent();
        assertThat(result.get().bankCode()).isEqualTo("KB");
        assertThat(result.get().bankAccountNumber()).isEqualTo("000-0000000042");
    }

    @Test
    @DisplayName("sellerId 가 null 이면 빈 값")
    void nullSellerId() {
        assertThat(adapter.findBySellerId(null)).isEmpty();
    }
}
