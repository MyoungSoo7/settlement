package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.out.PayoutPiiBackfillPort;
import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReencryptPayoutPiiService — 페이지 루프 오케스트레이션 단위 검증.
 * 실제 재암호화·평문 스킵은 포트 구현(어댑터)의 nativeQuery/컨버터 경계라 통합 테스트에서 검증하고,
 * 여기선 루프 종료·건수 집계·페이지 크기 클램프·안전 상한만 순수 단위로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReencryptPayoutPiiService — 재암호화 백필 루프")
class ReencryptPayoutPiiServiceTest {

    @Mock PayoutPiiBackfillPort port;

    private ReencryptPayoutPiiService service(int defaultPageSize) {
        return new ReencryptPayoutPiiService(port, defaultPageSize);
    }

    @Test
    @DisplayName("페이지가 0 을 반환할 때까지 반복하고 건수를 합산한다")
    void loopsUntilPageReturnsZero() {
        when(port.countLegacyPlaintext()).thenReturn(1017L, 0L); // 초기 잔존, 실행 후 잔존
        when(port.reencryptNextPage(500)).thenReturn(500, 500, 17, 0);

        PayoutPiiBackfillReport report = service(500).reencryptLegacyPlaintext(null);

        assertThat(report.backfilled()).isEqualTo(1017L);
        assertThat(report.pagesCommitted()).isEqualTo(3);
        assertThat(report.pageSize()).isEqualTo(500);
        assertThat(report.remainingPlaintext()).isZero();
        assertThat(report.complete()).isTrue();
        verify(port, times(4)).reencryptNextPage(500);
    }

    @Test
    @DisplayName("평문이 처음부터 없으면 한 페이지만 확인하고 즉시 완료")
    void noPlaintextRemaining() {
        when(port.countLegacyPlaintext()).thenReturn(0L, 0L);
        when(port.reencryptNextPage(500)).thenReturn(0);

        PayoutPiiBackfillReport report = service(500).reencryptLegacyPlaintext(null);

        assertThat(report.backfilled()).isZero();
        assertThat(report.pagesCommitted()).isZero();
        assertThat(report.complete()).isTrue();
        verify(port, times(1)).reencryptNextPage(500);
    }

    @Test
    @DisplayName("실행 후에도 평문이 남으면 complete=false 로 재실행을 유도한다")
    void remainingPlaintextReportedIncomplete() {
        when(port.countLegacyPlaintext()).thenReturn(3L, 1L);
        when(port.reencryptNextPage(2)).thenReturn(2, 0);

        PayoutPiiBackfillReport report = service(2).reencryptLegacyPlaintext(null);

        assertThat(report.backfilled()).isEqualTo(2L);
        assertThat(report.remainingPlaintext()).isEqualTo(1L);
        assertThat(report.complete()).isFalse();
    }

    @Test
    @DisplayName("null/비양수 pageSize 는 기본값으로 대체된다")
    void pageSizeDefaultsWhenNullOrNonPositive() {
        when(port.countLegacyPlaintext()).thenReturn(0L, 0L);
        when(port.reencryptNextPage(500)).thenReturn(0);

        assertThat(service(500).reencryptLegacyPlaintext(0).pageSize()).isEqualTo(500);
        assertThat(service(500).reencryptLegacyPlaintext(-5).pageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("과도한 pageSize 는 상한(5000)으로 클램프된다")
    void pageSizeClampedToMax() {
        when(port.countLegacyPlaintext()).thenReturn(0L, 0L);
        when(port.reencryptNextPage(5000)).thenReturn(0);

        PayoutPiiBackfillReport report = service(500).reencryptLegacyPlaintext(1_000_000);

        assertThat(report.pageSize()).isEqualTo(5000);
        verify(port).reencryptNextPage(5000);
    }

    @Test
    @DisplayName("안전 상한: 페이지가 계속 비지 않아도 초기 잔존 기반 상한에서 멈춘다")
    void stopsAtSafetyCapWhenPagesNeverDrain() {
        // 초기 잔존 5, pageSize 5 → maxPages = 5/5 + 2 = 3. 페이지가 계속 5 를 반환해도 3회에서 종료.
        when(port.countLegacyPlaintext()).thenReturn(5L, 5L);
        when(port.reencryptNextPage(5)).thenReturn(5);

        PayoutPiiBackfillReport report = service(5).reencryptLegacyPlaintext(null);

        assertThat(report.pagesCommitted()).isEqualTo(3);
        assertThat(report.backfilled()).isEqualTo(15L);
        verify(port, times(3)).reencryptNextPage(5);
    }

    @Test
    @DisplayName("remainingPlaintextCount 는 실행 없이 잔존 건수만 조회한다")
    void statusReadsCountOnly() {
        when(port.countLegacyPlaintext()).thenReturn(7L);

        PayoutPiiBackfillReport report = service(500).remainingPlaintextCount();

        assertThat(report.remainingPlaintext()).isEqualTo(7L);
        assertThat(report.backfilled()).isZero();
        assertThat(report.complete()).isFalse();
        verify(port, never()).reencryptNextPage(org.mockito.ArgumentMatchers.anyInt());
    }
}
