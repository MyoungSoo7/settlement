package github.lms.lemuel.education.application.service;

import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;
import github.lms.lemuel.education.adapter.out.persistence.CourseRepository;
import github.lms.lemuel.education.domain.CourseStatus;
import github.lms.lemuel.education.application.port.out.PublishEducationEventPort;
import github.lms.lemuel.education.application.port.out.EducationAuditPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class CourseAdminService {
    private final CourseRepository courses;
    private final PublishEducationEventPort events;
    private final EducationAuditPort audit;
    public CourseAdminService(CourseRepository courses, PublishEducationEventPort events) { this(courses, events, (a, t, id, actor, detail) -> { }); }
    @Autowired
    public CourseAdminService(CourseRepository courses, PublishEducationEventPort events, EducationAuditPort audit) { this.courses = courses; this.events = events; this.audit = audit; }

    @Transactional(readOnly = true)
    public Page<CourseJpaEntity> list(CourseStatus status, String query, Pageable pageable) {
        String normalized = query == null ? "" : query;
        return status == null ? courses.findByTitleContainingIgnoreCase(normalized, pageable)
                : courses.findByStatusAndTitleContainingIgnoreCase(status, normalized, pageable);
    }

    @Transactional
    public CourseJpaEntity create(String title, String description, String actor) {
        CourseJpaEntity course = courses.save(new CourseJpaEntity(UUID.randomUUID(), title, description, actor));
        audit.record("COURSE_CREATED", "Course", course.getId(), actor, "course created");
        return course;
    }

    @Transactional(readOnly = true)
    public CourseJpaEntity get(UUID id) { return findOrThrow(id); }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private CourseJpaEntity findOrThrow(UUID id) { return courses.findById(id).orElseThrow(() -> new CourseNotFoundException(id)); }

    @Transactional
    public CourseJpaEntity update(UUID id, String title, String description, String actor) {
        CourseJpaEntity course = findOrThrow(id); course.update(title, description, actor); audit.record("COURSE_UPDATED", "Course", id, actor, "course updated"); return course;
    }

    @Transactional
    public CourseJpaEntity transition(UUID id, CourseStatus target, String actor) {
        CourseJpaEntity course = findOrThrow(id);
        switch (target) {
            case PUBLISHED -> course.publish(actor);
            case HIDDEN -> course.hide(actor);
            case CLOSED -> course.close(actor);
            default -> throw new IllegalArgumentException("unsupported course transition");
        }
        if (target == CourseStatus.PUBLISHED) events.coursePublished(course, actor);
        audit.record("COURSE_" + target.name(), "Course", id, actor, "course state transition");
        return course;
    }

    public static class CourseNotFoundException extends RuntimeException {
        public CourseNotFoundException(UUID id) { super("course not found: " + id); }
    }
}
