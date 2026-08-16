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
            outbox.save(OutboxEvent.pending("EducationCourse", course.getId().toString(),
                    "CoursePublished", payload, trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize CoursePublished payload", exception);
        }
    }
}
