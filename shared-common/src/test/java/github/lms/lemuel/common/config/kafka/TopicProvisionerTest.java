package github.lms.lemuel.common.config.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토픽 프로비저닝 — **없는 토픽만 만들고, 있는 토픽은 절대 건드리지 않는다**.
 *
 * <p>Spring 의 {@code KafkaAdmin} 은 {@code NewTopic} 이 선언한 파티션이 더 많으면 기존 토픽의
 * 파티션을 <b>자동으로 늘린다</b>(Spring Kafka 레퍼런스 "Configuring Topics"). 키 기반 순서 보장을
 * 쓰는 이 저장소에서 그 증설은 재해시 = 순서 붕괴다. 카탈로그를 도입하면서 그 사고를 유발하면
 * 본말전도이므로, 프로비저너는 증설 대신 <b>드리프트로 보고</b>하고 조치는 사람에게 넘긴다.
 */
class TopicProvisionerTest {

    /** 테스트용 인메모리 브로커 — 실제 AdminClient 없이 프로비저닝 판단만 검증한다. */
    private static final class FakeTopicAdmin implements TopicAdmin {
        private final Map<String, Integer> existing;
        private final List<String> created = new ArrayList<>();

        FakeTopicAdmin(Map<String, Integer> existing) {
            this.existing = new LinkedHashMap<>(existing);
        }

        @Override
        public Map<String, Integer> describePartitions(Set<String> names) {
            Map<String, Integer> found = new LinkedHashMap<>();
            for (String name : names) {
                if (existing.containsKey(name)) found.put(name, existing.get(name));
            }
            return found;
        }

        @Override
        public void create(String name, int partitions, int retentionDays) {
            created.add(name + ":" + partitions + ":" + retentionDays + "d");
            existing.put(name, partitions);
        }
    }

    private static TopicCatalog catalogOf(TopicCatalog.Topic... topics) {
        return TopicCatalog.of(List.of(topics));
    }

    private static TopicCatalog.Topic paymentCaptured(int partitions) {
        return new TopicCatalog.Topic("lemuel.payment.captured", "order-service", "paymentId", partitions, 7);
    }

    @Test
    @DisplayName("없는 토픽은 카탈로그 파티션 수로 만든다")
    void createsMissingTopic() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

        TopicProvisioner.Report report =
                new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

        assertThat(admin.created).contains("lemuel.payment.captured:3:7d");
        assertThat(report.created()).contains("lemuel.payment.captured");
        assertThat(report.drifted()).isEmpty();
    }

    @Test
    @DisplayName("DLT 도 원본과 같은 파티션 수로 함께 만든다 — 자동생성에 맡기면 브로커 기본값(1)로 갈린다")
    void createsDeadLetterTopicAlongside() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

        new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(6)), "order-service");

        assertThat(admin.created).contains("lemuel.payment.captured.DLT:6:30d");
    }

    @Test
    @DisplayName("이미 있고 파티션이 충분하면 아무것도 하지 않는다")
    void leavesSatisfiedTopicAlone() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                "lemuel.payment.captured", 3, "lemuel.payment.captured.DLT", 3));

        TopicProvisioner.Report report =
                new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

        assertThat(admin.created).isEmpty();
        assertThat(report.created()).isEmpty();
        assertThat(report.drifted()).isEmpty();
    }

    @Test
    @DisplayName("기존 토픽의 파티션이 카탈로그보다 적어도 늘리지 않는다 — 증설은 재해시 = 순서 붕괴")
    void neverGrowsExistingTopic() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                "lemuel.payment.captured", 1, "lemuel.payment.captured.DLT", 1));

        TopicProvisioner.Report report =
                new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

        assertThat(admin.created).as("증설도 재생성도 없어야 한다").isEmpty();
        assertThat(report.drifted())
                .extracting(TopicProvisioner.Drift::topic, TopicProvisioner.Drift::declared,
                        TopicProvisioner.Drift::actual)
                .contains(org.assertj.core.groups.Tuple.tuple("lemuel.payment.captured", 3, 1));
    }

    @Test
    @DisplayName("파티션이 카탈로그보다 많아도 드리프트로 보고한다 — 카탈로그가 현실과 어긋난 상태다")
    void reportsUpwardDriftToo() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                "lemuel.payment.captured", 6, "lemuel.payment.captured.DLT", 6));

        TopicProvisioner.Report report =
                new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

        assertThat(report.drifted())
                .extracting(TopicProvisioner.Drift::topic, TopicProvisioner.Drift::actual)
                .contains(org.assertj.core.groups.Tuple.tuple("lemuel.payment.captured", 6));
    }

    @Test
    @DisplayName("소유하지 않은 토픽은 만들지 않는다 — 컨슈머가 토픽을 만들면 파티션 수 결정 주체가 둘이 된다")
    void ignoresTopicsOwnedByOtherServices() {
        FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

        TopicProvisioner.Report report =
                new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "settlement-service");

        assertThat(admin.created).isEmpty();
        assertThat(report.created()).isEmpty();
    }
}
