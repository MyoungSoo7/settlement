package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.PublishDiscrepancyResolvedEventPort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyStatus;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolveDiscrepancyServiceTest {

    @Mock LoadReconciliationRunPort loadPort;
    @Mock SaveReconciliationRunPort savePort;
    @Mock PublishDiscrepancyResolvedEventPort publishPort;
    ResolveDiscrepancyService service;

    @BeforeEach
    void setUp() {
        service = new ResolveDiscrepancyService(loadPort, savePort, publishPort, new SimpleMeterRegistry());
    }

    private ReconciliationDiscrepancy pendingDiscrepancy() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "PG-001",
                new BigDecimal("10000"), new BigDecimal("9500"));
    }

    @Test @DisplayName("approve: 성공")
    void approve_success() {
        ReconciliationDiscrepancy d = pendingDiscrepancy();
        when(loadPort.findDiscrepancyById(1L)).thenReturn(Optional.of(d));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReconciliationDiscrepancy result = service.approve(1L, "admin", "확인됨");
        assertThat(result.getStatus()).isEqualTo(DiscrepancyStatus.APPROVED);
        // 승인 시 보정 트리거 이벤트가 Outbox 포트로 발행되어야 함
        verify(publishPort).publishDiscrepancyApproved(result);
    }

    @Test @DisplayName("reject: 보정 이벤트 발행 없음 (무시 결정)")
    void reject_doesNotPublishEvent() {
        ReconciliationDiscrepancy d = pendingDiscrepancy();
        when(loadPort.findDiscrepancyById(1L)).thenReturn(Optional.of(d));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.reject(1L, "admin", "PG 오류");
        verify(publishPort, never()).publishDiscrepancyApproved(any());
    }

    @Test @DisplayName("approve: 없으면 예외")
    void approve_notFound() {
        when(loadPort.findDiscrepancyById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(99L, "admin", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("reject: 성공")
    void reject_success() {
        ReconciliationDiscrepancy d = pendingDiscrepancy();
        when(loadPort.findDiscrepancyById(1L)).thenReturn(Optional.of(d));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReconciliationDiscrepancy result = service.reject(1L, "admin", "PG 오류");
        assertThat(result.getStatus()).isEqualTo(DiscrepancyStatus.REJECTED);
    }

    @Test @DisplayName("reject: 없으면 예외")
    void reject_notFound() {
        when(loadPort.findDiscrepancyById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reject(99L, "admin", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
