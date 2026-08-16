package github.lms.lemuel.education.application.service;

import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;
import github.lms.lemuel.education.adapter.out.persistence.CourseRepository;
import github.lms.lemuel.education.application.port.out.PublishEducationEventPort;
import github.lms.lemuel.education.domain.CourseStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;

class CoursePublicationEventTest {
    @Test
    void listSupportsStatusAndTitleFilters() {
        CourseRepository repository = mock(CourseRepository.class);
        PublishEducationEventPort events = mock(PublishEducationEventPort.class);
        when(repository.findByTitleContainingIgnoreCase("", PageRequest.of(0, 20))).thenReturn(new PageImpl<>(java.util.List.of()));
        when(repository.findByStatusAndTitleContainingIgnoreCase(CourseStatus.DRAFT, "정산", PageRequest.of(0, 20))).thenReturn(new PageImpl<>(java.util.List.of()));
        CourseAdminService service = new CourseAdminService(repository, events);

        service.list(null, null, PageRequest.of(0, 20));
        service.list(CourseStatus.DRAFT, "정산", PageRequest.of(0, 20));

        verify(repository).findByTitleContainingIgnoreCase("", PageRequest.of(0, 20));
        verify(repository).findByStatusAndTitleContainingIgnoreCase(CourseStatus.DRAFT, "정산", PageRequest.of(0, 20));
    }

    @Test
    void createUpdateHideAndCloseArePersisted() {
        CourseRepository repository = mock(CourseRepository.class);
        PublishEducationEventPort events = mock(PublishEducationEventPort.class);
        CourseJpaEntity course = new CourseJpaEntity(UUID.randomUUID(), "교육", "설명", "admin");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findById(course.getId())).thenReturn(java.util.Optional.of(course));
        CourseAdminService service = new CourseAdminService(repository, events);

        service.create("새 교육", "설명", "admin");
        service.update(course.getId(), "수정 교육", "수정 설명", "admin");
        service.transition(course.getId(), CourseStatus.PUBLISHED, "admin");
        service.transition(course.getId(), CourseStatus.HIDDEN, "admin");
        service.transition(course.getId(), CourseStatus.CLOSED, "admin");

        assertThat(course.getTitle()).isEqualTo("수정 교육");
        verify(repository, atLeastOnce()).save(any());
        verify(events).coursePublished(course, "admin");
    }

    @Test
    void missingCourseIsReported() {
        CourseRepository repository = mock(CourseRepository.class);
        when(repository.findById(any())).thenReturn(java.util.Optional.empty());
        CourseAdminService service = new CourseAdminService(repository, mock(PublishEducationEventPort.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(CourseAdminService.CourseNotFoundException.class);
    }

    @Test
    void publishingCourseWritesCoursePublishedEvent() {
        CourseRepository repository = mock(CourseRepository.class);
        PublishEducationEventPort events = mock(PublishEducationEventPort.class);
        CourseJpaEntity course = new CourseJpaEntity(UUID.randomUUID(), "교육", "설명", "admin");
        when(repository.findById(course.getId())).thenReturn(java.util.Optional.of(course));
        CourseAdminService service = new CourseAdminService(repository, events);

        service.transition(course.getId(), CourseStatus.PUBLISHED, "admin");

        verify(events).coursePublished(course, "admin");
    }
}
