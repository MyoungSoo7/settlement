package github.lms.lemuel.commondata.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서울 열린데이터광장(data.seoul.go.kr) 연동 설정.
 *
 * @param apiKey 열린데이터광장 발급 인증키 — data.go.kr 키와 별개이며 URL 경로에 들어간다.
 *               미설정(빈 문자열)이면 SEOUL_OPENAPI provider 수집 비활성
 */
@ConfigurationProperties(prefix = "app.commondata.seoul")
public record SeoulOpenApiProperties(String apiKey) {

    public SeoulOpenApiProperties {
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
