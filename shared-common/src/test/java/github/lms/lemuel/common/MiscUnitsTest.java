package github.lms.lemuel.common;

import github.lms.lemuel.common.config.kafka.KafkaConfig;
import github.lms.lemuel.common.observability.aop.ObservabilityAopProperties;
import github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher;
import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 값/설정 객체 단위 검증 — KafkaConfig 토픽 정의, AOP 프로퍼티 접근자, NoOp opssignal.
 */
class MiscUnitsTest {

    @Test
    @DisplayName("KafkaConfig: 4개 토픽이 지정 파티션 수로 정의된다")
    void kafkaTopics() {
        KafkaConfig config = new KafkaConfig(5);
        NewTopic captured = config.paymentCapturedTopic();
        NewTopic refunded = config.paymentRefundedTopic();
        NewTopic capturedDlt = config.paymentCapturedDltTopic();
        NewTopic refundedDlt = config.paymentRefundedDltTopic();

        assertThat(captured.name()).isEqualTo("lemuel.payment.captured");
        assertThat(captured.numPartitions()).isEqualTo(5);
        assertThat(refunded.name()).isEqualTo("lemuel.payment.refunded");
        assertThat(capturedDlt.name()).isEqualTo("lemuel.payment.captured.DLT");
        assertThat(refundedDlt.name()).isEqualTo("lemuel.payment.refunded.DLT");
        assertThat(capturedDlt.numPartitions()).isEqualTo(5);
    }

    @Test
    @DisplayName("ObservabilityAopProperties: 기본값 + setter/getter")
    void observabilityProps() {
        ObservabilityAopProperties p = new ObservabilityAopProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getSlowThresholdMs()).isEqualTo(500);
        assertThat(p.isLogArgs()).isFalse();
        assertThat(p.getMaxArgLength()).isEqualTo(200);

        p.setEnabled(false);
        p.setSlowThresholdMs(1000);
        p.setLogArgs(true);
        p.setMaxArgLength(50);

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getSlowThresholdMs()).isEqualTo(1000);
        assertThat(p.isLogArgs()).isTrue();
        assertThat(p.getMaxArgLength()).isEqualTo(50);
    }

    @Test
    @DisplayName("NoOpOpsSignalPublisher: 두 emit 오버로드 모두 안전한 no-op")
    void noOpOpsSignal() {
        NoOpOpsSignalPublisher publisher = new NoOpOpsSignalPublisher();
        OpsSignal signal = new OpsSignal(OpsSignalCategory.SETTLEMENT_FAILED, "settlement-service",
                "Settlement", "1", OpsSignal.SEVERITY_ERROR, java.time.Instant.now(), Map.of("k", "v"));
        publisher.emit(signal);
        publisher.emit(OpsSignalCategory.PAYMENT_FAILED, "Payment", "2", Map.of("a", 1));
    }
}
