package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.in.ResolveSettlementWithholdingUseCase;
import github.lms.lemuel.tax.domain.TaxType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementConfirmItemWriterTest {

    @Mock SaveSettlementPort saveSettlementPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Mock PublishSettlementEventPort publishSettlementEventPort;
    @Mock ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase;
    SimpleMeterRegistry meterRegistry;
    SettlementConfirmItemWriter writer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        writer = new SettlementConfirmItemWriter(saveSettlementPort, loadSellerIdPort,
                publishSettlementDomainEventPort, enqueueLedgerTaskPort, publishSettlementEventPort,
                resolveSettlementWithholdingUseCase, meterRegistry);
        // 기본: 세무 프로필 미등록(사업자 취급, 원천징수 0) — 등록된 개인 셀러 케이스는 개별 테스트가 재스텁한다.
        lenient().when(resolveSettlementWithholdingUseCase.resolveForPayout(any(), any()))
                .thenReturn(WithholdingResolution.unregistered());
    }

    private Settlement confirmed(long id) {
        Settlement s = Settlement.createFromPayment(id, id + 10, new BigDecimal("10000"), LocalDate.now());
        s.assignId(id);
        s.confirm();
        return s;
    }

    @Test
    @DisplayName("청크의 각 정산 저장 + 원장 enqueue·ES 이벤트는 청크 id 목록으로 1회씩 발행")
    void writesChunkAndPublishesOnce() throws Exception {
        Settlement s1 = confirmed(1L);
        Settlement s2 = confirmed(2L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(loadSellerIdPort.findSellerIdByPaymentId(2L)).thenReturn(Optional.of(92L));

        writer.write(new Chunk<>(List.of(s1, s2)));

        verify(saveSettlementPort).save(s1);
        verify(saveSettlementPort).save(s2);
        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(1L), eq(91L), any());
        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(2L), eq(92L), any());
        verify(enqueueLedgerTaskPort).enqueueCreate(List.of(1L, 2L));
        verify(publishSettlementEventPort).publishSettlementConfirmedEvent(List.of(1L, 2L));
        // 세무 프로필 미등록(기본 스텁) — 원천징수 이벤트 발행 없음.
        verify(publishSettlementDomainEventPort, never()).publishWithholdingAccrued(anyLong(), anyLong(), any());
        // 확정 건수·금액 메트릭: 2건, net 합은 두 정산의 net_amount 합과 일치.
        assertThat(meterRegistry.counter("settlement.confirmed.count").count()).isEqualTo(2.0);
        double expectedNet = s1.getNetAmount().add(s2.getNetAmount()).doubleValue();
        assertThat(meterRegistry.counter("settlement.confirmed.amount").count()).isEqualTo(expectedNet);
    }

    @Test
    @DisplayName("판매자 미해석 정산은 SettlementConfirmed(loan) 발행 생략")
    void skipsLoanEventWhenSellerUnresolved() throws Exception {
        Settlement s1 = confirmed(1L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.empty());

        writer.write(new Chunk<>(List.of(s1)));

        verify(publishSettlementDomainEventPort, never()).publishSettlementConfirmed(anyLong(), anyLong(), any());
        verify(enqueueLedgerTaskPort).enqueueCreate(List.of(1L));
    }

    @Test
    @DisplayName("빈 청크면 원장 enqueue·ES 이벤트 발행 안 함")
    void emptyChunkNoSideEffects() throws Exception {
        writer.write(new Chunk<>(List.of()));

        verify(enqueueLedgerTaskPort, never()).enqueueCreate(any());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(any());
    }

    // L-3: 확정 경로는 지급을 만들 수 없다 — RequestPayoutUseCase·OffsetSellerRecoveryUseCase 의존을
    // 아예 제거해 구조로 보장했다(생성자에 없으므로 호출 자체가 불가능). 금액 산정 검증은
    // ApplyLoanDeductionServiceTest 가 담당한다.

    @Test
    @DisplayName("확정 시점에도 원천징수는 확정·발행된다 — 순서 1순위라 뒤 단계에 의존하지 않는다")
    void confirm_stillAccruesWithholding() throws Exception {
        Settlement s1 = confirmed(1L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, new BigDecimal("320")));

        writer.write(new Chunk<>(List.of(s1)));

        verify(publishSettlementDomainEventPort)
                .publishWithholdingAccrued(1L, 91L, new BigDecimal("320"));
    }
}
