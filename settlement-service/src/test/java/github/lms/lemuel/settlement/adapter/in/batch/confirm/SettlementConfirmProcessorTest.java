package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementConfirmProcessorTest {

    private final SettlementConfirmProcessor processor = new SettlementConfirmProcessor();

    private Settlement requested(long id) {
        Settlement s = Settlement.createFromPayment(id, id + 10, new BigDecimal("10000"), LocalDate.now());
        s.assignId(id);
        return s;
    }

    @Test
    @DisplayName("REQUESTED 정산은 confirm 되어 DONE 으로 전이된다")
    void confirmsPending() {
        Settlement result = processor.process(requested(1L));

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.DONE);
    }

    @Test
    @DisplayName("이미 DONE 인 정산은 null 반환(청크에서 제외)")
    void skipsAlreadyDone() {
        Settlement done = requested(1L);
        done.confirm();

        assertThat(processor.process(done)).isNull();
    }
}
