package github.lms.lemuel.settlement.application.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SettlementBatchHealthSnapshot — 배치 헬스 스냅샷")
class SettlementBatchHealthSnapshotTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 10);

    @Test
    @DisplayName("게터가 생성자 인자를 그대로 반환")
    void getters() {
        SettlementBatchHealthSnapshot snapshot =
                new SettlementBatchHealthSnapshot(DATE, 10L, 20L, 5L);

        assertThat(snapshot.getSettlementDate()).isEqualTo(DATE);
        assertThat(snapshot.getSettlementPendingCount()).isEqualTo(10L);
        assertThat(snapshot.getSettlementConfirmedCount()).isEqualTo(20L);
        assertThat(snapshot.getAdjustmentPendingCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("대기 정산 100 건 초과 여부 판정")
    void hasTooManyPendingSettlements() {
        assertThat(new SettlementBatchHealthSnapshot(DATE, 101L, 0L, 0L)
                .hasTooManyPendingSettlements()).isTrue();
        assertThat(new SettlementBatchHealthSnapshot(DATE, 100L, 0L, 0L)
                .hasTooManyPendingSettlements()).isFalse();
    }

    @Test
    @DisplayName("대기 조정 50 건 초과 여부 판정")
    void hasTooManyPendingAdjustments() {
        assertThat(new SettlementBatchHealthSnapshot(DATE, 0L, 0L, 51L)
                .hasTooManyPendingAdjustments()).isTrue();
        assertThat(new SettlementBatchHealthSnapshot(DATE, 0L, 0L, 50L)
                .hasTooManyPendingAdjustments()).isFalse();
    }
}
