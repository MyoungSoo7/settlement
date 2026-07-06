package github.lms.lemuel.company.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 뉴스 검색 API 연동 설정.
 *
 * @param clientId     developers.naver.com 발급 Client ID — 미설정이면 수집 비활성
 * @param clientSecret Client Secret
 * @param baseUrl      기본 https://openapi.naver.com
 * @param display      기업당 1회 검색 결과 수 (API 최대 100)
 */
@ConfigurationProperties(prefix = "app.company.naver")
public record NaverNewsProperties(String clientId, String clientSecret, String baseUrl, int display) {

    public NaverNewsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://openapi.naver.com";
        }
        if (clientId == null) {
            clientId = "";
        }
        if (clientSecret == null) {
            clientSecret = "";
        }
        if (display <= 0 || display > 100) {
            display = 20;
        }
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }
}
