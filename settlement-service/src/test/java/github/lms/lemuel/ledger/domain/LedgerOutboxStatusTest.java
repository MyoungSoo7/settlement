package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LedgerOutboxStatus — 원장 아웃박스 상태 enum")
class LedgerOutboxStatusTest {

    @Test
    @DisplayName("세 가지 상태를 가진다")
    void hasThreeStates() {
        assertThat(LedgerOutboxStatus.values())
                .containsExactly(LedgerOutboxStatus.PENDING,
                        LedgerOutboxStatus.DONE,
                        LedgerOutboxStatus.FAILED);
    }

    @Test
    @DisplayName("valueOf 로 이름 복원")
    void valueOf_roundTrip() {
        assertThat(LedgerOutboxStatus.valueOf("PENDING")).isEqualTo(LedgerOutboxStatus.PENDING);
        assertThat(LedgerOutboxStatus.valueOf("DONE")).isEqualTo(LedgerOutboxStatus.DONE);
        assertThat(LedgerOutboxStatus.valueOf("FAILED")).isEqualTo(LedgerOutboxStatus.FAILED);
    }
}
