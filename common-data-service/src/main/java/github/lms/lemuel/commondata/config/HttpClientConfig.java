package github.lms.lemuel.commondata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Boot 4 미자동구성 빈 직접 제공.
 *
 * <ul>
 *   <li>RestClient.Builder — web 스타터만으로는 등록되지 않는다 (DataPortalApiClient 사용).
 *       connect 5s / read 30s 타임아웃을 명시해 API 무응답 시 수집 스레드가 무한 hang 하지 않게 한다.</li>
 *   <li>ObjectMapper — Boot 4 는 레거시 Jackson2 ObjectMapper 빈을 노출하지 않는다.
 *       shared-common 의 JacksonCompatConfig 를 쓰지 않는 서비스라 포털 응답 파싱용으로 자체 제공</li>
 * </ul>
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

    @Bean
    public ObjectMapper portalObjectMapper() {
        return new ObjectMapper();
    }
}
