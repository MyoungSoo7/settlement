package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 정체 채권 수기 이관 — OPEN 채권 중 마지막 활동이 유예 기간을 넘긴 건을 MANUAL_REQUIRED 로 이관한다.
 *
 * <p>셀러가 활동을 멈추면(휴면·이탈) {@link OffsetSellerRecoveryService} 의 자동 상계 기회 자체가
 * 오지 않아 채권이 영구히 OPEN 으로 남는다 — 이 배치가 그 정체를 감지해 운영자 개입 대상으로 이관한다.
 */
@ExtendWith(MockitoExtension.class)
class EscalateStaleSellerRecoveryServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 3, 30, 0, 0, ZoneOffset.ofHours(9));
    private static final int MANUAL_ESCALATION_DAYS = 30;

    @Mock LoadSellerRecoveryPort loadPort;
    @Mock SaveSellerRecoveryPort savePort;

    private EscalateStaleSellerRecoveryService service;

    private void setUp(int manualEscalationDays) {
        service = new EscalateStaleSellerRecoveryService(loadPort, savePort, manualEscalationDays);
    }

    @Test
    @DisplayName("cutoff = now - manualEscalationDays 로 조회한다")
    void computesCutoffFromConfiguredGracePeriod() {
        setUp(MANUAL_ESCALATION_DAYS);
        when(loadPort.findStaleOpen(any(), any(Integer.class))).thenReturn(List.of());

        service.escalateStaleOpenRecoveries(NOW);

        verify(loadPort).findStaleOpen(
                eq(OffsetDateTime.of(2026, 7, 21, 3, 30, 0, 0, ZoneOffset.ofHours(9))), any(Integer.class));
    }

    @Test
    @DisplayName("정체 채권이 없으면 0 을 반환하고 저장하지 않는다")
    void returnsZeroWithoutStaleRecoveries() {
        setUp(MANUAL_ESCALATION_DAYS);
        when(loadPort.findStaleOpen(any(), any(Integer.class))).thenReturn(List.of());

        int escalated = service.escalateStaleOpenRecoveries(NOW);

        assertThat(escalated).isZero();
        verifyNoInteractions(savePort);
    }

    @Test
    @DisplayName("정체 OPEN 채권을 MANUAL_REQUIRED 로 이관하고 저장한다")
    void escalatesStaleOpenRecoveries() {
        setUp(MANUAL_ESCALATION_DAYS);
        SellerRecovery stale1 = openRecovery(1L, 7L);
        SellerRecovery stale2 = openRecovery(2L, 8L);
        when(loadPort.findStaleOpen(any(), any(Integer.class)))
                .thenReturn(List.of(stale1, stale2))
                .thenReturn(List.of());

        int escalated = service.escalateStaleOpenRecoveries(NOW);

        assertThat(escalated).isEqualTo(2);
        assertThat(stale1.getStatus()).isEqualTo(RecoveryStatus.MANUAL_REQUIRED);
        assertThat(stale2.getStatus()).isEqualTo(RecoveryStatus.MANUAL_REQUIRED);
        verify(savePort).save(stale1);
        verify(savePort).save(stale2);
    }

    @Test
    @DisplayName("배치 크기(100) 를 가득 채운 응답이면 다음 페이지를 추가로 조회한다")
    void pagesThroughFullBatches() {
        setUp(MANUAL_ESCALATION_DAYS);
        List<SellerRecovery> firstBatch = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            firstBatch.add(openRecovery(i, 100L + i));
        }
        when(loadPort.findStaleOpen(any(), eq(100)))
                .thenReturn(firstBatch)
                .thenReturn(Collections.emptyList());

        int escalated = service.escalateStaleOpenRecoveries(NOW);

        assertThat(escalated).isEqualTo(100);
        verify(loadPort, times(2)).findStaleOpen(any(), eq(100));
        verify(savePort, times(100)).save(any());
    }

    @Test
    @DisplayName("설정값(manual-escalation-days) 이 바뀌면 cutoff 계산도 그에 따른다")
    void honorsConfiguredGracePeriod() {
        setUp(14);
        when(loadPort.findStaleOpen(any(), any(Integer.class))).thenReturn(List.of());

        service.escalateStaleOpenRecoveries(NOW);

        verify(loadPort).findStaleOpen(
                eq(OffsetDateTime.of(2026, 8, 6, 3, 30, 0, 0, ZoneOffset.ofHours(9))), any(Integer.class));
        verify(savePort, never()).save(any());
    }

    private static SellerRecovery openRecovery(Long id, Long sellerId) {
        return SellerRecovery.rehydrate(id, id + 1000L, sellerId, new BigDecimal("2000.00"),
                BigDecimal.ZERO, RecoveryStatus.OPEN, LocalDateTime.of(2026, 6, 1, 0, 0), null);
    }
}
