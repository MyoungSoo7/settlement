package github.lms.lemuel.commondata.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털(data.go.kr) 연동 설정.
 *
 * @param apiKey data.go.kr 발급 인증키(Decoding 키) — 계정당 1개로 활용신청한 모든 API 공용.
 *               미설정(빈 문자열)이면 수집 비활성(수집 데이터 없음 — 샘플 시드는 제거됨)
 */
@ConfigurationProperties(prefix = "app.commondata.portal")
public record DataPortalProperties(String apiKey) {

    public DataPortalProperties {
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
