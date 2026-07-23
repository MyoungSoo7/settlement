package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LedgerReverseBackfillPort;
import github.lms.lemuel.ledger.application.port.out.LedgerReverseBackfillPort.PageResult;
import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BackfillMissingReverseService — 페이지 루프 오케스트레이션 단위 검증.
 *
 * <p>실제 SQL·아웃박스 적재는 포트 구현(어댑터)의 경계라 통합 테스트에서 검증하고,
 * 여기선 루프 종료·건수 집계·pageSize 클램프·안전 상한만 순수 단위로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillMissingReverseService — 역분개 백필 루프")
class BackfillMissingReverseServiceTest {

    @Mock
    LedgerReverseBackfillPort port;

    private BackfillMissingReverseService service(int defaultPageSize) {
        return new BackfillMissingReverseService(port, defaultPageSize);
    }

    @Nested
    class 백필_실행 {

        @Test
        @DisplayName("누락이 없으면 한 페이지 확인 후 즉시 완료")
        void noMissingImmediatelyComplete() {
            when(port.countMissingReverseAdjustments()).thenReturn(0L, 0L);
            when(port.enqueueReversePage(0L, 200)).thenReturn(PageResult.empty(0L));

            LedgerReverseBackfillReport report = service(200).backfillMissingReverse(null);

            assertThat(report.totalEnqueued()).isZero();
            assertThat(report.pagesCommitted()).isZero();
            assertThat(report.complete()).isTrue();
            verify(port, times(1)).enqueueReversePage(0L, 200);
        }

        @Test
        @DisplayName("페이지 단위로 커밋하고 출처별 건수를 합산한다")
        void accumulatesPerTypeCountsAcrossPages() {
            // initial missing = 3, pageSize = 2 → maxPages = 3/2 + 2 = 3
            when(port.countMissingReverseAdjustments()).thenReturn(3L, 0L);
            // Page 1: 2건 (차지백 1, PG대사 1), lastId=10
            when(port.enqueueReversePage(0L, 2))
                    .thenReturn(new PageResult(2, 10L, 1, 1));
            // Page 2: 1건 (PG대사 1), lastId=15
            when(port.enqueueReversePage(10L, 2))
                    .thenReturn(new PageResult(1, 15L, 0, 1));
            // Page 3: 빈 페이지 → 루프 종료
            when(port.enqueueReversePage(15L, 2))
                    .thenReturn(PageResult.empty(15L));

            LedgerReverseBackfillReport report = service(2).backfillMissingReverse(null);

            assertThat(report.enqueuedChargeback()).isEqualTo(1);
            assertThat(report.enqueuedReconciliation()).isEqualTo(2);
            assertThat(report.totalEnqueued()).isEqualTo(3);
            assertThat(report.pagesCommitted()).isEqualTo(2);
            assertThat(report.remainingMissing()).isZero();
            assertThat(report.complete()).isTrue();
        }

        @Test
        @DisplayName("폴러 미처리로 잔여가 남으면 complete=false 로 재확인을 유도한다")
        void remainingMissingReportedIncomplete() {
            // 폴러가 처리하지 않아 적재 후에도 잔여가 남는 시나리오
            when(port.countMissingReverseAdjustments()).thenReturn(2L, 2L);
            when(port.enqueueReversePage(0L, 2))
                    .thenReturn(new PageResult(2, 20L, 1, 1));
            when(port.enqueueReversePage(20L, 2))
                    .thenReturn(PageResult.empty(20L));

            LedgerReverseBackfillReport report = service(2).backfillMissingReverse(null);

            assertThat(report.totalEnqueued()).isEqualTo(2);
            assertThat(report.remainingMissing()).isEqualTo(2);
            assertThat(report.complete()).isFalse();
        }

        @Test
        @DisplayName("안전 상한: 페이지가 계속 비지 않아도 초기 건수 기반 상한에서 멈춘다")
        void stopsAtSafetyCap() {
            // initial=5, pageSize=5 → maxPages=5/5+2=3. 페이지가 계속 반환해도 3회에서 멈춤
            when(port.countMissingReverseAdjustments()).thenReturn(5L, 5L);
            when(port.enqueueReversePage(anyLong(), eq(5)))
                    .thenReturn(new PageResult(5, 99L, 5, 0));

            LedgerReverseBackfillReport report = service(5).backfillMissingReverse(null);

            assertThat(report.pagesCommitted()).isEqualTo(3);
            assertThat(report.enqueuedChargeback()).isEqualTo(15);
            verify(port, times(3)).enqueueReversePage(anyLong(), eq(5));
        }

        @Test
        @DisplayName("afterId 커서가 마지막 처리 id로 올바르게 전진한다")
        void afterIdCursorAdvancesCorrectly() {
            when(port.countMissingReverseAdjustments()).thenReturn(4L, 0L);
            when(port.enqueueReversePage(0L, 2))
                    .thenReturn(new PageResult(2, 50L, 1, 1));
            when(port.enqueueReversePage(50L, 2))
                    .thenReturn(new PageResult(2, 100L, 0, 2));
            when(port.enqueueReversePage(100L, 2))
                    .thenReturn(PageResult.empty(100L));

            service(2).backfillMissingReverse(null);

            verify(port).enqueueReversePage(0L, 2);
            verify(port).enqueueReversePage(50L, 2);
            verify(port).enqueueReversePage(100L, 2);
        }
    }

    @Nested
    class pageSize_클램프 {

        @Test
        @DisplayName("null/비양수 pageSize 는 기본값으로 대체된다")
        void defaultsWhenNullOrNonPositive() {
            when(port.countMissingReverseAdjustments()).thenReturn(0L, 0L);
            when(port.enqueueReversePage(0L, 200)).thenReturn(PageResult.empty(0L));

            assertThat(service(200).backfillMissingReverse(null).pageSize()).isEqualTo(200);
            assertThat(service(200).backfillMissingReverse(0).pageSize()).isEqualTo(200);
            assertThat(service(200).backfillMissingReverse(-1).pageSize()).isEqualTo(200);
        }

        @Test
        @DisplayName("과도한 pageSize 는 상한(1000)으로 클램프된다")
        void clampedToMax() {
            when(port.countMissingReverseAdjustments()).thenReturn(0L, 0L);
            when(port.enqueueReversePage(0L, 1000)).thenReturn(PageResult.empty(0L));

            LedgerReverseBackfillReport report = service(200).backfillMissingReverse(999_999);

            assertThat(report.pageSize()).isEqualTo(1000);
            verify(port).enqueueReversePage(0L, 1000);
        }
    }

    @Nested
    class 현황_조회 {

        @Test
        @DisplayName("statusMissingReverse 는 실행 없이 누락 건 수만 조회한다")
        void statusReadsCountOnly() {
            when(port.countMissingReverseAdjustments()).thenReturn(7L);

            LedgerReverseBackfillReport report = service(200).statusMissingReverse();

            assertThat(report.remainingMissing()).isEqualTo(7);
            assertThat(report.totalEnqueued()).isZero();
            assertThat(report.pagesCommitted()).isZero();
            assertThat(report.complete()).isFalse();
            verify(port, never()).enqueueReversePage(anyLong(), anyInt());
        }

        @Test
        @DisplayName("누락이 없으면 complete=true 상태를 반환한다")
        void statusCompleteWhenNoMissing() {
            when(port.countMissingReverseAdjustments()).thenReturn(0L);

            LedgerReverseBackfillReport report = service(200).statusMissingReverse();

            assertThat(report.complete()).isTrue();
            assertThat(report.remainingMissing()).isZero();
        }
    }
}
