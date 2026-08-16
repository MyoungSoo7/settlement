package github.lms.lemuel.education.application.service;

import github.lms.lemuel.education.adapter.out.persistence.LessonJpaEntity;
import github.lms.lemuel.education.adapter.out.persistence.LessonRepository;
import github.lms.lemuel.education.domain.Lesson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import github.lms.lemuel.education.domain.LessonContentType;
import github.lms.lemuel.education.application.port.out.EducationAuditPort;

@Service
public class LessonAdminService {
    private final LessonRepository lessons;
    private final EducationAuditPort audit;
    public LessonAdminService(LessonRepository lessons) { this(lessons, (a, t, id, actor, detail) -> { }); }
    @Autowired
    public LessonAdminService(LessonRepository lessons, EducationAuditPort audit) { this.lessons = lessons; this.audit = audit; }

    @Transactional(readOnly = true)
    public List<LessonJpaEntity> list(UUID courseId) { return lessons.findAllByCourseIdOrderBySequence(courseId); }

    @Transactional
    public LessonJpaEntity create(UUID courseId, String title, String description, int sequence, String type, String ref, boolean required, String actor) {
        LessonJpaEntity lesson = lessons.save(LessonJpaEntity.active(UUID.randomUUID(), courseId, title, description, sequence, type, ref, required, actor));
        audit.record("LESSON_CREATED", "Lesson", lesson.getId(), actor, "lesson created"); return lesson;
    }

    @Transactional
    public LessonJpaEntity update(UUID id, String title, String description, String type, String ref, boolean required, String actor) {
        LessonJpaEntity lesson = lessons.findById(id).orElseThrow(() -> new IllegalArgumentException("lesson not found: " + id));
        lesson.update(title, description, type, ref, required, actor); audit.record("LESSON_UPDATED", "Lesson", id, actor, "lesson updated"); return lesson;
    }

    @Transactional
    public void delete(UUID id, String actor) { lessons.deleteById(id); audit.record("LESSON_DELETED", "Lesson", id, actor, "lesson deleted"); }

    @Transactional
    public void reorder(UUID courseId, List<UUID> requestedIds, String actor) {
        List<LessonJpaEntity> current = lessons.findAllByCourseIdOrderBySequence(courseId);
        Lesson.validateReorder(current.stream().map(LessonJpaEntity::getId).toList(), requestedIds);
        var byId = current.stream().collect(java.util.stream.Collectors.toMap(LessonJpaEntity::getId, x -> x));
        for (int i = 0; i < requestedIds.size(); i++) {
            LessonJpaEntity lesson = byId.get(requestedIds.get(i));
            lesson.changeSequence(-(i + 1), actor);
            lessons.save(lesson);
        }
        for (int i = 0; i < requestedIds.size(); i++) byId.get(requestedIds.get(i)).changeSequence(i + 1, actor);
        for (UUID id : requestedIds) lessons.save(byId.get(id));
        audit.record("LESSON_REORDERED", "Course", courseId, actor, "lesson order changed");
    }
}
