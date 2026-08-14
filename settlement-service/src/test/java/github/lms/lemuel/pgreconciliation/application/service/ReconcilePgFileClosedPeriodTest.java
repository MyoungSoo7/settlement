package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.out.LoadInternalPaymentsForReconciliationPort;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.ParsePgFilePort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마감된 기간에는 새 대사를 열 수 없다.
 *
 * <p>파일 해시 멱등은 <b>같은 파일</b>만 막는다. 다른 파일을 같은 (PG, 날짜)로 올리면 새 run 이
 * 열리고, 이미 정산·지급이 끝난 기간에 새 불일치와 새 clawback 이 생긴다. 마감이 그 경로를 닫는다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcilePgFileClosedPeriodTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 5);
    private static final String PROVIDER = "TOSS";

    @Mock ParsePgFilePort parsePort;
    @Mock LoadInternalPaymentsForReconciliationPort loadPort;
    @Mock SaveReconciliationRunPort savePort;
    @Mock LoadReconciliationRunPort loadRunPort;

    SimpleMeterRegistry meterRegistry;
    ReconcilePgFileService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ReconcilePgFileService(parsePort, loadPort, savePort, loadRunPort, meterRegistry);
        lenient().when(loadRunPort.findCompletedByFileSha256(any())).thenReturn(Optional.empty());
    }

    private InputStream input(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    private static ReconciliationRun closedRun() {
        ReconciliationRun run = ReconciliationRun.start(PROVIDER, DATE, "old.csv", "op-1", "sha-old");
        run.complete(1, 1, 1, List.of());
        run.close("op-2", "마감");
        return run;
    }

    @Test
    @DisplayName("마감된 (PG, 날짜)에 다른 파일을 올리면 거부 — 파싱조차 하지 않는다")
    void rejectsUploadForClosedPeriod() {
        when(loadRunPort.findClosedByProviderAndDate(PROVIDER, DATE)).thenReturn(Optional.of(closedRun()));

        assertThatThrownBy(() ->
                service.reconcile(PROVIDER, DATE, "new.csv", input("different"), "op-3"))
                .isInstanceOf(InvalidReconciliationStateException.class)
                .hasMessageContaining("마감");

        verify(parsePort, never()).parse(any());
        verify(savePort, never()).saveAll(any());
    }

    @Test
    @DisplayName("마감 거부는 메트릭으로 관측된다 — 조용히 막지 않는다")
    void rejectionIsObservable() {
        when(loadRunPort.findClosedByProviderAndDate(PROVIDER, DATE)).thenReturn(Optional.of(closedRun()));

        assertThatThrownBy(() ->
                service.reconcile(PROVIDER, DATE, "new.csv", input("different"), "op-3"))
                .isInstanceOf(InvalidReconciliationStateException.class);

        assertThat(meterRegistry.counter("pg.reconciliation.closed_period.rejected",
                "provider", PROVIDER).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("마감되지 않은 기간은 정상 진행")
    void proceedsWhenNotClosed() {
        when(loadRunPort.findClosedByProviderAndDate(PROVIDER, DATE)).thenReturn(Optional.empty());
        when(parsePort.parse(any())).thenReturn(List.of());
        when(loadPort.loadByCapturedDate(DATE)).thenReturn(List.of());
        when(savePort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationRun result = service.reconcile(PROVIDER, DATE, "new.csv", input("data"), "op-3");

        assertThat(result).isNotNull();
        verify(parsePort).parse(any());
    }

    @Test
    @DisplayName("마감 확인은 파일 해시 멱등보다 먼저 — 같은 파일이어도 마감 기간이면 거부한다")
    void closedCheckPrecedesHashIdempotency() {
        when(loadRunPort.findClosedByProviderAndDate(PROVIDER, DATE)).thenReturn(Optional.of(closedRun()));

        assertThatThrownBy(() ->
                service.reconcile(PROVIDER, DATE, "same.csv", input("same"), "op-3"))
                .isInstanceOf(InvalidReconciliationStateException.class);

        // 마감 판정에서 이미 끊겼으므로 해시 조회까지 가지 않는다
        verify(loadRunPort, never()).findCompletedByFileSha256(any());
    }
}
