package github.lms.lemuel.company.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Boot 4 미자동구성 빈 직접 제공.
 *
 * <ul>
 *   <li>RestClient.Builder — web 스타터만으로는 등록되지 않는다 (NaverNewsApiClient 가 base-url 을 얹음)</li>
 *   <li>ObjectMapper — Boot 4 는 레거시 Jackson2 ObjectMapper 빈을 노출하지 않는다.
 *       shared-common 의 JacksonCompatConfig 를 쓰지 않는 서비스라 뉴스 응답 파싱용으로 자체 제공</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public ObjectMapper newsObjectMapper() {
        return new ObjectMapper();
    }
}
