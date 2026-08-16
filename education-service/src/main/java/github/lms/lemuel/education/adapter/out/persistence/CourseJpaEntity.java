package github.lms.lemuel.education.adapter.out.persistence;

import github.lms.lemuel.education.domain.CourseStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "education_courses", schema = "education")
public class CourseJpaEntity {
    @Id private UUID id;
    private String title;
    private String description;
    @Enumerated(EnumType.STRING) private CourseStatus status;
    private Instant publishedAt;
    private Instant closedAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected CourseJpaEntity() { }

    public CourseJpaEntity(UUID id, String title, String description, String actor) {
        this.id = id; this.title = title; this.description = description;
        this.status = CourseStatus.DRAFT; this.createdBy = actor; this.updatedBy = actor;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public CourseStatus getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getClosedAt() { return closedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void update(String title, String description, String actor) {
        this.title = title; this.description = description; this.updatedBy = actor; this.updatedAt = Instant.now();
    }
    public void publish(String actor) {
        if (status != CourseStatus.DRAFT && status != CourseStatus.HIDDEN) throw new IllegalStateException("course cannot be published from " + status);
        status = CourseStatus.PUBLISHED; publishedAt = Instant.now(); updatedBy = actor; updatedAt = Instant.now();
    }
    public void hide(String actor) {
        if (status != CourseStatus.PUBLISHED) throw new IllegalStateException("course cannot be hidden from " + status);
        status = CourseStatus.HIDDEN; updatedBy = actor; updatedAt = Instant.now();
    }
    public void close(String actor) {
        if (status != CourseStatus.PUBLISHED && status != CourseStatus.HIDDEN) throw new IllegalStateException("course cannot be closed from " + status);
        status = CourseStatus.CLOSED; closedAt = Instant.now(); updatedBy = actor; updatedAt = Instant.now();
    }
}
