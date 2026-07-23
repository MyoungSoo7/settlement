package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.out.LoadInternalPaymentsForReconciliationPort;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.ParsePgFilePort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.InternalPaymentRow;
import github.lms.lemuel.pgreconciliation.domain.PgTransactionRow;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconcilePgFileService — PG 파일 대사 오케스트레이션")
class ReconcilePgFileServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 10);

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
        // 기본: 같은 파일의 기존 완료 run 없음 — 멱등 경로 테스트가 개별 재정의한다.
        org.mockito.Mockito.lenient().when(loadRunPort.findCompletedByFileSha256(any()))
                .thenReturn(java.util.Optional.empty());
    }

    private InputStream anyInput() {
        return new ByteArrayInputStream("file".getBytes());
    }

    @Test
    @DisplayName("정상: 매칭 1건 + 금액불일치 1건 → COMPLETED, 불일치 메트릭 증가, saveAll 저장")
    void reconcile_completesAndRecordsMetrics() {
        List<PgTransactionRow> pgRows = List.of(
                new PgTransactionRow("PG-1", new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, DATE),
                new PgTransactionRow("PG-2", new BigDecimal("10500"), BigDecimal.ZERO, BigDecimal.ZERO, DATE));
        List<InternalPaymentRow> internalRows = List.of(
                new InternalPaymentRow(1L, "PG-1", new BigDecimal("10000"), BigDecimal.ZERO, DATE),
                new InternalPaymentRow(2L, "PG-2", new BigDecimal("10000"), BigDecimal.ZERO, DATE));

        when(parsePort.parse(any())).thenReturn(pgRows);
        when(loadPort.loadByCapturedDate(DATE)).thenReturn(internalRows);
        when(savePort.saveAll(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationRun run = service.reconcile("TOSS", DATE, "toss.csv", anyInput(), "ops-1");

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getDiscrepancyCount()).isEqualTo(1);
        verify(savePort).saveAll(any(ReconciliationRun.class));

        // AMOUNT_MISMATCH 불일치가 Prometheus 카운터로 집계되어야 한다
        double mismatch = Search.in(meterRegistry).name("pg.reconciliation.discrepancies")
                .tag("type", DiscrepancyType.AMOUNT_MISMATCH.name())
                .counter().count();
        assertThat(mismatch).isEqualTo(1.0);
    }

    @Test
    @DisplayName("파싱 실패: 예외를 흡수해 run 을 FAILED 로 마감하고 저장 (대사 배치는 조용히 실패)")
    void reconcile_failsGracefully() {
        when(parsePort.parse(any())).thenThrow(new RuntimeException("깨진 CSV"));
        when(savePort.saveAll(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationRun run = service.reconcile("TOSS", DATE, "bad.csv", anyInput(), "ops-1");

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(run.getNote()).contains("깨진 CSV");
        verify(savePort).saveAll(any(ReconciliationRun.class));
    }

    @Test
    @DisplayName("같은 파일 재업로드: 기존 COMPLETED run 을 멱등 반환하고 새 run 을 만들지 않는다 (이중 clawback 차단)")
    void reconcile_duplicateFile_returnsExistingRun() {
        ReconciliationRun existing = ReconciliationRun.rehydrate(
                77L, "TOSS", DATE, "toss.csv", "dummy-sha", ReconciliationRunStatus.COMPLETED,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                2, 2, 2, 0, 0, "ops-1", null, List.of());
        when(loadRunPort.findCompletedByFileSha256(any())).thenReturn(java.util.Optional.of(existing));

        ReconciliationRun run = service.reconcile("TOSS", DATE, "toss.csv", anyInput(), "ops-2");

        assertThat(run.getId()).isEqualTo(77L);
        verify(savePort, org.mockito.Mockito.never()).saveAll(any(ReconciliationRun.class));
        verify(parsePort, org.mockito.Mockito.never()).parse(any());
        assertThat(Search.in(meterRegistry).name("pg.reconciliation.duplicate_file.hit")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("새 run 에는 파일 SHA-256 이 저장된다 — DB 부분 UNIQUE(COMPLETED) 멱등 키")
    void reconcile_storesFileSha256() {
        when(parsePort.parse(any())).thenReturn(List.of());
        when(loadPort.loadByCapturedDate(DATE)).thenReturn(List.of());
        when(savePort.saveAll(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationRun run = service.reconcile("TOSS", DATE, "toss.csv", anyInput(), "ops-1");

        // "file" 바이트의 SHA-256 — 해시가 내용 기준으로 계산돼 저장됨을 고정한다.
        assertThat(run.getFileSha256())
                .isEqualTo("3b9c358f36f0a31b6ad3e14f309c7cf198ac9246e8316f9ce543d5b19ac02b80");
    }
}
