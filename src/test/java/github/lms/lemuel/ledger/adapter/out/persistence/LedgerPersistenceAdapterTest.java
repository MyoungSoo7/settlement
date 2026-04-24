package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({LedgerPersistenceAdapter.class, AccountPersistenceAdapter.class,
         AccountBalancePersistenceAdapter.class, LedgerPersistenceMapper.class})
@EntityScan(basePackages = "github.lms.lemuel.ledger.adapter.out.persistence")
@EnableJpaRepositories(basePackages = "github.lms.lemuel.ledger.adapter.out.persistence")
class LedgerPersistenceAdapterTest {

    @Autowired
    private LedgerPersistenceAdapter ledgerAdapter;

    @Autowired
    private AccountPersistenceAdapter accountAdapter;

    @Autowired
    private AccountBalancePersistenceAdapter balanceAdapter;

    @Autowired
    private SpringDataAccountJpaRepository accountRepository;

    private Account platformCash;
    private Account sellerPayable;

    @BeforeEach
    void setUp() {
        platformCash = accountAdapter.getOrCreate(Account.createPlatformCash());
        sellerPayable = accountAdapter.getOrCreate(Account.createSellerPayable(42L));
    }

    @Test
    @DisplayName("JournalEntry를 저장하고 ID가 생성된다")
    void 분개_저장() {
        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "TEST:1", "테스트"
        );

        JournalEntry saved = ledgerAdapter.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getId()).isNotNull();
    }

    @Test
    @DisplayName("idempotencyKey 중복 여부를 확인한다")
    void 멱등키_확인() {
        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "TEST", "TEST", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "IDEM:1", "test"
        );
        ledgerAdapter.save(entry);

        assertThat(ledgerAdapter.existsByIdempotencyKey("IDEM:1")).isTrue();
        assertThat(ledgerAdapter.existsByIdempotencyKey("IDEM:2")).isFalse();
    }

    @Test
    @DisplayName("referenceType과 referenceId로 분개를 조회한다")
    void 참조_조회() {
        Money amount = Money.krw(new BigDecimal("10000"));
        ledgerAdapter.save(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 99L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "REF_TEST:1", "test"
        ));

        List<JournalEntry> entries = ledgerAdapter.findByReference("SETTLEMENT", 99L);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryType()).isEqualTo("SETTLEMENT_CREATED");
    }

    @Test
    @DisplayName("계정 잔액을 정확하게 계산한다")
    void 잔액_계산() {
        Money amount = Money.krw(new BigDecimal("10000"));
        ledgerAdapter.save(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "BALANCE_TEST:1", "test"
        ));

        // PLATFORM_CASH (ASSET): debit increases → balance = +10000
        Money cashBalance = balanceAdapter.getBalance(platformCash.getId());
        assertThat(cashBalance.amount()).isEqualByComparingTo("10000.00");

        // SELLER_PAYABLE (LIABILITY): credit increases → balance = +10000
        Money payableBalance = balanceAdapter.getBalance(sellerPayable.getId());
        assertThat(payableBalance.amount()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("getOrCreate은 기존 계정을 재사용한다")
    void 계정_재사용() {
        Account first = accountAdapter.getOrCreate(Account.createPlatformCash());
        Account second = accountAdapter.getOrCreate(Account.createPlatformCash());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(accountRepository.count()).isEqualTo(2); // platformCash + sellerPayable (setUp)
    }
}
