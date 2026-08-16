package github.lms.lemuel.education.application.service;

import github.lms.lemuel.education.adapter.out.persistence.LessonJpaEntity;
import github.lms.lemuel.education.adapter.out.persistence.LessonRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LessonAdminServiceTest {
    @Test
    void listCreateUpdateAndDeleteDelegateToRepository() {
        UUID courseId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        LessonRepository repository = mock(LessonRepository.class);
        LessonJpaEntity lesson = LessonJpaEntity.active(lessonId, courseId, "차시", 1, "VIDEO", "v1", "admin");
        when(repository.findAllByCourseIdOrderBySequence(courseId)).thenReturn(List.of(lesson));
        when(repository.findById(lessonId)).thenReturn(java.util.Optional.of(lesson));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LessonAdminService service = new LessonAdminService(repository);

        assertThat(service.list(courseId)).containsExactly(lesson);
        assertThat(service.create(courseId, "새 차시", "설명", 2, "DOCUMENT", "d1", true, "admin")).isNotNull();
        assertThat(service.update(lessonId, "수정 차시", "설명", "EXTERNAL_LINK", "x1", false, "admin")).isSameAs(lesson);
        service.delete(lessonId, "admin");

        verify(repository).deleteById(lessonId);
    }

    @Test
    void reorderUpdatesEveryLessonSequenceInRequestedOrder() {
        UUID courseId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LessonRepository repository = mock(LessonRepository.class);
        when(repository.findAllByCourseIdOrderBySequence(courseId)).thenReturn(List.of(
                LessonJpaEntity.active(first, courseId, "첫 차시", 1, "VIDEO", "v1", "admin"),
                LessonJpaEntity.active(second, courseId, "둘째 차시", 2, "VIDEO", "v2", "admin")));
        LessonAdminService service = new LessonAdminService(repository);

        service.reorder(courseId, List.of(second, first), "admin");

        ArgumentCaptor<LessonJpaEntity> captor = ArgumentCaptor.forClass(LessonJpaEntity.class);
        verify(repository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues().get(2)).extracting(LessonJpaEntity::getSequence).isEqualTo(1);
        assertThat(captor.getAllValues().get(3)).extracting(LessonJpaEntity::getSequence).isEqualTo(2);
        assertThat(captor.getAllValues().get(2)).extracting(LessonJpaEntity::getId).isEqualTo(second);
        assertThat(captor.getAllValues().get(3)).extracting(LessonJpaEntity::getId).isEqualTo(first);
    }
}
