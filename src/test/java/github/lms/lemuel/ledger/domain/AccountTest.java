package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AccountTest {

    @Test
    @DisplayName("계정을 코드와 이름, 타입으로 생성한다")
    void 계정_생성() {
        Account account = Account.create("PLATFORM_CASH", "플랫폼 보유 현금", AccountType.ASSET);
        assertThat(account.getCode()).isEqualTo("PLATFORM_CASH");
        assertThat(account.getName()).isEqualTo("플랫폼 보유 현금");
        assertThat(account.getType()).isEqualTo(AccountType.ASSET);
    }

    @Test
    @DisplayName("판매자별 동적 계정 코드를 생성한다")
    void 판매자_계정_코드() {
        Account account = Account.createSellerPayable(42L);
        assertThat(account.getCode()).isEqualTo("SELLER_PAYABLE:42");
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
    }

    @Test
    @DisplayName("빈 코드로 생성하면 예외가 발생한다")
    void 빈_코드_예외() {
        assertThatThrownBy(() -> Account.create("", "name", AccountType.ASSET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 타입으로 생성하면 예외가 발생한다")
    void null_타입_예외() {
        assertThatThrownBy(() -> Account.create("CODE", "name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ASSET 계정은 DEBIT이 정상 방향이다")
    void ASSET_정상방향_DEBIT() {
        assertThat(AccountType.ASSET.normalSide()).isEqualTo(DebitCredit.DEBIT);
    }

    @Test
    @DisplayName("LIABILITY 계정은 CREDIT이 정상 방향이다")
    void LIABILITY_정상방향_CREDIT() {
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo(DebitCredit.CREDIT);
    }

    @Test
    @DisplayName("REVENUE 계정은 CREDIT이 정상 방향이다")
    void REVENUE_정상방향_CREDIT() {
        assertThat(AccountType.REVENUE.normalSide()).isEqualTo(DebitCredit.CREDIT);
    }

    @Test
    @DisplayName("EXPENSE 계정은 DEBIT이 정상 방향이다")
    void EXPENSE_정상방향_DEBIT() {
        assertThat(AccountType.EXPENSE.normalSide()).isEqualTo(DebitCredit.DEBIT);
    }
}
