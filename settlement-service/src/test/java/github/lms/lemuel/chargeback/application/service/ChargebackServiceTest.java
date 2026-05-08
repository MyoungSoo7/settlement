package github.lms.lemuel.chargeback.application.service;

import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase.OpenChargebackCommand;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.application.port.out.SaveChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChargebackService — application 계층 분기 검증.
 * 도메인 자체 검증은 ChargebackTest 가 책임. 여기서는:
 *   - PG webhook 멱등 (같은 pgChargebackId 두 번 통지 시 1 회만 OPEN)
 *   - ACCEPT 시 settlementId 가 있으면 SettlementAdjustment 자동 생성
 *   - ACCEPT 시 settlementId 가 없으면 adjustment 생성 안 함
 *   - REJECT 는 adjustment 영향 없음
 */
class ChargebackServiceTest {

    private LoadChargebackPort loadPort;
    private SaveChargebackPort savePort;
    private SaveSettlementAdjustmentPort saveAdjustmentPort;
    private ChargebackService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadChargebackPort.class);
        savePort = mock(SaveChargebackPort.class);
        saveAdjustmentPort = mock(SaveSettlementAdjustmentPort.class);
        service = new ChargebackService(loadPort, savePort, saveAdjustmentPort, new SimpleMeterRegistry());

        // savePort 는 입력을 그대로 (id=999 부여 후) 반환하는 echo 스텁
        when(savePort.save(any(Chargeback.class))).thenAnswer(inv -> {
            Chargeback c = inv.getArgument(0);
            if (c.getId() == null) c.assignId(999L);
            return c;
        });
    }

    @Nested
    class Open_멱등 {

        @Test
        void PG_WEBHOOK_같은_pgChargebackId_두_번_통지하면_한_번만_OPEN() {
            String pgId = "PG-CB-DUP-1";
            // 1 차: 미존재
            when(loadPort.findByPgChargebackId(pgId)).thenReturn(Optional.empty());

            Chargeback first = service.open(new OpenChargebackCommand(
                    1L, 100L, BigDecimal.valueOf(10_000),
                    ChargebackReason.FRAUD, "fraud", ChargebackSource.PG_WEBHOOK, pgId));

            // 2 차: 1 차 결과를 멱등 lookup 이 찾는 시뮬레이션
            when(loadPort.findByPgChargebackId(pgId)).thenReturn(Optional.of(first));

            Chargeback second = service.open(new OpenChargebackCommand(
                    1L, 100L, BigDecimal.valueOf(10_000),
                    ChargebackReason.FRAUD, "fraud", ChargebackSource.PG_WEBHOOK, pgId));

            assertThat(second.getId()).isEqualTo(first.getId());
            // savePort.save 는 1 차 호출 1 번만
            verify(savePort, times(1)).save(any(Chargeback.class));
        }

        @Test
        void MANUAL_은_pg_id_없으니_멱등_lookup_안_함() {
            service.open(new OpenChargebackCommand(
                    2L, 200L, BigDecimal.valueOf(5_000),
                    ChargebackReason.OTHER, "manual", ChargebackSource.MANUAL, null));

            verify(loadPort, never()).findByPgChargebackId(any());
            verify(savePort, times(1)).save(any(Chargeback.class));
        }
    }

    @Nested
    class Accept {

        @Test
        void settlementId_있으면_SettlementAdjustment_생성() {
            Chargeback existing = Chargeback.open(
                    1L, 100L, BigDecimal.valueOf(15_000),
                    ChargebackReason.FRAUD, "fraud", ChargebackSource.PG_WEBHOOK, "PG-A");
            existing.assignId(42L);
            when(loadPort.findById(42L)).thenReturn(Optional.of(existing));

            service.accept(42L, "admin@lemuel.io", "셀러 응답 없음");

            ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
            verify(saveAdjustmentPort, times(1)).save(captor.capture());

            SettlementAdjustment adj = captor.getValue();
            assertThat(adj.getSettlementId()).isEqualTo(100L);
            assertThat(adj.getChargebackId()).isEqualTo(42L);
            assertThat(adj.getRefundId()).isNull();   // XOR 제약 — chargeback 경로
            assertThat(adj.getAmount()).isEqualByComparingTo("-15000");  // 음수 기록
        }

        @Test
        void settlementId_null_이면_adjustment_생성_안_함() {
            Chargeback existing = Chargeback.open(
                    1L, null, BigDecimal.valueOf(8_000),
                    ChargebackReason.FRAUD, null, ChargebackSource.PG_WEBHOOK, "PG-B");
            existing.assignId(43L);
            when(loadPort.findById(43L)).thenReturn(Optional.of(existing));

            Chargeback result = service.accept(43L, "admin", "정산 전 발생 — adjustment 백필 필요");

            assertThat(result.isAccepted()).isTrue();
            verify(saveAdjustmentPort, never()).save(any());
        }

        @Test
        void 존재하지_않는_chargeback_은_거부() {
            when(loadPort.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.accept(999L, "admin", "n/a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    class Reject {

        @Test
        void REJECT_는_adjustment_생성_안_함() {
            Chargeback existing = Chargeback.open(
                    1L, 100L, BigDecimal.valueOf(15_000),
                    ChargebackReason.FRAUD, "fraud", ChargebackSource.MANUAL, null);
            existing.assignId(50L);
            when(loadPort.findById(50L)).thenReturn(Optional.of(existing));

            Chargeback result = service.reject(50L, "admin", "셀러 배송증명 운송장 ZX-99 제출");

            assertThat(result.isRejected()).isTrue();
            verify(saveAdjustmentPort, never()).save(any());
        }
    }

}
