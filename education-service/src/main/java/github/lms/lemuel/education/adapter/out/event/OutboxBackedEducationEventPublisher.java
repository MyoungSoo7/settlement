package github.lms.lemuel.education.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.education.application.port.out.PublishEducationEventPort;
import github.lms.lemuel.education.domain.Course;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OutboxBackedEducationEventPublisher implements PublishEducationEventPort {
    /** 토픽명은 여기서 파생된다 — lemuel.education.course_published (ADR 0035 카탈로그 등재명). */
    private static final String AGGREGATE_TYPE = "Education";

    private final SaveOutboxEventPort outbox;
    private final ObjectMapper mapper;
    private final TraceContextCapture trace;

    /**
     * 매퍼는 반드시 {@code outboxObjectMapper} 를 주입받는다.
     *
     * <p>여기서 {@code new ObjectMapper()} 를 쓰면 {@code publishedAt}(Instant) 직렬화가
     * {@code InvalidDefinitionException} 으로 터져 과정 공개가 통째로 실패한다 — 실제로 그 상태였고,
     * 이 어댑터에 테스트가 없어서 드러나지 않았다. 공용 매퍼에는 JavaTimeModule(ISO-8601) 과
     * 금액 plain string 직렬화가 들어 있다.
     */
    public OutboxBackedEducationEventPublisher(SaveOutboxEventPort outbox,
                                               @Qualifier("outboxObjectMapper") ObjectMapper mapper,
                                               TraceContextCapture trace) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.trace = trace;
    }

    @Override
    public void coursePublished(Course course, String actor) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("courseId", course.id());
            payload.put("title", course.title());
            payload.put("publishedAt", course.publishedAt());
            payload.put("publishedBy", actor);
            payload.put("version", course.version());
            outbox.save(OutboxEvent.pending(AGGREGATE_TYPE, course.id().toString(),
                    "CoursePublished", mapper.writeValueAsString(payload), trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize CoursePublished payload", exception);
        }
    }
}
