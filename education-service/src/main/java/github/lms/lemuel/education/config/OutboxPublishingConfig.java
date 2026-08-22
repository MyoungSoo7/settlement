package github.lms.lemuel.education.config;

import github.lms.lemuel.common.outbox.adapter.out.event.ApplicationEventOutboxPublisher;
import github.lms.lemuel.common.outbox.adapter.out.event.KafkaDlqPublisher;
import github.lms.lemuel.common.outbox.adapter.out.event.KafkaOutboxPublisher;
import github.lms.lemuel.common.outbox.adapter.out.event.NoOpDlqPublisher;
import github.lms.lemuel.common.outbox.application.service.OutboxBatchEventPublisher;
import github.lms.lemuel.common.outbox.application.service.OutboxPollingTrigger;
import github.lms.lemuel.common.outbox.application.service.OutboxPublisherScheduler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Outbox → Kafka 발행 배선.
 *
 * <p>★ 왜 이 설정이 따로 필요한가 — {@code EducationServiceApplication} 의 스캔 범위가
 * {@code github.lms.lemuel.education} 으로 한정돼 있어 shared-common 의 발행 머시너리
 * ({@code @Component} 들)가 자동으로 붙지 않는다. 전체 스캔인 서비스들(order·settlement·loan 등)은
 * 우연히 무사했고, 제한 스캔인 education 만 조용히 빠져 있었다 — 발행 어댑터는 정상 동작해
 * {@code education.outbox_events} 에 PENDING 행을 넣지만 그 행을 집어 갈 주체가 없었다.
 * 컴파일도 테스트도 API 응답도 정상이라 증상이 어디에도 나타나지 않는다.
 *
 * <p><b>소비 측은 의도적으로 들이지 않는다.</b> education 은 소비 0 이고 스키마에
 * {@code processed_events} 테이블이 없다 — {@code common.outbox} 패키지를 통째로
 * {@code @ComponentScan} 하면 컨슈머 측 배선까지 딸려 온다. 그래서 발행에 필요한 것만 명시한다.
 *
 * <p>구현 선택은 {@code app.kafka.enabled} 가 가른다({@code @ConditionalOnProperty}):
 * <ul>
 *   <li>{@code true} — {@link KafkaOutboxPublisher} + {@link KafkaDlqPublisher}</li>
 *   <li>{@code false}(기본) — {@link ApplicationEventOutboxPublisher} + {@link NoOpDlqPublisher}.
 *       로컬은 브로커 없이도 부팅한다.</li>
 * </ul>
 * 둘 다 {@code @Import} 하는 이유는 조건이 <b>런타임에</b> 하나만 남기기 때문이다.
 *
 * <p>주기 실행은 {@link OutboxPollingTrigger} 의 {@code @Scheduled} 가 건다 — 그래서
 * {@code EducationServiceApplication} 에 {@code @EnableScheduling} 이 필요하다. 빈만 들이고
 * 스케줄링을 켜지 않으면 폴러가 등록만 된 채 영영 돌지 않는다.
 */
@Configuration
@Import({
        OutboxPublisherScheduler.class,
        OutboxBatchEventPublisher.class,
        OutboxPollingTrigger.class,
        KafkaOutboxPublisher.class,
        ApplicationEventOutboxPublisher.class,
        KafkaDlqPublisher.class,
        NoOpDlqPublisher.class,
})
public class OutboxPublishingConfig {
}
