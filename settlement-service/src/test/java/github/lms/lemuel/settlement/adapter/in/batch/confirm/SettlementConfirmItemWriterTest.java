package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
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
    @Mock RequestPayoutUseCase requestPayoutUseCase;
    @Mock OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase;
    @Mock ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase;
    SimpleMeterRegistry meterRegistry;
    SettlementConfirmItemWriter writer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        writer = new SettlementConfirmItemWriter(saveSettlementPort, loadSellerIdPort,
                publishSettlementDomainEventPort, enqueueLedgerTaskPort, publishSettlementEventPort,
                requestPayoutUseCase, offsetSellerRecoveryUseCase, resolveSettlementWithholdingUseCase, meterRegistry);
        // 기본: 상계 없음 — 상계 케이스는 개별 테스트가 재스텁한다.
        lenient().when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(
                any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
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
        // 확정 경로에서 즉시지급 Payout(IMMEDIATE, 홀드백·원천징수 없어 net 전액)이 정산별로 생성된다.
        verify(requestPayoutUseCase).requestPayoutOfType(1L, 91L, s1.getImmediatePayoutAmount(), PayoutType.IMMEDIATE);
        verify(requestPayoutUseCase).requestPayoutOfType(2L, 92L, s2.getImmediatePayoutAmount(), PayoutType.IMMEDIATE);
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
        // 판매자 미해석이면 Payout 도 생성하지 않는다(반쪽 지급 방지).
        verify(requestPayoutUseCase, never()).requestPayoutOfType(anyLong(), anyLong(), any(), any());
        verify(enqueueLedgerTaskPort).enqueueCreate(List.of(1L));
    }

    @Test
    @DisplayName("빈 청크면 원장 enqueue·ES 이벤트 발행 안 함")
    void emptyChunkNoSideEffects() throws Exception {
        writer.write(new Chunk<>(List.of()));

        verify(enqueueLedgerTaskPort, never()).enqueueCreate(any());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(any());
    }

    @Test
    @DisplayName("ADR 0029 §B(HIGH #4 봉합): 개인 셀러 원천징수는 payout 금액에서 실제 공제되고 이벤트가 발행된다")
    void individualSeller_withholdingDeductedFromPayout_andEventPublished() throws Exception {
        Settlement s1 = confirmed(1L); // paymentAmount=10000, commission 3%=300, net=9700, holdback 없음
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        // 원천징수 = floor(9700*0.033) = floor(320.1) = 320.
        BigDecimal withholding = new BigDecimal("320");
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, withholding));

        writer.write(new Chunk<>(List.of(s1)));

        BigDecimal expectedPayout = s1.getImmediatePayoutAmount().subtract(withholding);
        verify(requestPayoutUseCase).requestPayoutOfType(1L, 91L, expectedPayout, PayoutType.IMMEDIATE);
        verify(publishSettlementDomainEventPort).publishWithholdingAccrued(1L, 91L, withholding);
    }

    @Test
    @DisplayName("사업자 셀러는 원천징수 0 — payout 전액 지급, 이벤트 미발행")
    void businessSeller_noWithholding() throws Exception {
        Settlement s1 = confirmed(1L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.BUSINESS, BigDecimal.ZERO));

        writer.write(new Chunk<>(List.of(s1)));

        verify(requestPayoutUseCase).requestPayoutOfType(1L, 91L, s1.getImmediatePayoutAmount(), PayoutType.IMMEDIATE);
        verify(publishSettlementDomainEventPort, never()).publishWithholdingAccrued(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("세무 프로필 미등록 셀러는 사업자 취급 — 원천징수 미적용, 전액 지급(정책 명시)")
    void unregisteredSeller_treatedAsBusiness_fullPayout() throws Exception {
        Settlement s1 = confirmed(1L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        // setUp() 의 기본 스텁이 이미 unregistered() 를 반환 — 명시적으로 재확인.

        writer.write(new Chunk<>(List.of(s1)));

        verify(requestPayoutUseCase).requestPayoutOfType(1L, 91L, s1.getImmediatePayoutAmount(), PayoutType.IMMEDIATE);
        verify(publishSettlementDomainEventPort, never()).publishWithholdingAccrued(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("원천징수가 즉시지급 가용액을 초과하면 실제 징수 가능액으로 캡핑(GL 과대계상 방지)")
    void withholdingExceedsImmediate_capsToAvailable() throws Exception {
        Settlement s1 = confirmed(1L); // immediate = net(9700) - holdback(0) = 9700, 상계 0
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        BigDecimal hugeWithholding = new BigDecimal("999999");
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, hugeWithholding));

        writer.write(new Chunk<>(List.of(s1)));

        // 가용액(=immediate, 상계 0)까지만 원천징수 → payout 0, 발행도 실제 징수액(=가용액)만.
        // payout 감액과 발행값이 동일해야 account GL 통제계정이 0으로 닫힌다(GL 감사 HIGH 봉합).
        BigDecimal available = s1.getImmediatePayoutAmount();
        verify(requestPayoutUseCase).requestPayoutOfType(1L, 91L, available.subtract(available), PayoutType.IMMEDIATE);
        verify(publishSettlementDomainEventPort).publishWithholdingAccrued(1L, 91L, available);
    }

    @Test
    @DisplayName("T-4: 회수 채권이 즉시지급분을 삼켜도 원천징수가 먼저 확보된다 — 상계는 잔여까지만")
    void withholdingTakesPrecedenceOverRecoveryOffset() throws Exception {
        Settlement s1 = confirmed(1L);            // net=9700, holdback 없음 → immediate=9700
        BigDecimal immediate = s1.getImmediatePayoutAmount();
        BigDecimal withholding = new BigDecimal("320");   // floor(9700 × 0.033)
        BigDecimal openDebt = new BigDecimal("50000");    // 즉시지급분을 훨씬 넘는 미상계 채권

        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, withholding));
        // 실제 상계 서비스와 같은 계약: 넘겨받은 가용액까지만 소진하고 나머지 채권은 OPEN 으로 남긴다.
        when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(eq(1L), eq(91L), any(), any()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(2)).min(openDebt));

        writer.write(new Chunk<>(List.of(s1)));

        // 원천징수는 전액 확보된다(과소징수 0) — 캡핑되지 않는다.
        verify(publishSettlementDomainEventPort).publishWithholdingAccrued(1L, 91L, withholding);
        // 상계에 넘기는 가용액은 '원천징수를 뗀 뒤' 잔여다 — 세금 재원을 채권 회수가 잠식하지 못한다.
        verify(offsetSellerRecoveryUseCase).offsetForConfirmedSettlement(
                eq(1L), eq(91L), eq(immediate.subtract(withholding)), any());
        // 잔여를 상계가 전부 가져가므로 셀러 지급은 0. 미상계 채권은 다음 정산으로 이월된다(기존 경로).
        // 금액 비교는 스케일 무관 compareTo 로 한다 — Mockito 기본 매처는 equals 라 0 과 0.00 을 다르게 본다.
        ArgumentCaptor<BigDecimal> payoutCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(requestPayoutUseCase).requestPayoutOfType(eq(1L), eq(91L), payoutCaptor.capture(),
                eq(PayoutType.IMMEDIATE));
        assertThat(payoutCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("T-4: 상계가 잔여보다 작으면 셀러가 차액을 받는다 — 순서 변경이 지급액을 줄이지 않는다")
    void partialOffsetStillPaysRemainderToSeller() throws Exception {
        Settlement s1 = confirmed(1L);            // immediate=9700
        BigDecimal immediate = s1.getImmediatePayoutAmount();
        BigDecimal withholding = new BigDecimal("320");
        BigDecimal openDebt = new BigDecimal("1000");     // 잔여(9380)보다 작은 채권

        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(91L, s1.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, withholding));
        when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(eq(1L), eq(91L), any(), any()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(2)).min(openDebt));

        writer.write(new Chunk<>(List.of(s1)));

        // 지급액 = immediate − 원천징수 − 상계. 세 값의 합은 순서와 무관하게 immediate 로 닫힌다.
        verify(requestPayoutUseCase).requestPayoutOfType(
                1L, 91L, immediate.subtract(withholding).subtract(openDebt), PayoutType.IMMEDIATE);
        verify(publishSettlementDomainEventPort).publishWithholdingAccrued(1L, 91L, withholding);
    }
}
