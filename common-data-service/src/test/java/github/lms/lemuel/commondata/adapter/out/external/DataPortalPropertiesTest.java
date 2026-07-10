package github.lms.lemuel.commondata.adapter.out.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataPortalPropertiesTest {

    @Test
    void null_키는_빈문자열로_보정되고_미설정() {
        DataPortalProperties props = new DataPortalProperties(null);
        assertThat(props.apiKey()).isEmpty();
        assertThat(props.configured()).isFalse();
    }

    @Test
    void 공백_키는_미설정() {
        assertThat(new DataPortalProperties("   ").configured()).isFalse();
    }

    @Test
    void 값이_있으면_설정됨() {
        DataPortalProperties props = new DataPortalProperties("KEY");
        assertThat(props.apiKey()).isEqualTo("KEY");
        assertThat(props.configured()).isTrue();
    }
}
