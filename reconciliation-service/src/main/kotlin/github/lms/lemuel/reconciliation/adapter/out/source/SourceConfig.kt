package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconciliationSource
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 대사 소스 등록 지점.
 *
 * 핵심 규칙: **샘플 소스는 `demo` 프로파일에서만, 실 소스는 그 외에서만** 뜬다. 둘이 같이 뜨면
 * 실제 원천과 허구 데이터가 한 대사에 섞여 결과가 무의미해진다.
 *
 * 예전에는 두 샘플이 `@Component` 라 프로덕션에서도 무조건 등록됐고 실 소스는 아예 빈이
 * 아니어서, 대사 배치가 늘 샘플만 보고 돌았다(2026-07-30 프로덕션 로그로 확인).
 */
@Configuration
@EnableConfigurationProperties(SourceProperties::class)
class SourceConfig {

    /** 실 원천(비-demo). 주소가 틀리면 fetch 가 예외로 떨어지고 스케줄러가 실패로 기록한다 — 조용한 빈 결과보다 낫다. */
    @Configuration
    @Profile("!demo")
    class LiveSources {

        private val log = LoggerFactory.getLogger(javaClass)

        @Bean
        fun settlementHttpSource(props: SourceProperties): ReconciliationSource {
            log.info("EXPECTED 소스 = settlement-http ({})", props.settlementBaseUrl)
            return SettlementHttpSource(internalClient(props.settlementBaseUrl, props.internalApiKey))
        }

        @Bean
        fun paymentHttpSource(props: SourceProperties): ReconciliationSource {
            log.info("ACTUAL 소스 = payment-http ({})", props.paymentBaseUrl)
            return PaymentHttpSource(internalClient(props.paymentBaseUrl, props.internalApiKey))
        }

        /** 내부 API 공유 시크릿 헤더를 항상 싣는 클라이언트. 키가 비면 헤더 없이 호출(로컬 fail-open 경로). */
        private fun internalClient(baseUrl: String, apiKey: String): RestClient =
            RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .apply { if (apiKey.isNotBlank()) it.defaultHeader(INTERNAL_API_KEY_HEADER, apiKey) }
                .requestFactory(
                    SimpleClientHttpRequestFactory().apply {
                        setConnectTimeout(Duration.ofSeconds(5))
                        setReadTimeout(Duration.ofSeconds(30))
                    },
                )
                .build()
    }

    /**
     * 번들 샘플 — 데모/로컬 전용. `SPRING_PROFILES_ACTIVE=demo` 일 때만 등록된다.
     * 프로덕션에서 이게 뜨면 대사 결과가 통째로 허구가 되므로 절대 기본값으로 두지 않는다.
     */
    @Configuration
    @Profile("demo")
    class DemoSources {
        @Bean
        fun sampleExpectedSource(): ReconciliationSource = SampleExpectedSource()

        @Bean
        fun sampleActualSource(): ReconciliationSource = SampleActualSource()
    }

    companion object {
        const val INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"
    }
}

/** `app.sources.*` — 실 원천 주소와 내부 API 공유 시크릿. */
@ConfigurationProperties(prefix = "app.sources")
data class SourceProperties(
    /** settlement-service base URL (EXPECTED 소스). */
    val settlementBaseUrl: String = "",
    /** order-service base URL (ACTUAL 소스). */
    val paymentBaseUrl: String = "",
    /** order/settlement 과 동일한 공유 시크릿. */
    val internalApiKey: String = "",
)
