package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import github.lms.lemuel.seller.domain.SettlementCycle;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceSellerTest {

    @Mock
    private LoadCapturedPaymentsPort loadCapturedPaymentsPort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private SettlementSearchIndexPort settlementSearchIndexPort;

    @Mock
    private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @Mock
    private LoadSellerPort loadSellerPort;

    @InjectMocks
    private CreateDailySettlementsService service;

    @Test
    @DisplayName("판매자별 수수료율을 적용하여 정산을 생성한다")
    void 판매자별_수수료_적용() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();
        seller.updateCommissionRate(new BigDecimal("0.05")); // 5% 수수료

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        given(loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(targetDate, 42L))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).save(captor.capture());

        Settlement saved = captor.getValue();
        assertThat(saved.getSellerId()).isEqualTo(42L);
        assertThat(saved.getCommission()).isEqualByComparingTo("500.00"); // 10000 * 5%
        assertThat(saved.getNetAmount()).isEqualByComparingTo("9500.00");
    }

    @Test
    @DisplayName("WEEKLY 판매자는 해당 요일이 아니면 정산하지 않는다")
    void WEEKLY_판매자_필터링() {
        // 2026-04-25 = FRIDAY
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();
        seller.updateSettlementCycle(SettlementCycle.WEEKLY, java.time.DayOfWeek.MONDAY, null);

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        // NOTE: Do NOT stub settlementSearchIndexPort.isSearchEnabled() here!
        // The seller is filtered out, so dueSellers is empty, service returns early before reaching ES code.
        // Stubbing it would cause UnnecessaryStubbingException with Mockito strict stubs.

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        // seller filtered out → no payment lookup, no save
        verify(loadCapturedPaymentsPort, never()).findCapturedPaymentsByDateAndSeller(any(), any());
        verify(saveSettlementPort, never()).save(any());
    }

    @Test
    @DisplayName("Ledger 분개에 실제 sellerId가 전달된다")
    void 레저_분개에_셀러ID_전달() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        given(loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(targetDate, 42L))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        verify(recordJournalEntryUseCase).recordSettlementCreated(
                eq(1L),           // settlementId
                eq(42L),          // sellerId
                any(Money.class), // paymentAmount
                any(Money.class)  // commission
        );
    }
}
