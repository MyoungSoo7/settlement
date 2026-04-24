package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.DuplicateJournalEntryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private SaveJournalEntryPort saveJournalEntryPort;

    @Mock
    private LoadAccountPort loadAccountPort;

    @InjectMocks
    private LedgerService ledgerService;

    private final Account platformCash = new Account(1L, "PLATFORM_CASH", "현금", AccountType.ASSET, null);
    private final Account sellerPayable = new Account(2L, "SELLER_PAYABLE:42", "판매자", AccountType.LIABILITY, null);
    private final Account commission = new Account(3L, "PLATFORM_COMMISSION", "수수료", AccountType.REVENUE, null);

    @Test
    @DisplayName("정산 생성 분개를 기록한다")
    void 정산_분개_기록() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "SETTLEMENT:1", "정산 생성"
        );

        JournalEntry result = ledgerService.recordJournalEntry(entry);

        verify(saveJournalEntryPort).save(any());
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("중복 idempotencyKey는 DuplicateJournalEntryException을 던진다")
    void 중복_멱등키_예외() {
        given(saveJournalEntryPort.existsByIdempotencyKey("SETTLEMENT:1")).willReturn(true);

        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "SETTLEMENT:1", "정산 생성"
        );

        assertThatThrownBy(() -> ledgerService.recordJournalEntry(entry))
                .isInstanceOf(DuplicateJournalEntryException.class);

        verify(saveJournalEntryPort, never()).save(any());
    }

    @Test
    @DisplayName("정산+수수료 분개를 한 번에 기록한다")
    void 정산_수수료_분개() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(loadAccountPort.getOrCreate(any())).willAnswer(inv -> inv.getArgument(0));
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money paymentAmount = Money.krw(new BigDecimal("10000"));
        Money commissionAmount = Money.krw(new BigDecimal("300"));

        ledgerService.recordSettlementCreated(1L, 42L, paymentAmount, commissionAmount);

        // 2번 호출: 정산 분개 + 수수료 분개
        verify(saveJournalEntryPort, times(2)).save(any());
    }

    @Test
    @DisplayName("환불 분개를 기록한다 (3-line: 판매자 차감 + 수수료 역산 + 현금 유출)")
    void 환불_분개_기록() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(loadAccountPort.getOrCreate(any())).willAnswer(inv -> inv.getArgument(0));
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money refundAmount = Money.krw(new BigDecimal("3000"));
        Money commissionReversal = Money.krw(new BigDecimal("90"));

        ledgerService.recordRefundProcessed(1L, 42L, refundAmount, commissionReversal);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(saveJournalEntryPort).save(captor.capture());

        JournalEntry entry = captor.getValue();
        assertThat(entry.getLines()).hasSize(3);
        assertThat(entry.getEntryType()).isEqualTo("REFUND_PROCESSED");
        assertThat(entry.totalDebit()).isEqualTo(refundAmount);
        assertThat(entry.totalCredit()).isEqualTo(refundAmount);
    }
}
