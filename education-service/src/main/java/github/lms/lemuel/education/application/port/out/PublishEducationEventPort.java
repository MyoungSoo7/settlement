package github.lms.lemuel.education.application.port.out;

import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;

public interface PublishEducationEventPort {
    void coursePublished(CourseJpaEntity course, String actor);
}
