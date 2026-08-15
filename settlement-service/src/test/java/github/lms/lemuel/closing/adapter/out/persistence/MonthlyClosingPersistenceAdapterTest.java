package github.lms.lemuel.closing.adapter.out.persistence;

import github.lms.lemuel.closing.domain.ClosingRunStatus;
import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 월마감 마트 영속 매핑.
 *
 * <p>마트는 재마감 때 <b>기간 단위로 통째 교체</b>(delete+insert)된다. 그래서 이 어댑터가
 * 지켜야 하는 것은 두 가지다. ① 교체와 run 갱신이 같은 호출 안에서 함께 일어날 것(반쪽 마감 금지),
 * ② 금액 5종·건수 4종이 자리를 바꾸지 않고 그대로 실릴 것 — 필드가 많아 순서만 어긋나도
 * 컴파일은 통과하고 마감 숫자만 조용히 틀린다.
 */
class MonthlyClosingPersistenceAdapterTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 7);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private SpringDataMonthlyClosingRunRepository runRepository;
    private SpringDataSellerMonthlyClosingRepository martRepository;
    private MonthlyClosingPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        runRepository = mock(SpringDataMonthlyClosingRunRepository.class);
        martRepository = mock(SpringDataSellerMonthlyClosingRepository.class);
        adapter = new MonthlyClosingPersistenceAdapter(runRepository, martRepository);
    }

    private static ClosingTotals totals() {
        return ClosingTotals.of(new BigDecimal("100000000.00"), new BigDecimal("2000000.00"),
                new BigDecimal("3500000.00"), new BigDecimal("1000000.00"), new BigDecimal("93500000.00"));
    }

    private static MonthlyClosingRun completedRun() {
        return MonthlyClosingRun.rehydrate(1L, PERIOD, ClosingRunStatus.COMPLETED, "admin",
                NOW, NOW.plusMinutes(1), 12, 340L, 0L, 0L, totals(), null, NOW);
    }

    private static SellerMonthlyClosing martRow() {
        return SellerMonthlyClosing.of(PERIOD, 7L, 30L,
                new BigDecimal("10000000.00"), new BigDecimal("0.00"),
                new BigDecimal("350000.00"), new BigDecimal("0.00"), new BigDecimal("9650000.00"));
    }

    private static MonthlyClosingRunJpaEntity runEntity() {
        MonthlyClosingRunJpaEntity e = new MonthlyClosingRunJpaEntity();
        e.setPeriodYm("2026-07");
        e.setStatus("COMPLETED");
        e.setTriggeredBy("admin");
        e.setStartedAt(NOW);
        e.setFinishedAt(NOW.plusMinutes(1));
        e.setSellerCount(12);
        e.setSettlementCount(340L);
        e.setUnmappedCount(0L);
        e.setPendingCount(0L);
        e.setTotalGross(new BigDecimal("100000000.00"));
        e.setTotalRefunded(new BigDecimal("2000000.00"));
        e.setTotalCommission(new BigDecimal("3500000.00"));
        e.setTotalHoldback(new BigDecimal("1000000.00"));
        e.setTotalNet(new BigDecimal("93500000.00"));
        e.setCreatedAt(NOW);
        return e;
    }

    @Test
    @DisplayName("마감 이력이 없으면 빈 Optional")
    void findRunReturnsEmptyWhenAbsent() {
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.empty());

        assertThat(adapter.findRun(PERIOD)).isEmpty();
    }

    @Test
    @DisplayName("마감 이력을 도메인으로 복원한다 — 합계 5종 포함")
    void findRunMapsTotals() {
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.of(runEntity()));

        MonthlyClosingRun run = adapter.findRun(PERIOD).orElseThrow();

        assertThat(run.getPeriod()).isEqualTo(PERIOD);
        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
        assertThat(run.getTriggeredBy()).isEqualTo("admin");
        assertThat(run.getSellerCount()).isEqualTo(12);
        assertThat(run.getSettlementCount()).isEqualTo(340L);
        assertThat(run.getTotals().grossAmount()).isEqualByComparingTo("100000000.00");
        assertThat(run.getTotals().netAmount()).isEqualByComparingTo("93500000.00");
    }

    @Test
    @DisplayName("합계가 비어 있는 진행/실패 이력은 totals=null 로 복원한다")
    void findRunKeepsNullTotals() {
        MonthlyClosingRunJpaEntity running = runEntity();
        running.setStatus("RUNNING");
        running.setTotalGross(null);
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.of(running));

        MonthlyClosingRun run = adapter.findRun(PERIOD).orElseThrow();

        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.RUNNING);
        assertThat(run.getTotals()).isNull();
    }

    @Test
    @DisplayName("마트 조회는 셀러 오름차순 결과를 도메인으로 옮긴다")
    void findMartMapsRows() {
        SellerMonthlyClosingJpaEntity row = new SellerMonthlyClosingJpaEntity();
        row.setPeriodYm("2026-07");
        row.setSellerId(7L);
        row.setSettlementCount(30L);
        row.setGrossAmount(new BigDecimal("10000000.00"));
        row.setRefundedAmount(new BigDecimal("0.00"));
        row.setCommissionAmount(new BigDecimal("350000.00"));
        row.setHoldbackAmount(new BigDecimal("0.00"));
        row.setNetAmount(new BigDecimal("9650000.00"));
        when(martRepository.findByPeriodYmOrderBySellerIdAsc("2026-07")).thenReturn(List.of(row));

        List<SellerMonthlyClosing> rows = adapter.findMart(PERIOD);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSellerId()).isEqualTo(7L);
        assertThat(rows.get(0).getSettlementCount()).isEqualTo(30L);
        assertThat(rows.get(0).getCommissionAmount()).isEqualByComparingTo("350000.00");
        assertThat(rows.get(0).getNetAmount()).isEqualByComparingTo("9650000.00");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("마감 저장은 기간 마트를 지우고 다시 넣은 뒤 run 을 upsert 한다")
    void saveCompletedReplacesPeriodMart() {
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.empty());
        when(runRepository.save(any(MonthlyClosingRunJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

        adapter.saveCompleted(completedRun(), List.of(martRow()));

        verify(martRepository).deleteByPeriodYm("2026-07");
        ArgumentCaptor<List<SellerMonthlyClosingJpaEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(martRepository).saveAll(saved.capture());
        SellerMonthlyClosingJpaEntity entity = saved.getValue().get(0);
        assertThat(entity.getPeriodYm()).isEqualTo("2026-07");
        assertThat(entity.getSellerId()).isEqualTo(7L);
        assertThat(entity.getSettlementCount()).isEqualTo(30L);
        assertThat(entity.getGrossAmount()).isEqualByComparingTo("10000000.00");
        assertThat(entity.getRefundedAmount()).isEqualByComparingTo("0.00");
        assertThat(entity.getCommissionAmount()).isEqualByComparingTo("350000.00");
        assertThat(entity.getHoldbackAmount()).isEqualByComparingTo("0.00");
        assertThat(entity.getNetAmount()).isEqualByComparingTo("9650000.00");
        verify(runRepository).save(any(MonthlyClosingRunJpaEntity.class));
    }

    @Test
    @DisplayName("이미 있는 기간이면 새 행을 만들지 않고 기존 run 행을 갱신한다")
    void upsertUpdatesExistingRunRow() {
        MonthlyClosingRunJpaEntity existing = runEntity();
        existing.setStatus("RUNNING");
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.of(existing));
        when(runRepository.save(any(MonthlyClosingRunJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

        MonthlyClosingRun saved = adapter.saveRun(completedRun());

        ArgumentCaptor<MonthlyClosingRunJpaEntity> captor =
                ArgumentCaptor.forClass(MonthlyClosingRunJpaEntity.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
        verify(martRepository, never()).deleteByPeriodYm(any());
    }

    @Test
    @DisplayName("합계가 없는 run 은 금액 컬럼을 null 로 남긴다 (0 으로 채우지 않는다)")
    void upsertKeepsNullTotals() {
        MonthlyClosingRun failed = MonthlyClosingRun.rehydrate(2L, PERIOD, ClosingRunStatus.FAILED,
                "admin", NOW, NOW, 0, 0L, 0L, 0L, null, "집계 쿼리 실패", NOW);
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.empty());
        when(runRepository.save(any(MonthlyClosingRunJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

        MonthlyClosingRun saved = adapter.saveRun(failed);

        ArgumentCaptor<MonthlyClosingRunJpaEntity> captor =
                ArgumentCaptor.forClass(MonthlyClosingRunJpaEntity.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalGross()).isNull();
        assertThat(captor.getValue().getTotalNet()).isNull();
        assertThat(captor.getValue().getFailureReason()).isEqualTo("집계 쿼리 실패");
        assertThat(saved.getTotals()).isNull();
    }

    @Test
    @DisplayName("생성시각이 비어 있으면 저장 시점 UTC 로 채운다")
    void upsertFillsCreatedAtWhenMissing() {
        MonthlyClosingRun noCreatedAt = MonthlyClosingRun.rehydrate(null, PERIOD, ClosingRunStatus.RUNNING,
                "admin", NOW, null, 0, 0L, 0L, 0L, null, null, null);
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.empty());
        when(runRepository.save(any(MonthlyClosingRunJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

        adapter.saveRun(noCreatedAt);

        ArgumentCaptor<MonthlyClosingRunJpaEntity> captor =
                ArgumentCaptor.forClass(MonthlyClosingRunJpaEntity.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("빈 마트로 마감해도 기간 교체는 수행된다 (이전 달 잔재를 남기지 않는다)")
    void saveCompletedWithEmptyMartStillClearsPeriod() {
        when(runRepository.findByPeriodYm("2026-07")).thenReturn(Optional.empty());
        when(runRepository.save(any(MonthlyClosingRunJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

        adapter.saveCompleted(completedRun(), List.of());

        verify(martRepository).deleteByPeriodYm("2026-07");
        verify(martRepository).saveAll(anyList());
    }
}
