package github.lms.lemuel.card.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 소비 비활성 상태가 <b>조용히</b> 지나가지 않는지 검증한다.
 *
 * <p>card-service 컨슈머 6종은 app.kafka.enabled=false 면 빈이 아예 생성되지 않고, 그때 실패하는 것은
 * 아무것도 없다 — 프로젝션이 에러 없이 비어 있을 뿐이라 원인 추적이 오래 걸린다. 기동 경고가 그 침묵을 깬다.
 * 이 테스트가 사라지면 경고도 조용히 사라질 수 있으므로 회귀 가드로 남긴다.
 */
class KafkaConsumptionStartupNoticeTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(KafkaConsumptionStartupNotice.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("Kafka 소비 비활성이면 무엇이 멈추는지 적은 WARN 을 남긴다")
    void warnsWhenDisabled() {
        new KafkaConsumptionStartupNotice(false).announce();

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();

        assertThat(warns).hasSize(1);
        String message = warns.getFirst().getFormattedMessage();
        // 운영자가 바로 조치할 수 있어야 한다 — 원인(설정 키)과 영향(무엇이 안 도는지)이 함께 있어야 의미가 있다.
        assertThat(message).contains("app.kafka.enabled=false");
        assertThat(message).contains("APP_KAFKA_ENABLED=true");
        assertThat(message).contains("프로젝션");
    }

    @Test
    @DisplayName("Kafka 소비 활성이면 WARN 을 남기지 않는다 — 정상 상태를 경고로 오염시키지 않는다")
    void doesNotWarnWhenEnabled() {
        new KafkaConsumptionStartupNotice(true).announce();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.INFO);
    }
}
