package github.lms.lemuel.company.config;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
 * </ul>
 *
 * <p><b>ObjectMapper</b>: Boot 4 는 레거시 Jackson2 ObjectMapper 빈을 노출하지 않으므로
 * shared-common 의 {@link JacksonCompatConfig} 를 <b>명시적으로 @Import</b> 한다. 이 서비스는 스캔을
 * {@code github.lms.lemuel.company} + {@code common.outbox} 로 한정해 자체 최소 SecurityConfig 를
 * 유지하는데, {@code JacksonCompatConfig} 는 {@code common.config} 에 있어 그 범위에 걸리지 않는다.
 * 그래서 outbox 금액 wire 표준화(DATA-STANDARD N5)로 발행 어댑터가
 * {@code @Qualifier("outboxObjectMapper")} 를 요구하게 된 뒤 이 서비스는 부팅 자체가 불가능했다
 * (기동 시 {@code CompanyReputationEventPublisherAdapter} 주입 실패). 뉴스 파싱용 자체 빈을 따로 두지
 * 않는 이유는, 두 개의 ObjectMapper 빈 중 하나가 {@code @Primary} 로 다른 하나를 덮는 구조가 더
 * 혼란스럽기 때문이다 — 공용 빈(JavaTimeModule 등록)이 뉴스 파싱 요구의 상위집합이다.
 */
@Configuration
@Import(JacksonCompatConfig.class)
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder().requestFactory(factory);
    }
}
