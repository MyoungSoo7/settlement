package github.lms.lemuel.company.config;

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
 *   <li>RestClient.Builder — web 스타터만으로는 등록되지 않는다. NaverNewsApiClient·Gemini/Claude
 *       감성분석기가 base-url 을 얹어 공유한다. connect 5s / read 12s 타임아웃을 명시해 외부 API
 *       무응답·지연 시 요청 스레드가 무한 hang 하지 않게 한다. 이 상한이 없으면 평판 재계산
 *       (recalcAll)이 느린 Gemini 응답에 동기로 묶여 일일 배치 curl(--max-time 900)을 초과했다
 *       (2026-07-19/20 KubeJobFailed). 타임아웃 시 각 분석기의 fail-open(키워드 폴백)이 받아
 *       배치 총 소요를 유계로 만든다.</li>
 *   <li>ObjectMapper — Boot 4 는 레거시 Jackson2 ObjectMapper 빈을 노출하지 않는다.
 *       shared-common 의 JacksonCompatConfig 를 쓰지 않는 서비스라 뉴스 응답 파싱용으로 자체 제공</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public ObjectMapper newsObjectMapper() {
        return new ObjectMapper();
    }
}
