package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPeriodStatusTest {

    @Test
    void OPEN_은_CLOSED_로만_전이_가능() {
        assertThat(LedgerPeriodStatus.OPEN.canTransitionTo(LedgerPeriodStatus.CLOSED)).isTrue();
        assertThat(LedgerPeriodStatus.OPEN.canTransitionTo(LedgerPeriodStatus.OPEN)).isFalse();
    }

    @Test
    void CLOSED_는_종결상태_어디로도_전이불가() {
        assertThat(LedgerPeriodStatus.CLOSED.canTransitionTo(LedgerPeriodStatus.OPEN)).isFalse();
        assertThat(LedgerPeriodStatus.CLOSED.canTransitionTo(LedgerPeriodStatus.CLOSED)).isFalse();
        assertThat(LedgerPeriodStatus.CLOSED.isFinal()).isTrue();
        assertThat(LedgerPeriodStatus.OPEN.isFinal()).isFalse();
    }
}
