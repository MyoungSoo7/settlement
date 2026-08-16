package github.lms.lemuel.education.domain;

import github.lms.lemuel.education.domain.exception.InvalidCourseStateException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseTest {

    @Test
    void draftCanBePublishedAndHiddenThenClosed() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");

        course.publish("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.PUBLISHED);

        course.hide("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.HIDDEN);

        course.close("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.CLOSED);
        assertThat(course.id()).isNotNull();
        assertThat(course.title()).isEqualTo("정산 교육");
        assertThat(course.description()).isEqualTo("설명");
        assertThat(course.publishedAt()).isNotNull();
        assertThat(course.closedAt()).isNotNull();
        assertThat(course.updatedBy()).isEqualTo("admin");
    }

    @Test
    void closedCourseCannotBePublishedAgain() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.publish("admin");
        course.hide("admin");
        course.close("admin");

        assertThatThrownBy(() -> course.publish("admin"))
                .isInstanceOf(InvalidCourseStateException.class);
    }

    @Test
    void invalidHideAndCloseTransitionsAreRejected() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        assertThatThrownBy(() -> course.hide("admin")).isInstanceOf(InvalidCourseStateException.class);
        assertThatThrownBy(() -> course.close("admin")).isInstanceOf(InvalidCourseStateException.class);
        course.publish("admin");
        assertThatThrownBy(() -> course.publish("admin")).isInstanceOf(InvalidCourseStateException.class);
    }
}
