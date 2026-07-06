package github.lms.lemuel.company.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.company.application.port.out.LoadSellerLinkPort;
import github.lms.lemuel.company.application.port.out.PublishReputationEventPort;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 평판 등급 변동을 Transactional Outbox 에 기록한다. 스냅샷 저장과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고(둘 다 커밋되거나 둘 다 롤백), shared-common 의 OutboxPublisherScheduler 가
 * Kafka 로 비동기 발행한다.
 *
 * <p>토픽 라우팅: aggregateType="Company" + eventType="CompanyReputationChanged"
 *   → {@code lemuel.company.reputation_changed} (KafkaOutboxPublisher 규칙).
 *
 * <p>페이로드는 String/숫자만 담는다 — company 의 ObjectMapper 는 JavaTimeModule 미등록이라
 * java.time 값은 직접 문자열화한다.
 */
@Component
public class CompanyReputationEventPublisherAdapter implements PublishReputationEventPort {

    private static final String AGGREGATE_TYPE = "Company";
    private static final String EVENT_TYPE = "CompanyReputationChanged";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final LoadSellerLinkPort loadSellerLinkPort;
    private final ObjectMapper objectMapper;

    public CompanyReputationEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                                  LoadSellerLinkPort loadSellerLinkPort,
                                                  ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.loadSellerLinkPort = loadSellerLinkPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishReputationChanged(ReputationScore score, ReputationGrade previousGrade) {
        // 이 기업에 링크된 셀러들을 동봉 → loan 이 셀러별 신용 haircut 에 반영 (ADR 0023 Phase 3 후속)
        List<Long> sellerIds = loadSellerLinkPort.sellersOf(score.stockCode());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stockCode", score.stockCode());
        payload.put("snapshotDate", score.snapshotDate().toString());
        payload.put("score", score.score());
        payload.put("grade", score.grade().name());
        payload.put("previousGrade", previousGrade == null ? null : previousGrade.name());
        payload.put("articleCount", score.articleCount());
        payload.put("negativeCount", score.negativeCount());
        payload.put("sellerIds", sellerIds);
        payload.put("calculatedAt", score.calculatedAt().toString());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                score.stockCode(),
                EVENT_TYPE,
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("평판 이벤트 직렬화 실패", e);
        }
    }
}
