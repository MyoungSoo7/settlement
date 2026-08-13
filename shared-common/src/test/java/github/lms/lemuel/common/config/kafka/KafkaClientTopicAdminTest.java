package github.lms.lemuel.common.config.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaClientTopicAdminTest {

    private final Admin admin = mock(Admin.class);
    private final KafkaClientTopicAdmin topicAdmin = new KafkaClientTopicAdmin(() -> admin);

    private static TopicDescription description(String name, int partitions) {
        List<TopicPartitionInfo> infos = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            infos.add(new TopicPartitionInfo(i, null, List.of(), List.of()));
        }
        return new TopicDescription(name, false, infos);
    }

    @SuppressWarnings("unchecked")
    private static KafkaFuture<TopicDescription> futureOf(TopicDescription value) throws Exception {
        KafkaFuture<TopicDescription> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenReturn(value);
        return future;
    }

    @SuppressWarnings("unchecked")
    private static KafkaFuture<TopicDescription> failingFuture(Throwable cause) throws Exception {
        KafkaFuture<TopicDescription> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new ExecutionException(cause));
        return future;
    }

    private void stubDescribe(Map<String, KafkaFuture<TopicDescription>> futures) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(futures);
        when(admin.describeTopics(any(Collection.class))).thenReturn(result);
    }

    @Test
    @DisplayName("실재하는 토픽은 파티션 수를 돌려준다")
    void describesExistingTopic() throws Exception {
        Map<String, KafkaFuture<TopicDescription>> futures = new LinkedHashMap<>();
        futures.put("lemuel.payment.captured", futureOf(description("lemuel.payment.captured", 6)));
        stubDescribe(futures);

        assertThat(topicAdmin.describePartitions(Set.of("lemuel.payment.captured")))
                .containsEntry("lemuel.payment.captured", 6);
    }

    @Test
    @DisplayName("없는 토픽은 결과에서 빠진다 — '파티션 0' 같은 애매한 값을 만들지 않는다")
    void omitsUnknownTopic() throws Exception {
        Map<String, KafkaFuture<TopicDescription>> futures = new LinkedHashMap<>();
        futures.put("lemuel.absent.topic", failingFuture(new UnknownTopicOrPartitionException()));
        stubDescribe(futures);

        assertThat(topicAdmin.describePartitions(Set.of("lemuel.absent.topic"))).isEmpty();
    }

    @Test
    @DisplayName("조회할 이름이 없으면 브로커에 접속하지 않는다")
    void skipsBrokerWhenNothingToDescribe() {
        assertThat(topicAdmin.describePartitions(Set.of())).isEmpty();

        verify(admin, never()).describeTopics(any(Collection.class));
    }

    @Test
    @DisplayName("조회 실패(미지의 원인)는 타입 예외로 올린다 — 조용히 '없음'으로 처리하면 토픽을 덮어 만든다")
    void surfacesUnexpectedDescribeFailure() throws Exception {
        Map<String, KafkaFuture<TopicDescription>> futures = new LinkedHashMap<>();
        futures.put("lemuel.payment.captured", failingFuture(new IllegalStateException("boom")));
        stubDescribe(futures);

        assertThatThrownBy(() -> topicAdmin.describePartitions(Set.of("lemuel.payment.captured")))
                .isInstanceOf(InvalidTopicCatalogException.class);
    }

    @Test
    @DisplayName("파티션 수와 보존기간을 그대로 실어 토픽을 만든다")
    void createsTopicWithDeclaredSpec() throws Exception {
        stubCreate(null);

        topicAdmin.create("lemuel.payment.captured", 6, 7);

        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(admin).createTopics(captor.capture());
        NewTopic created = captor.getValue().iterator().next();
        assertThat(created.name()).isEqualTo("lemuel.payment.captured");
        assertThat(created.numPartitions()).isEqualTo(6);
        assertThat(created.configs())
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 60 * 60 * 1000));
    }

    @Test
    @DisplayName("동시 기동으로 이미 만들어진 경우는 통과한다 — 결과가 같기 때문이다")
    void toleratesConcurrentCreation() throws Exception {
        stubCreate(new TopicExistsException("already"));

        topicAdmin.create("lemuel.payment.captured", 3, 7);
    }

    @Test
    @DisplayName("그 밖의 생성 실패는 타입 예외로 올린다")
    void surfacesCreateFailure() throws Exception {
        stubCreate(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> topicAdmin.create("lemuel.payment.captured", 3, 7))
                .isInstanceOf(InvalidTopicCatalogException.class);
    }

    @Test
    @DisplayName("조회 타임아웃은 타입 예외로 올린다")
    @SuppressWarnings("unchecked")
    void surfacesDescribeTimeout() throws Exception {
        KafkaFuture<TopicDescription> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new java.util.concurrent.TimeoutException());
        Map<String, KafkaFuture<TopicDescription>> futures = new LinkedHashMap<>();
        futures.put("lemuel.payment.captured", future);
        stubDescribe(futures);

        assertThatThrownBy(() -> topicAdmin.describePartitions(Set.of("lemuel.payment.captured")))
                .isInstanceOf(InvalidTopicCatalogException.class);
    }

    @Test
    @DisplayName("생성 타임아웃은 타입 예외로 올린다")
    @SuppressWarnings("unchecked")
    void surfacesCreateTimeout() throws Exception {
        CreateTopicsResult result = mock(CreateTopicsResult.class);
        KafkaFuture<Void> all = mock(KafkaFuture.class);
        when(all.get(anyLong(), any())).thenThrow(new java.util.concurrent.TimeoutException());
        when(result.all()).thenReturn(all);
        when(admin.createTopics(any(Collection.class))).thenReturn(result);

        assertThatThrownBy(() -> topicAdmin.create("lemuel.payment.captured", 3, 7))
                .isInstanceOf(InvalidTopicCatalogException.class);
    }

    @Test
    @DisplayName("KafkaAdmin 설정에서 AdminClient 를 만드는 프로덕션 생성자도 배선된다")
    void wiresFromKafkaAdmin() {
        org.springframework.kafka.core.KafkaAdmin kafkaAdmin =
                new org.springframework.kafka.core.KafkaAdmin(Map.of("bootstrap.servers", "localhost:9092"));

        assertThat(new KafkaClientTopicAdmin(kafkaAdmin).describePartitions(Set.of()))
                .as("조회 대상이 없으면 브로커 접속 없이 빈 결과 — 생성자 배선만 검증한다")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void stubCreate(Throwable cause) throws Exception {
        CreateTopicsResult result = mock(CreateTopicsResult.class);
        KafkaFuture<Void> all = mock(KafkaFuture.class);
        if (cause == null) {
            when(all.get(anyLong(), any())).thenReturn(null);
        } else {
            when(all.get(anyLong(), any())).thenThrow(new ExecutionException(cause));
        }
        when(result.all()).thenReturn(all);
        when(admin.createTopics(any(Collection.class))).thenReturn(result);
    }
}
