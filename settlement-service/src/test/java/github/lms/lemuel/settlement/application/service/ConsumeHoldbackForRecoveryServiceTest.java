package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ConsumeHoldbackForRecoveryUseCase.HoldbackConsumption;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 지급후 회수분의 홀드백 소진 — 정산 애그리거트를 여는 쪽의 규약.
 *
 * <p>이 어서션들은 원래 {@code RecoverPostPayoutAdjustmentServiceTest} 에 있었다.
 * 홀드백 소진 실행을 소유 슬라이스로 되가져오면서 규약도 함께 옮겼다(누락 없이 이관).
 */
@ExtendWith(MockitoExtension.class)
class ConsumeHoldbackForRecoveryServiceTest {

    private static final BigDecimal RECOVERED = new BigDecimal("3000.00");

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;

    private ConsumeHoldbackForRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new ConsumeHoldbackForRecoveryService(loadSettlementPort, saveSettlementPort,
                loadSellerIdPort, publishSettlementDomainEventPort);
    }

    @Test
    @DisplayName("정산을 찾지 못하면 아무것도 바꾸지 않는다")
    void emptyWhenSettlementMissing() {
        when(loadSettlementPort.findById(501L)).thenReturn(Optional.empty());

        assertThat(service.consumeForRecovery(501L, 11L, RECOVERED)).isEmpty();

        verifyNoInteractions(saveSettlementPort, publishSettlementDomainEventPort);
    }

    @Test
    @DisplayName("셀러 미해석이면 홀드백을 건드리지 않는다 (조정 레코드가 수기 대응 근거)")
    void emptyWhenSellerUnresolved() {
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.empty());

        assertThat(service.consumeForRecovery(501L, 11L, RECOVERED)).isEmpty();

        verify(settlement, never()).consumeHoldbackForRefund(any());
        verifyNoInteractions(saveSettlementPort, publishSettlementDomainEventPort);
    }

    @Test
    @DisplayName("전액 흡수 — 정산을 저장하고 유보 소진(현금유출) 이벤트를 발행한다")
    void consumesFully() {
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(RECOVERED);

        Optional<HoldbackConsumption> result = service.consumeForRecovery(501L, 11L, RECOVERED);

        assertThat(result).isPresent();
        assertThat(result.get().sellerId()).isEqualTo(7L);
        assertThat(result.get().consumed()).isEqualByComparingTo(RECOVERED);
        verify(saveSettlementPort).save(settlement);
        verify(publishSettlementDomainEventPort).publishHoldbackConsumed(11L, 501L, 7L, RECOVERED);
    }

    @Test
    @DisplayName("부분 흡수 — 깎인 만큼만 이벤트로 나간다")
    void consumesPartially() {
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(new BigDecimal("1000.00"));

        Optional<HoldbackConsumption> result = service.consumeForRecovery(501L, 11L, RECOVERED);

        assertThat(result).isPresent();
        assertThat(result.get().consumed()).isEqualByComparingTo("1000.00");
        verify(saveSettlementPort).save(settlement);
        verify(publishSettlementDomainEventPort)
                .publishHoldbackConsumed(11L, 501L, 7L, new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("흡수 0 — 저장도 이벤트도 없이 결과만 돌려준다")
    void consumesNothing() {
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(BigDecimal.ZERO);

        Optional<HoldbackConsumption> result = service.consumeForRecovery(501L, 11L, RECOVERED);

        assertThat(result).isPresent();
        assertThat(result.get().consumed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(saveSettlementPort, never()).save(any());
        verify(publishSettlementDomainEventPort, never())
                .publishHoldbackConsumed(anyLong(), any(), anyLong(), any());
    }

    private Settlement stubSettlement(Long settlementId, Long paymentId) {
        Settlement settlement = mock(Settlement.class);
        when(settlement.getPaymentId()).thenReturn(paymentId);
        when(loadSettlementPort.findById(settlementId)).thenReturn(Optional.of(settlement));
        return settlement;
    }
}
