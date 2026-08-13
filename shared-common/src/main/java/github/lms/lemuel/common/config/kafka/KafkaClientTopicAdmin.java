package github.lms.lemuel.common.config.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link TopicAdmin} 의 Kafka {@code AdminClient} 어댑터.
 *
 * <p>파티션 증설·토픽 삭제 경로는 구현하지 않는다 — 포트에 없기 때문이다(설계 의도는 {@link TopicAdmin}).
 */
public class KafkaClientTopicAdmin implements TopicAdmin {

    private static final Logger log = LoggerFactory.getLogger(KafkaClientTopicAdmin.class);
    private static final long TIMEOUT_SEC = 10;
    private static final short REPLICAS = 1; // 개발/데모 단일 브로커. 프로덕션은 최소 3.

    private final Supplier<Admin> adminFactory;

    public KafkaClientTopicAdmin(KafkaAdmin kafkaAdmin) {
        this(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
    }

    /** 테스트 진입점 — {@code Admin.create} 가 정적 팩토리라 주입 지점을 따로 연다. */
    KafkaClientTopicAdmin(Supplier<Admin> adminFactory) {
        this.adminFactory = adminFactory;
    }

    @Override
    public Map<String, Integer> describePartitions(Set<String> names) {
        Map<String, Integer> found = new LinkedHashMap<>();
        if (names.isEmpty()) return found;

        try (Admin admin = adminFactory.get()) {
            Map<String, KafkaFuture<TopicDescription>> futures = admin.describeTopics(names).topicNameValues();
            for (Map.Entry<String, KafkaFuture<TopicDescription>> entry : futures.entrySet()) {
                describeOne(entry.getKey(), entry.getValue()).ifPresent(
                        count -> found.put(entry.getKey(), count));
            }
        }
        return found;
    }

    /** 없는 토픽은 빈 값 — "파티션 수 0" 같은 애매한 상태로 만들지 않는다. */
    private java.util.Optional<Integer> describeOne(String name, KafkaFuture<TopicDescription> future) {
        try {
            return java.util.Optional.of(future.get(TIMEOUT_SEC, TimeUnit.SECONDS).partitions().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("토픽 조회 중 인터럽트: " + name, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return java.util.Optional.empty();
            }
            throw new InvalidTopicCatalogException("토픽 조회 실패: " + name, e);
        } catch (TimeoutException e) {
            throw new InvalidTopicCatalogException("토픽 조회 타임아웃: " + name, e);
        }
    }

    @Override
    public void create(String name, int partitions, int retentionDays) {
        NewTopic topic = new NewTopic(name, partitions, REPLICAS)
                .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(retentionDays).toMillis())));

        try (Admin admin = adminFactory.get()) {
            admin.createTopics(List.of(topic)).all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            log.info("Kafka 토픽 생성: {} (partitions={}, retention={}d)", name, partitions, retentionDays);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("토픽 생성 중 인터럽트: " + name, e);
        } catch (ExecutionException e) {
            // 다중 인스턴스가 동시에 기동하면 한쪽만 이기고 나머지는 이 예외를 본다 — 결과는 같으므로 통과.
            if (e.getCause() instanceof TopicExistsException) {
                log.debug("토픽이 이미 있다(동시 기동): {}", name);
                return;
            }
            throw new InvalidTopicCatalogException("토픽 생성 실패: " + name, e);
        } catch (TimeoutException e) {
            throw new InvalidTopicCatalogException("토픽 생성 타임아웃: " + name, e);
        }
    }
}
