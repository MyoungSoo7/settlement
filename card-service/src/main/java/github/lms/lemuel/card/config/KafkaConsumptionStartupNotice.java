package github.lms.lemuel.card.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka 소비 활성 여부를 기동 시 한 줄로 알린다.
 *
 * <p>card-service 의 컨슈머 6종은 모두 {@code @ConditionalOnProperty(app.kafka.enabled=true)} 라
 * 기본값(false)에서는 빈 자체가 만들어지지 않는다. 이때 실패하는 것은 아무것도 없다 —
 * 조직·멤버십·평판 프로젝션과 매입→경비보고서 자동 생성이 <b>에러 없이 그냥 갱신되지 않을</b> 뿐이다.
 * 빈 프로젝션은 "데이터가 아직 없음"과 구분되지 않아, 사람이 원인을 찾는 데 오래 걸린다.
 *
 * <p>기본값을 뒤집지 않는 이유: 코어 11개 서비스가 모두 {@code APP_KAFKA_ENABLED:false} 로 통일돼 있고,
 * 이는 브로커 없이 서비스를 단독 기동할 수 있게 하는 의도된 규약이다. 그래서 기본값이 아니라
 * <b>침묵</b>을 고친다 — 통합 환경(compose·k8s)은 {@code APP_KAFKA_ENABLED=true} 를 주입한다.
 */
@Configuration
public class KafkaConsumptionStartupNotice {

    static final String DISABLED_MESSAGE =
            "Kafka 소비 비활성(app.kafka.enabled=false) — 조직·멤버십·평판 프로젝션과 "
                    + "매입→경비보고서 자동 생성이 갱신되지 않습니다. 통합 환경에서는 APP_KAFKA_ENABLED=true 를 주입하세요.";
    static final String ENABLED_MESSAGE = "Kafka 소비 활성 — 조직·멤버십·평판 프로젝션과 경비보고서 자동 생성이 동작합니다.";

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumptionStartupNotice.class);

    private final boolean kafkaEnabled;

    public KafkaConsumptionStartupNotice(@Value("${app.kafka.enabled:false}") boolean kafkaEnabled) {
        this.kafkaEnabled = kafkaEnabled;
    }

    @PostConstruct
    public void announce() {
        if (kafkaEnabled) {
            log.info(ENABLED_MESSAGE);
            return;
        }
        log.warn(DISABLED_MESSAGE);
    }
}
