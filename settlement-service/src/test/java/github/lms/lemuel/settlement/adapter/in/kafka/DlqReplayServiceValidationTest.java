package github.lms.lemuel.settlement.adapter.in.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * DlqReplayService 의 토픽 검증 로직 + 순수 record({@link DlqReplayService.ReplayResult}) 단위 테스트.
 * inspect/replay 의 consumer happy-path 는 {@link DlqReplayServiceEmbeddedKafkaTest} 에서 별도로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DlqReplayServiceValidationTest {

    @Mock KafkaTemplate<String, String> dltKafkaTemplate;

    private DlqReplayService newService() {
        return new DlqReplayService("localhost:9092", dltKafkaTemplate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("inspect — .DLT 로 끝나지 않는 토픽은 IllegalArgumentException")
    void inspect_rejectsNonDltTopic() {
        DlqReplayService service = newService();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.inspect("lemuel.payment.captured", 10))
                .withMessageContaining("Topic must end with .DLT");
    }

    @Test
    @DisplayName("inspect — null 토픽은 IllegalArgumentException")
    void inspect_rejectsNullTopic() {
        DlqReplayService service = newService();

        assertThatIllegalArgumentException().isThrownBy(() -> service.inspect(null, 10));
    }

    @Test
    @DisplayName("replay — .DLT 로 끝나지 않는 토픽은 IllegalArgumentException")
    void replay_rejectsNonDltTopic() {
        DlqReplayService service = newService();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.replay("lemuel.payment.captured", 10))
                .withMessageContaining("Topic must end with .DLT");
    }

    @Test
    @DisplayName("ReplayResult record — accessor 왕복 검증")
    void replayResult_accessors() {
        DlqReplayService.ReplayResult result =
                new DlqReplayService.ReplayResult("lemuel.payment.captured", "lemuel.payment.captured.DLT", 3, 1);

        org.assertj.core.api.Assertions.assertThat(result.sourceTopic()).isEqualTo("lemuel.payment.captured");
        org.assertj.core.api.Assertions.assertThat(result.dltTopic()).isEqualTo("lemuel.payment.captured.DLT");
        org.assertj.core.api.Assertions.assertThat(result.sent()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(result.skipped()).isEqualTo(1);
    }
}
