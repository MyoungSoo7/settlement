package github.lms.lemuel.economics.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IndicatorTest {

    @Test
    void code_가_blank_이면_생성_거부() {
        assertThatThrownBy(() -> new Indicator("", "기준금리", "%", IndicatorCycle.D, "722Y001", "0101000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void name_이_blank_이면_생성_거부() {
        assertThatThrownBy(() -> new Indicator("BASE_RATE", "   ", "%", IndicatorCycle.D, "722Y001", "0101000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ecosStatCode_가_blank_이면_생성_거부() {
        assertThatThrownBy(() -> new Indicator("BASE_RATE", "기준금리", "%", IndicatorCycle.D, "", "0101000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cycle_이_null_이면_생성_거부() {
        assertThatThrownBy(() -> new Indicator("BASE_RATE", "기준금리", "%", null, "722Y001", "0101000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 유효한_값이면_정상_생성() {
        Indicator indicator = new Indicator("BASE_RATE", "기준금리", "%", IndicatorCycle.D, "722Y001", "0101000", null);
        assertThat(indicator.code()).isEqualTo("BASE_RATE");
        assertThat(indicator.cycle()).isEqualTo(IndicatorCycle.D);
    }
}
