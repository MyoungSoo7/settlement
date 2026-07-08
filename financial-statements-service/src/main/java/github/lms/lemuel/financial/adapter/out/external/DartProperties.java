package github.lms.lemuel.financial.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenDART 연동 설정.
 *
 * @param apiKey  opendart.fss.or.kr 발급 인증키 — 미설정(빈 문자열)이면 수집 비활성(시드 데이터로만 동작)
 * @param baseUrl 기본 https://opendart.fss.or.kr/api
 */
@ConfigurationProperties(prefix = "app.financial.dart")
public record DartProperties(String apiKey, String baseUrl) {

    public DartProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://opendart.fss.or.kr/api";
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
