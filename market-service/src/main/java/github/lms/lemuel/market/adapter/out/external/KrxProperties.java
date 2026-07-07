package github.lms.lemuel.market.adapter.out.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털 금융위원회 주식시세정보 연동 설정.
 *
 * @param apiKey   data.go.kr 발급 인증키(Decoding 키) — 미설정(빈 문자열)이면 수집 비활성(시드로만 동작)
 * @param baseUrl  기본 https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService
 * @param pageSize 페이지당 행 수 (기본 1000)
 */
@ConfigurationProperties(prefix = "app.market.krx")
public record KrxProperties(String apiKey, String baseUrl, int pageSize) {

    public KrxProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (pageSize <= 0) {
            pageSize = 1000;
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
