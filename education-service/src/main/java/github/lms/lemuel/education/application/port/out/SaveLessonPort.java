package github.lms.lemuel.education.application.port.out;

import github.lms.lemuel.education.domain.Lesson;

/** 차시 저장 포트. */
@FunctionalInterface
public interface SaveLessonPort {
    Lesson save(Lesson lesson);
}
