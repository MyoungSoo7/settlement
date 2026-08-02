package github.lms.lemuel.commondata.adapter.out.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulOpenApiPropertiesTest {

    @Test
    void null_키는_빈문자열로_보정되고_미설정() {
        SeoulOpenApiProperties props = new SeoulOpenApiProperties(null);
        assertThat(props.apiKey()).isEmpty();
        assertThat(props.configured()).isFalse();
    }

    @Test
    void 공백_키는_미설정() {
        assertThat(new SeoulOpenApiProperties("   ").configured()).isFalse();
    }

    @Test
    void 값이_있으면_설정됨() {
        SeoulOpenApiProperties props = new SeoulOpenApiProperties("SEOULKEY");
        assertThat(props.apiKey()).isEqualTo("SEOULKEY");
        assertThat(props.configured()).isTrue();
    }
}
