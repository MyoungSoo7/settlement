package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceLedgerTest {

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
    @DisplayName("정산 생성 시 Ledger 분개도 함께 기록된다")
    void 정산_생성_시_레저_분개() {
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
                eq(1L),
                eq(42L),
                any(Money.class),
                any(Money.class)
        );
    }
}
