package github.lms.lemuel.market.adapter.out.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KrxPropertiesTest {

    @Test
    void 결측값은_기본값으로_보정된다() {
        KrxProperties props = new KrxProperties(null, null, 0);

        assertThat(props.apiKey()).isEmpty();
        assertThat(props.baseUrl())
                .isEqualTo("https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService");
        assertThat(props.pageSize()).isEqualTo(1000);
        assertThat(props.configured()).isFalse();
    }

    @Test
    void 공백_baseUrl_도_기본값으로_보정된다() {
        KrxProperties props = new KrxProperties("KEY", "   ", 500);

        assertThat(props.baseUrl()).startsWith("https://apis.data.go.kr");
        assertThat(props.pageSize()).isEqualTo(500);
        assertThat(props.configured()).isTrue();
    }

    @Test
    void 명시값은_보존된다() {
        KrxProperties props = new KrxProperties("KEY", "https://example.test", 100);

        assertThat(props.baseUrl()).isEqualTo("https://example.test");
        assertThat(props.pageSize()).isEqualTo(100);
    }
}
