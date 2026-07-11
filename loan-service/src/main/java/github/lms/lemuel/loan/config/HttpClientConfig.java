package github.lms.lemuel.loan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Boot 4 미자동구성 빈 직접 제공.
 *
 * <p>Spring Boot 4 는 web 스타터만으로 {@code RestClient.Builder} 빈을 등록하지 않는다.
 * financial-statements-service 공개 API 호출용(FinancialApiClient)으로 base-url 을 얹을 빌더를
 * 직접 제공하되, connect 5s / read 10s 타임아웃을 명시해 재무 API 무응답 시 요청 스레드가 무한 hang
 * 하지 않게 한다. ObjectMapper 는 shared-common(JacksonCompatConfig)이 이미 제공하므로 재정의하지 않는다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder loanRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }
}
