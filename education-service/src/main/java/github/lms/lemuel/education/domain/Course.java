package github.lms.lemuel.education.domain;

import github.lms.lemuel.education.domain.exception.InvalidCourseStateException;

import java.time.Instant;
import java.util.UUID;

public final class Course {
    private final UUID id;
    private String title;
    private String description;
    private CourseStatus status;
    private Instant publishedAt;
    private Instant closedAt;
    private String updatedBy;

    private Course(UUID id, String title, String description, String updatedBy) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = CourseStatus.DRAFT;
        this.updatedBy = updatedBy;
    }

    public static Course draft(UUID id, String title, String description, String actor) {
        return new Course(id, title, description, actor);
    }

    public void publish(String actor) {
        require(CourseStatus.DRAFT, CourseStatus.HIDDEN);
        status = CourseStatus.PUBLISHED;
        publishedAt = Instant.now();
        updatedBy = actor;
    }

    public void hide(String actor) {
        require(CourseStatus.PUBLISHED);
        status = CourseStatus.HIDDEN;
        updatedBy = actor;
    }

    public void close(String actor) {
        require(CourseStatus.PUBLISHED, CourseStatus.HIDDEN);
        status = CourseStatus.CLOSED;
        closedAt = Instant.now();
        updatedBy = actor;
    }

    private void require(CourseStatus... allowed) {
        for (CourseStatus candidate : allowed) if (status == candidate) return;
        throw new InvalidCourseStateException("course cannot transition from " + status);
    }

    public UUID id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public CourseStatus status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public Instant closedAt() { return closedAt; }
    public String updatedBy() { return updatedBy; }
}
