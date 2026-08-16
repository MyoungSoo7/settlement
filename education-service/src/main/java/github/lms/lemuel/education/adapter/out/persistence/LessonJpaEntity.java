package github.lms.lemuel.education.adapter.out.persistence;

import github.lms.lemuel.education.domain.LessonContentType;
import github.lms.lemuel.education.domain.LessonStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "education_lessons", schema = "education")
public class LessonJpaEntity {
    @Id private UUID id;
    private UUID courseId;
    private String title;
    private String description;
    private int sequence;
    @Enumerated(EnumType.STRING) private LessonContentType contentType;
    private String contentRef;
    private boolean required;
    @Enumerated(EnumType.STRING) private LessonStatus status;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected LessonJpaEntity() { }
    public static LessonJpaEntity active(UUID id, UUID courseId, String title, int sequence, String type, String ref, String actor) {
        return active(id, courseId, title, null, sequence, type, ref, true, actor);
    }
    public static LessonJpaEntity active(UUID id, UUID courseId, String title, String description, int sequence, String type, String ref, boolean required, String actor) {
        LessonJpaEntity entity = new LessonJpaEntity();
        entity.id = id; entity.courseId = courseId; entity.title = title; entity.description = description; entity.sequence = sequence;
        entity.contentType = LessonContentType.valueOf(type); entity.contentRef = ref; entity.required = required;
        entity.status = LessonStatus.ACTIVE; entity.createdBy = actor; entity.updatedBy = actor;
        entity.createdAt = Instant.now(); entity.updatedAt = entity.createdAt;
        return entity;
    }
    public void changeSequence(int sequence, String actor) { this.sequence = sequence; this.updatedBy = actor; this.updatedAt = Instant.now(); }
    public void update(String title, String description, String type, String ref, boolean required, String actor) {
        this.title = title; this.description = description; this.contentType = LessonContentType.valueOf(type);
        this.contentRef = ref; this.required = required; this.updatedBy = actor; this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public String getTitle() { return title; }
    public int getSequence() { return sequence; }
    public LessonContentType getContentType() { return contentType; }
    public String getContentRef() { return contentRef; }
    public boolean isRequired() { return required; }
    public LessonStatus getStatus() { return status; }
    public String getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }
}
