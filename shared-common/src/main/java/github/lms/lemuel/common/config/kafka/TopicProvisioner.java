package github.lms.lemuel.common.config.kafka;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카탈로그가 선언한 토픽 중 <b>없는 것만</b> 만든다 (ADR 0035).
 *
 * <p><b>왜 Spring 의 {@code NewTopic} 빈을 쓰지 않는가.</b> {@code KafkaAdmin} 은 "NewTopic 이 선언한
 * 파티션이 기존 토픽보다 많으면 파티션을 늘린다"(Spring Kafka 레퍼런스 <i>Configuring Topics</i>).
 * 편의 기능이지만 이 저장소에서는 사고다 — 메시지 키가 outbox {@code aggregateId} 이므로 파티션 수가
 * 바뀌면 {@code hash(key) % N} 이 바뀌고, 같은 애그리거트의 이벤트가 다른 파티션으로 흩어져 순서 보장이
 * 소급 붕괴한다. 토픽 정의를 코드로 옮기는 작업이 그 붕괴를 유발하면 본말전도이므로, 증설 경로가
 * 아예 없는 프로비저너를 쓴다.
 *
 * <p>파티션 수가 카탈로그와 다르면 만들지도 고치지도 않고 {@link Drift} 로 보고한다. 조치는 사람이
 * 판단한다 — 브로커를 카탈로그에 맞출지(rpk), 카탈로그를 현실에 맞출지는 도메인 판단이다.
 */
public class TopicProvisioner {

    /** 프로비저닝 결과 — 만든 토픽과, 카탈로그와 어긋난 토픽. */
    public record Report(List<String> created, List<Drift> drifted) {
    }

    /** 브로커 실제 파티션 수가 카탈로그 선언과 다른 상태. */
    public record Drift(String topic, int declared, int actual) {
    }

    private final TopicAdmin admin;

    public TopicProvisioner(TopicAdmin admin) {
        this.admin = admin;
    }

    public Report provision(TopicCatalog catalog, String module) {
        List<TopicCatalog.Spec> specs = new ArrayList<>();
        for (TopicCatalog.Topic topic : catalog.ownedBy(module)) {
            specs.add(topic.spec());
            specs.add(topic.deadLetterSpec());
        }

        Set<String> names = new LinkedHashSet<>(specs.stream().map(TopicCatalog.Spec::name).toList());
        Map<String, Integer> actual = admin.describePartitions(names);

        List<String> created = new ArrayList<>();
        List<Drift> drifted = new ArrayList<>();
        for (TopicCatalog.Spec spec : specs) {
            Integer existing = actual.get(spec.name());
            if (existing == null) {
                admin.create(spec.name(), spec.partitions(), spec.retentionDays());
                created.add(spec.name());
            } else if (existing != spec.partitions()) {
                // 늘리지도 줄이지도 않는다 — 파티션 변경은 키 재해시이고, 재해시는 이미 쌓인 메시지의
                // 순서 보장까지 소급해서 깬다. 조치(rpk add-partitions 또는 카탈로그 정정)는 사람이 판단한다.
                drifted.add(new Drift(spec.name(), spec.partitions(), existing));
            }
        }
        return new Report(created, drifted);
    }
}
