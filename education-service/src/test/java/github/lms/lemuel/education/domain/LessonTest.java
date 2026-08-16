package github.lms.lemuel.education.domain;

import github.lms.lemuel.education.domain.exception.LessonOrderViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LessonTest {

    @Test
    void reorderAcceptsEveryLessonExactlyOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(Lesson.validateReorder(List.of(first, second), List.of(second, first))).isTrue();
    }

    @Test
    void reorderRejectsForeignLesson() {
        UUID first = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> Lesson.validateReorder(List.of(first), List.of(first, foreign)))
                .isInstanceOf(LessonOrderViolationException.class);
    }
}
