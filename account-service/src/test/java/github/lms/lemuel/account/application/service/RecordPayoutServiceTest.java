package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.out.AcquireSellerLockPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * payout 차변을 현재 SELLER_PAYABLE 잔액 기준으로 분할하는 라우팅 로직 단위테스트 (감사 MED-3 + HIGH 동시성).
 *
 * <p>잔액을 초과하는 실지급분은 회수채권(PAYOUT_ADVANCE)으로 인식해 통제계정을 음수로 몰지 않으며,
 * 잔액 읽기 전에 셀러 단위 락을 먼저 잡아 동시 payout 을 직렬화한다.
 */
@ExtendWith(MockitoExtension.class)
class RecordPayoutServiceTest {

    @Mock AcquireSellerLockPort acquireSellerLockPort;
    @Mock LoadAccountEntryPort loadAccountEntryPort;
    @Mock RecordAccountEntryUseCase recordAccountEntryUseCase;
    @InjectMocks RecordPayoutService service;

    private List<AccountEntry> captureRecorded(int times) {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase, times(times)).record(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void 잔액이_지급액_이상이면_payoutCompleted_1건만_전기한다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(new BigDecimal("50000"));

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        AccountEntry e = captureRecorded(1).get(0);
        assertThat(e.getRefType()).isEqualTo("PAYOUT_COMPLETED");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("43425"); // 전액 payable, advance 없음
    }

    @Test
    void 잔액이_0과_지급액_사이면_잔액만큼_payoutCompleted_초과분은_payoutAdvance로_분할한다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(new BigDecimal("30000"));

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        List<AccountEntry> entries = captureRecorded(2);
        AccountEntry payable = entries.stream()
                .filter(e -> e.getRefType().equals("PAYOUT_COMPLETED")).findFirst().orElseThrow();
        assertThat(payable.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(payable.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(payable.getAmount()).isEqualByComparingTo("30000"); // 잔액 상당

        AccountEntry advance = entries.stream()
                .filter(e -> e.getRefType().equals("PAYOUT_ADVANCE")).findFirst().orElseThrow();
        assertThat(advance.getDebitAccount()).isEqualTo(GlAccount.SELLER_RECOVERY_RECEIVABLE);
        assertThat(advance.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(advance.getAmount()).isEqualByComparingTo("13425"); // 초과분 = 43425 - 30000

        // 두 전표 합 = 지급액 → CASH 유출 총액 정확
        assertThat(payable.getAmount().add(advance.getAmount())).isEqualByComparingTo("43425");

        // 동시성(HIGH): 락 획득이 잔액 읽기보다 반드시 먼저 일어난다(무락 read-then-write 방어).
        InOrder order = inOrder(acquireSellerLockPort, loadAccountEntryPort);
        order.verify(acquireSellerLockPort).lockSellerForPayout("777");
        order.verify(loadAccountEntryPort).sellerPayableBalance("777");
    }

    @Test
    void 잔액이_0이하면_payoutAdvance_1건만_전기한다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(BigDecimal.ZERO);

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        AccountEntry e = captureRecorded(1).get(0);
        assertThat(e.getRefType()).isEqualTo("PAYOUT_ADVANCE");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_RECOVERY_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("43425"); // 전액 advance, payoutCompleted 없음
    }

    @Test
        // LOW-2: 이벤트 순서 역전(payoutCompleted 가 settlementCreatedImmediate 크레딧보다 먼저 도착) →
        // SELLER_PAYABLE 잔액이 아직 0 이므로 크레딧 없이 차변하면 통제계정이 일시 음수가 된다. MED-3 채권
        // 라우팅이 잔액 0 을 읽어 전액을 회수채권(PAYOUT_ADVANCE)으로 전기하므로 음수가 나지 않는다.
        // (후속 settlementCreatedImmediate 크레딧이 도착하면 recoveryOffset 로 상계돼 0 으로 닫힌다.)
    void 순서역전_payout이_정산크레딧보다_먼저_와_잔액0이면_음수없이_회수채권으로_전기한다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(BigDecimal.ZERO);

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        AccountEntry e = captureRecorded(1).get(0);
        assertThat(e.getRefType()).isEqualTo("PAYOUT_ADVANCE");                          // 음수 SELLER_PAYABLE 아님
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_RECOVERY_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
    }

    @Test
    void 잔액이_음수여도_전액_payoutAdvance로_전기하고_음수전표는_만들지_않는다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(new BigDecimal("-10000"));

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        AccountEntry e = captureRecorded(1).get(0);
        assertThat(e.getRefType()).isEqualTo("PAYOUT_ADVANCE");
        assertThat(e.getAmount()).isEqualByComparingTo("43425"); // max(0, 음수 잔액)=0 → 전액 advance
    }

    @Test
    void 잔액이_정확히_지급액과_같으면_payoutCompleted_1건_advance_없음() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(new BigDecimal("43425"));

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        // record 총 1회(captureRecorded(1)) + 그 1건이 PAYOUT_COMPLETED → advance 전기 없음이 증명된다.
        AccountEntry e = captureRecorded(1).get(0);
        assertThat(e.getRefType()).isEqualTo("PAYOUT_COMPLETED");
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
    }

    @Test
    void 지급전에_셀러_락을_먼저_잡는다() {
        when(loadAccountEntryPort.sellerPayableBalance("777")).thenReturn(new BigDecimal("50000"));

        service.recordPayout("777", "7001", new BigDecimal("43425"));

        verify(acquireSellerLockPort).lockSellerForPayout("777");
    }

    @Test
    void 금액이_음수면_명시적으로_거부하고_락도_전기도_하지_않는다() {
        assertThatThrownBy(() -> service.recordPayout("777", "7001", new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(acquireSellerLockPort, loadAccountEntryPort, recordAccountEntryUseCase);
    }

    @Test
    void 금액이_0이면_명시적으로_거부하고_무전표_no_op으로_삼키지_않는다() {
        assertThatThrownBy(() -> service.recordPayout("777", "7001", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(recordAccountEntryUseCase, never()).record(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(acquireSellerLockPort, loadAccountEntryPort);
    }

    @Test
    void 금액이_null이면_명시적으로_거부한다() {
        assertThatThrownBy(() -> service.recordPayout("777", "7001", null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(acquireSellerLockPort, loadAccountEntryPort, recordAccountEntryUseCase);
    }
}
