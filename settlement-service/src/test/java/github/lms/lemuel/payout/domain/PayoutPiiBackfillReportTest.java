package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PayoutPiiBackfillReport — 완료 판정·노트 구성")
class PayoutPiiBackfillReportTest {

    @Test
    @DisplayName("of: 잔존 0 이면 complete=true 이고 완료 노트를 단다")
    void complete_whenNoRemaining() {
        PayoutPiiBackfillReport r = PayoutPiiBackfillReport.of(500, 1017, 0, 3);

        assertThat(r.complete()).isTrue();
        assertThat(r.backfilled()).isEqualTo(1017);
        assertThat(r.pagesCommitted()).isEqualTo(3);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("완료"));
    }

    @Test
    @DisplayName("of: 잔존이 남으면 complete=false 이고 재실행 권장 노트를 단다")
    void incomplete_whenRemaining() {
        PayoutPiiBackfillReport r = PayoutPiiBackfillReport.of(500, 500, 2, 1);

        assertThat(r.complete()).isFalse();
        assertThat(r.remainingPlaintext()).isEqualTo(2);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("재실행"));
    }

    @Test
    @DisplayName("status: 실행 정보(backfilled/pages/pageSize)는 0 이고 잔존만 반영한다")
    void status_countOnly() {
        PayoutPiiBackfillReport r = PayoutPiiBackfillReport.status(7);

        assertThat(r.backfilled()).isZero();
        assertThat(r.pagesCommitted()).isZero();
        assertThat(r.pageSize()).isZero();
        assertThat(r.remainingPlaintext()).isEqualTo(7);
        assertThat(r.complete()).isFalse();
    }

    @Test
    @DisplayName("status: 잔존 0 이면 complete=true")
    void status_completeWhenZero() {
        assertThat(PayoutPiiBackfillReport.status(0).complete()).isTrue();
    }

    @Test
    @DisplayName("notes 는 불변 복사본이다")
    void notesAreImmutable() {
        PayoutPiiBackfillReport r = PayoutPiiBackfillReport.of(500, 1, 0, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.notes().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
