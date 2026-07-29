package github.lms.lemuel.loan.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import github.lms.lemuel.loan.application.port.out.BaseRatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * 기준금리 Phase 2 구현 — economics-service(한국은행 기준금리, ECOS 722Y001) 공개 API 조회.
 *
 * <p>{@code GET {base}/api/economics/indicators/BASE_RATE/latest} 의 {@code latest.value} 를 쓴다.
 * Phase 1 의 설정값 어댑터({@code ConfiguredBaseRateAdapter})를 교체하되, 그 설정값은 <b>폴백</b>으로
 * 남긴다 — 폴백 경로가 넓기 때문이다: ECOS 수집이 돌지 않은 환경은 {@code latest=null} 이 정상
 * 응답이고, economics 미기동이면 접속 자체가 실패한다. 어느 경우든 <b>기준금리 조달 실패가 대출
 * 신청을 막으면 안 되므로</b>(가용성 우선) 설정 기준금리로 폴백하고 경고만 남긴다.
 *
 * <p>조달된 금리는 신청 시점에 애그리거트에 스냅샷으로 박히므로({@code RequestSecuredLoanService}),
 * 폴백으로 심사된 대출도 사후에 조건이 흔들리지 않는다 — 폴백 사용 사실은 로그로 추적한다.
 *
 * <p>중도상환수수료는 이 포트를 타지 않는다({@code SecuredLoanPolicy#earlyRepaymentFee} 정적) —
 * economics 장애가 상환 경로에 전파되지 않게 P2-6 에서 미리 끊어 둔 결합이다.
 */
@Component
public class EconomicsBaseRateAdapter implements BaseRatePort {

    private static final Logger log = LoggerFactory.getLogger(EconomicsBaseRateAdapter.class);
    private static final String BASE_RATE_LATEST_URI = "/api/economics/indicators/BASE_RATE/latest";

    private final RestClient restClient;
    private final BigDecimal fallbackRatePercent;

    public EconomicsBaseRateAdapter(RestClient.Builder loanRestClientBuilder,
                                    @Value("${app.loan.economics.base-url:http://localhost:8087}") String baseUrl,
                                    @Value("${app.loan.secured.base-rate-percent:3.5}") BigDecimal fallbackRatePercent) {
        this.restClient = loanRestClientBuilder.baseUrl(baseUrl).build();
        this.fallbackRatePercent = fallbackRatePercent;
    }

    @Override
    public BigDecimal currentBaseRatePercent() {
        try {
            IndicatorSnapshotDto snapshot = restClient.get()
                    .uri(BASE_RATE_LATEST_URI)
                    .retrieve()
                    .body(IndicatorSnapshotDto.class);
            if (snapshot == null || snapshot.latest() == null || snapshot.latest().value() == null) {
                log.warn("economics 기준금리 관측치 없음(latest=null) — 설정 기준금리 {}% 폴백", fallbackRatePercent);
                return fallbackRatePercent;
            }
            return snapshot.latest().value();
        } catch (RestClientException e) {
            log.warn("economics 기준금리 조회 실패 — 설정 기준금리 {}% 폴백: {}", fallbackRatePercent, e.getMessage());
            return fallbackRatePercent;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IndicatorSnapshotDto(String code, ObservationDto latest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ObservationDto(String observedDate, BigDecimal value) {
    }
}
