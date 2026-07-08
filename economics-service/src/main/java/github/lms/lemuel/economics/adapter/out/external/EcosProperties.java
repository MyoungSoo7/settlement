package github.lms.lemuel.economics.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 한국은행 ECOS 연동 설정.
 *
 * @param apiKey  ecos.bok.or.kr 발급 인증키 — 미설정(빈 문자열)이면 수집 비활성(시드 데이터로만 동작)
 * @param baseUrl 기본 https://ecos.bok.or.kr/api
 */
@ConfigurationProperties(prefix = "app.economics.ecos")
public record EcosProperties(String apiKey, String baseUrl) {

    public EcosProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://ecos.bok.or.kr/api";
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
