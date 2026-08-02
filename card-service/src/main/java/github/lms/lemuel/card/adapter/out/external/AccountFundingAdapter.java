package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort;
import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * account-service 내부 재원 API({@code GET /internal/account/sellers/{sellerId}/funding}) 호출 어댑터.
 *
 * <p>settlement 의 {@code OrderReconClient} 전례를 따라 <b>수제 재시도</b>(총 {@value #MAX_ATTEMPTS} 회,
 * 짧은 백오프, 5xx·연결실패만 재시도 / 4xx 즉시 실패)를 쓴다. 리포에서 {@code /internal/**} 호출에
 * Resilience4j 를 쓰는 전례는 없다(스펙 §8 대비 변경 1건, 계획서 참조).
 *
 * <p><b>폴백을 두지 않는다.</b> loan 은 기준금리·담보평가 실패 시 제시값으로 폴백해 신청 가용성을
 * 우선하지만, 재원은 다르다. 재원을 모르는 상태에서 추정 한도를 부여하면 그 자체가 여신 사고다.
 * 마찬가지 이유로 <b>응답을 못 읽었을 때 0 으로 정규화하지 않는다</b> — 재원 0 은 심사 탈락이라
 * account 장애가 "이 셀러는 자격 미달"로 둔갑한다. 실패는 실패로 남긴다.
 */
@Component
public class AccountFundingAdapter implements LoadSellerFundingPort {

    private static final Logger log = LoggerFactory.getLogger(AccountFundingAdapter.class);

    /** 총 시도 횟수 = 최초 1 + 재시도 1. 일시 순단(타임아웃·연결불가·5xx)에 한해 한 번만 더 시도한다. */
    private static final int MAX_ATTEMPTS = 2;

    private static final String PATH = "/internal/account/sellers/{sellerId}/funding";

    private final RestClient client;
    /** 재시도 사이의 짧은 백오프. 테스트 생성자는 0 으로 주입해 지연 없이 검증한다. */
    private final Duration retryBackoff;

    @Autowired
    public AccountFundingAdapter(
            @Value("${app.account-service.base-url:http://localhost:8102}") String baseUrl,
            @Value("${app.internal.api-key:}") String internalApiKey) {
        // account 무응답 시 요청 스레드가 무한 hang 하지 않게 connect/read 타임아웃을 명시한다.
        // 초과 시 ResourceAccessException → call() 이 FundingUnavailableException 으로 번역한다.
        // 이 배선만 프로덕션 전용이다 — requestFactory 를 아래 공용 생성자에서 걸면 테스트의
        // MockRestServiceServer 바인딩(자체 requestFactory)을 덮어써 요청이 실제 네트워크로 나간다.
        this(baseUrl, internalApiKey, RestClient.builder().requestFactory(timeoutFactory()));
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    /**
     * 테스트용(헤더 검증 전용) — 프로덕션 생성자가 그대로 위임하는 <b>같은 배선 경로</b>라
     * {@code X-Internal-Api-Key} 가 실제로 실리는지 검증할 수 있다.
     * (전례인 {@code OrderReconClientTest} 는 이 경로를 우회해 헤더를 검증하지 못한다 — 개선 1건.)
     */
    AccountFundingAdapter(String baseUrl, String internalApiKey, RestClient.Builder builder) {
        builder.baseUrl(baseUrl);
        // 내부 API 공유 시크릿 — account 의 InternalApiKeyFilter 가 검증한다. 미설정 시 헤더 생략(개발).
        // 운영 account 는 fail-closed 라 키가 없으면 401 이 되고, 이는 재시도 없이 즉시 실패한다.
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            builder.defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey);
        }
        this.client = builder.build();
        this.retryBackoff = Duration.ofMillis(200);
    }

    /** 테스트용 — 미리 구성된(예: MockRestServiceServer 바인딩) RestClient 와 백오프 주입. */
    AccountFundingAdapter(RestClient client, Duration retryBackoff) {
        this.client = client;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public SellerFunding load(String sellerId) {
        FundingResponse res = call(sellerId, () -> client.get()
                .uri(PATH, sellerId)
                .retrieve()
                .body(FundingResponse.class));

        if (res == null) {
            // 200 인데 바디가 비었다 — 0 으로 정규화하면 장애가 심사 탈락으로 둔갑한다.
            throw new FundingUnavailableException(
                    "account 재원 응답 바디가 비었습니다(sellerId=" + sellerId + ")");
        }
        return new SellerFunding(
                amount(res.sellerPayable(), "sellerPayable", sellerId),
                amount(res.holdbackPayable(), "holdbackPayable", sellerId));
    }

    /** 금액 문자열 → BigDecimal. 누락·파싱불가 모두 실패다(0 으로 정규화하지 않는다). */
    private BigDecimal amount(String raw, String field, String sellerId) {
        if (raw == null || raw.isBlank()) {
            throw new FundingUnavailableException(
                    "account 재원 응답에 " + field + " 가 없습니다(sellerId=" + sellerId + ")");
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new FundingUnavailableException(
                    "account 재원 응답의 " + field + " 를 금액으로 읽을 수 없습니다: " + raw
                            + "(sellerId=" + sellerId + ")", e);
        }
    }

    /**
     * 전송 실패를 명시적 신호로 번역하되 일시 순단은 1회 재시도한다.
     * 타임아웃·연결불가({@link ResourceAccessException})·5xx({@link HttpServerErrorException}) 는
     * 짧은 백오프 후 재시도하고(총 {@value #MAX_ATTEMPTS} 회), 4xx({@link HttpClientErrorException}) 는
     * 요청 자체 문제(키 오류·경로 오류)라 재시도해도 동일하게 실패하므로 즉시 번역한다.
     */
    private <T> T call(String sellerId, java.util.function.Supplier<T> apiCall) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return apiCall.get();
            } catch (HttpClientErrorException e) {
                log.error("[CardFunding] account 재원 API 4xx sellerId={} — 재시도 없이 실패 처리합니다: {}",
                        sellerId, e.toString());
                throw new FundingUnavailableException(
                        "account 재원 API 4xx 실패(sellerId=" + sellerId + ")", e);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                log.warn("[CardFunding] account 재원 API 일시 실패 sellerId={} 시도={}/{}: {}",
                        sellerId, attempt, MAX_ATTEMPTS, e.toString());
                if (attempt < MAX_ATTEMPTS) {
                    backoff(sellerId);
                }
            } catch (RestClientException e) {
                // 기타 전송 예외(역직렬화 실패 등) — 재시도 의미가 불분명하므로 보수적으로 즉시 실패.
                log.error("[CardFunding] account 재원 API 호출 실패 sellerId={}: {}", sellerId, e.toString());
                throw new FundingUnavailableException(
                        "account 재원 API 호출 실패(sellerId=" + sellerId + ")", e);
            }
        }
        log.error("[CardFunding] account 재원 API 재시도 소진 sellerId={} — 심사를 실패 처리합니다.", sellerId);
        throw new FundingUnavailableException(
                "account 재원 API 호출 실패(sellerId=" + sellerId + ", 재시도 소진)", last);
    }

    /** 재시도 사이 백오프. 인터럽트되면 플래그를 복원하고 즉시 실패로 번역한다. */
    private void backoff(String sellerId) {
        if (retryBackoff.isZero() || retryBackoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryBackoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new FundingUnavailableException(
                    "account 재원 재시도 대기 중 인터럽트(sellerId=" + sellerId + ")", ie);
        }
    }

    /**
     * account {@code InternalAccountController.FundingResponse} 의 JSON 계약.
     * 금액은 <b>문자열</b>이다(DATA-STANDARD N5 — 부동소수 왕복 방지).
     */
    record FundingResponse(String sellerId, String sellerPayable, String holdbackPayable) {
    }
}
