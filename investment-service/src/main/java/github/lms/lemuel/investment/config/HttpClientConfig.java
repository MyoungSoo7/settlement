package github.lms.lemuel.investment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Boot 4 미자동구성 빈 직접 제공.
 *
 * <p>RestClient.Builder — web 스타터만으로는 등록되지 않는다(FinancialStatementsApiClient 가
 * financial 공개 API base-url 을 얹는다). connect 5s / read 30s 타임아웃을 명시해 financial 서비스
 * 무응답 시 요청 스레드가 무한 hang 하지 않게 한다(market-service HttpClientConfig 와 동일 정책).
 *
 * <p>ObjectMapper 는 자체 제공하지 않는다 — investment-service 는 루트 {@code github.lms.lemuel} 전체를
 * 스캔하므로 shared-common 의 {@code JacksonCompatConfig} 가 제공하는 ObjectMapper 를 그대로 쓴다
 * (market/economics 처럼 제한 스캔이 아니라 loan 처럼 전체 스캔이다 — 중복 빈 방지).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory);
    }
}
