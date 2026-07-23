package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerBankAccountRegistrationTest {

    @Test
    @DisplayName("register: 필드 보존 + updatedAt 세팅 + 스냅샷 변환")
    void register() {
        SellerBankAccountRegistration reg = SellerBankAccountRegistration.register(
                7L, "KB", "123-45-678901", "홍길동");

        assertThat(reg.getSellerId()).isEqualTo(7L);
        assertThat(reg.getBankCode()).isEqualTo("KB");
        assertThat(reg.getAccountNumber()).isEqualTo("123-45-678901");
        assertThat(reg.getAccountHolder()).isEqualTo("홍길동");
        assertThat(reg.getUpdatedAt()).isNotNull();

        SellerBankAccount snapshot = reg.toBankAccount();
        assertThat(snapshot.bankCode()).isEqualTo("KB");
        assertThat(snapshot.bankAccountNumber()).isEqualTo("123-45-678901");
        assertThat(snapshot.accountHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("register: 필수값 누락 시 타입 예외")
    void register_rejectsBlank() {
        assertThatThrownBy(() -> SellerBankAccountRegistration.register(null, "KB", "1", "홍"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThatThrownBy(() -> SellerBankAccountRegistration.register(1L, " ", "1", "홍"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThatThrownBy(() -> SellerBankAccountRegistration.register(1L, "KB", "", "홍"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThatThrownBy(() -> SellerBankAccountRegistration.register(1L, "KB", "1", null))
                .isInstanceOf(PayoutInvariantViolationException.class);
    }

    @Test
    @DisplayName("changeAccount: 정정 계좌로 교체 + updatedAt 갱신")
    void changeAccount() {
        SellerBankAccountRegistration reg = SellerBankAccountRegistration.register(
                7L, "KB", "111-11-111111", "홍길동");

        reg.changeAccount("SHINHAN", "222-22-222222", "홍길동");

        assertThat(reg.getBankCode()).isEqualTo("SHINHAN");
        assertThat(reg.getAccountNumber()).isEqualTo("222-22-222222");
        assertThat(reg.toBankAccount().bankAccountNumber()).isEqualTo("222-22-222222");
    }

    @Test
    @DisplayName("changeAccount: 필수값 누락 시 타입 예외 (원 상태 훼손 없이 거부)")
    void changeAccount_rejectsBlank() {
        SellerBankAccountRegistration reg = SellerBankAccountRegistration.register(
                7L, "KB", "111", "홍길동");

        assertThatThrownBy(() -> reg.changeAccount("SHINHAN", " ", "홍길동"))
                .isInstanceOf(PayoutInvariantViolationException.class);
    }

    @Test
    @DisplayName("rehydrate: 저장된 updatedAt 을 그대로 보존")
    void rehydrate() {
        LocalDateTime ts = LocalDateTime.of(2026, 7, 20, 9, 0);
        SellerBankAccountRegistration reg = SellerBankAccountRegistration.rehydrate(
                7L, "KB", "123", "홍길동", ts);

        assertThat(reg.getUpdatedAt()).isEqualTo(ts);
    }
}
