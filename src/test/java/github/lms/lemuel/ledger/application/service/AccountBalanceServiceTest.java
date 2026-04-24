package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AccountBalanceServiceTest {

    @Mock
    private LoadAccountPort loadAccountPort;

    @Mock
    private LoadAccountBalancePort loadAccountBalancePort;

    @InjectMocks
    private AccountBalanceService accountBalanceService;

    @Test
    @DisplayName("계정 코드로 잔액을 조회한다")
    void 잔액_조회() {
        Account account = new Account(1L, "SELLER_PAYABLE:42", "판매자", AccountType.LIABILITY, null);
        given(loadAccountPort.findByCode("SELLER_PAYABLE:42")).willReturn(Optional.of(account));
        given(loadAccountBalancePort.getBalance(1L)).willReturn(Money.krw(new BigDecimal("9700")));

        Money balance = accountBalanceService.getBalance("SELLER_PAYABLE:42");

        assertThat(balance.amount()).isEqualByComparingTo("9700.00");
    }

    @Test
    @DisplayName("존재하지 않는 계정 코드는 예외를 던진다")
    void 존재하지_않는_계정() {
        given(loadAccountPort.findByCode("NONEXISTENT")).willReturn(Optional.empty());

        assertThatThrownBy(() -> accountBalanceService.getBalance("NONEXISTENT"))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
