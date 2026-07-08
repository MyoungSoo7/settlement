package github.lms.lemuel.payout.adapter.out.firmbanking;

import github.lms.lemuel.payout.application.port.out.FirmBankingPort.FirmBankingException;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MockFirmBankingAdapter — 송금 시뮬레이션")
class MockFirmBankingAdapterTest {

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "123-45-678901", "홍길동");

    @Test
    @DisplayName("failureRate 0 이면 FB- 접두 거래 ID 를 반환")
    void send_success() {
        MockFirmBankingAdapter adapter = new MockFirmBankingAdapter();
        ReflectionTestUtils.setField(adapter, "failureRate", 0.0);

        String txnId = adapter.send(ACCOUNT, new BigDecimal("50000"), "PAYOUT-1");

        assertThat(txnId).startsWith("FB-");
    }

    @Test
    @DisplayName("failureRate 1 이면 MOCK_RANDOM_FAIL 로 FirmBankingException")
    void send_forcedFailure() {
        MockFirmBankingAdapter adapter = new MockFirmBankingAdapter();
        ReflectionTestUtils.setField(adapter, "failureRate", 1.0);

        assertThatThrownBy(() -> adapter.send(ACCOUNT, new BigDecimal("50000"), "PAYOUT-1"))
                .isInstanceOf(FirmBankingException.class)
                .satisfies(e -> assertThat(((FirmBankingException) e).getErrorCode()).isEqualTo("MOCK_RANDOM_FAIL"));
    }

    @Test
    @DisplayName("FirmBankingException: errorCode·message·cause 보존")
    void exceptionCarriesErrorCodeAndCause() {
        Throwable cause = new IllegalStateException("소켓 끊김");
        FirmBankingException ex = new FirmBankingException("NETWORK", "연결 실패", cause);

        assertThat(ex.getErrorCode()).isEqualTo("NETWORK");
        assertThat(ex.getMessage()).isEqualTo("연결 실패");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
