package github.lms.lemuel.education.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;
import github.lms.lemuel.education.application.port.out.PublishEducationEventPort;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class OutboxBackedEducationEventPublisher implements PublishEducationEventPort {
    /** 토픽명은 여기서 파생된다 — lemuel.education.course_published (ADR 0035 카탈로그 등재명). */
    private static final String AGGREGATE_TYPE = "Education";

    private final SaveOutboxEventPort outbox;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TraceContextCapture trace;

    public OutboxBackedEducationEventPublisher(SaveOutboxEventPort outbox, TraceContextCapture trace) {
        this.outbox = outbox; this.trace = trace;
    }

    @Override
    public void coursePublished(CourseJpaEntity course, String actor) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "courseId", course.getId(), "title", course.getTitle(),
                    "publishedAt", course.getPublishedAt(), "publishedBy", actor,
                    "version", course.getVersion()));
            String courseId = course.getId().toString();
            outbox.save(OutboxEvent.pending(AGGREGATE_TYPE, courseId,
                    "CoursePublished", payload, trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize CoursePublished payload", exception);
        }
    }
}
