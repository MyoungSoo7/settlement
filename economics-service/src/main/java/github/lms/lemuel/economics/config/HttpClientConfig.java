package github.lms.lemuel.economics.config;

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
 *   <li>RestClient.Builder — web 스타터만으로는 등록되지 않는다 (EcosApiClient 가 base-url 을 얹음).
 *       connect 5s / read 30s 타임아웃을 명시해 ECOS 무응답 시 수집 스레드가 무한 hang 하지 않게 한다.</li>
 *   <li>ObjectMapper — Boot 4 는 레거시 Jackson2 ObjectMapper 빈을 노출하지 않는다.
 *       shared-common 의 JacksonCompatConfig 를 쓰지 않는 서비스라 ECOS 응답 파싱용으로 자체 제공</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        // Boot 4 는 RestTemplateBuilder/ClientHttpRequestFactorySettings 자동구성이 없어
        // order-service(TossPaymentService) 와 같은 방식으로 팩토리에 타임아웃을 직접 건다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public ObjectMapper ecosObjectMapper() {
        return new ObjectMapper();
    }
}
