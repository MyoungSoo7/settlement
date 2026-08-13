package github.lms.lemuel.common.config.kafka;

import java.util.Map;
import java.util.Set;

/**
 * 브로커 토픽 조작 포트 — 프로비저닝 판단 로직을 Kafka {@code AdminClient} 에서 분리한다.
 *
 * <p>의도적으로 <b>생성만</b> 노출한다. 파티션 증설·삭제 메서드가 이 포트에 없는 것이 설계다:
 * 키(aggregateId) 해시로 순서를 보장하는 구조에서 파티션 증설은 재해시를 일으켜 같은 애그리거트의
 * 이벤트를 다른 파티션으로 흩는다. 호출할 수 없으면 실수로 부를 수도 없다.
 */
public interface TopicAdmin {

    /**
     * 주어진 이름 중 <b>실재하는</b> 토픽의 파티션 수를 돌려준다. 없는 토픽은 결과 맵에 담기지 않는다
     * (null 파티션 수라는 애매한 상태를 만들지 않는다).
     */
    Map<String, Integer> describePartitions(Set<String> names);

    /** 토픽을 새로 만든다. 이미 있으면 호출되지 않는다. */
    void create(String name, int partitions, int retentionDays);
}
