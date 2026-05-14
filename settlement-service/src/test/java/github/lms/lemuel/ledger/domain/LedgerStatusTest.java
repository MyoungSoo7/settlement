package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerStatusTest {

    @Test
    void PENDING_은_POSTED_REVERSED_로_전이_가능_자기자신은_불가() {
        assertThat(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.POSTED)).isTrue();
        assertThat(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.REVERSED)).isTrue();
        assertThat(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.PENDING)).isFalse();
    }

    @Test
    void POSTED_는_REVERSED_로만_전이() {
        assertThat(LedgerStatus.POSTED.canTransitionTo(LedgerStatus.REVERSED)).isTrue();
        assertThat(LedgerStatus.POSTED.canTransitionTo(LedgerStatus.PENDING)).isFalse();
        assertThat(LedgerStatus.POSTED.canTransitionTo(LedgerStatus.POSTED)).isFalse();
    }

    @Test
    void REVERSED_는_종결_상태_어디로도_전이_불가() {
        assertThat(LedgerStatus.REVERSED.isFinal()).isTrue();
        assertThat(LedgerStatus.REVERSED.canTransitionTo(LedgerStatus.PENDING)).isFalse();
        assertThat(LedgerStatus.REVERSED.canTransitionTo(LedgerStatus.POSTED)).isFalse();
        assertThat(LedgerStatus.REVERSED.canTransitionTo(LedgerStatus.REVERSED)).isFalse();
    }

    @Test
    void 종결_여부() {
        assertThat(LedgerStatus.PENDING.isFinal()).isFalse();
        assertThat(LedgerStatus.POSTED.isFinal()).isFalse();
        assertThat(LedgerStatus.REVERSED.isFinal()).isTrue();
    }
}
