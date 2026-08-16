package github.lms.lemuel.education.adapter.in.web;

import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;
import github.lms.lemuel.education.application.service.CourseAdminService;
import github.lms.lemuel.education.application.service.LessonAdminService;
import github.lms.lemuel.education.adapter.out.persistence.LessonJpaEntity;
import github.lms.lemuel.education.domain.CourseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/education/courses")
public class AdminEducationController {
    private final CourseAdminService service;
    private final LessonAdminService lessonService;
    public AdminEducationController(CourseAdminService service, LessonAdminService lessonService) { this.service = service; this.lessonService = lessonService; }

    @GetMapping
    public Page<CourseResponse> list(@RequestParam(required = false) CourseStatus status,
                                     @RequestParam(defaultValue = "") String query, Pageable pageable) {
        return service.list(status, query, pageable).map(CourseResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(@Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(service.create(request.title(), request.description(), auth.getName()));
    }

    @GetMapping("/{id}")
    public CourseResponse get(@PathVariable UUID id) { return CourseResponse.from(service.get(id)); }

    @PutMapping("/{id}")
    public CourseResponse update(@PathVariable UUID id, @Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(service.update(id, request.title(), request.description(), auth.getName()));
    }

    @PostMapping("/{id}/publish")
    public CourseResponse publish(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.PUBLISHED, auth); }
    @PostMapping("/{id}/hide")
    public CourseResponse hide(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.HIDDEN, auth); }
    @PostMapping("/{id}/close")
    public CourseResponse close(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.CLOSED, auth); }

    @GetMapping("/{courseId}/lessons")
    public java.util.List<LessonResponse> lessons(@PathVariable UUID courseId) {
        return lessonService.list(courseId).stream().map(LessonResponse::from).toList();
    }

    @PostMapping("/{courseId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public LessonResponse createLesson(@PathVariable UUID courseId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(lessonService.create(courseId, request.title(), request.description(), request.sequence(), request.contentType(), request.contentRef(), request.required(), auth.getName()));
    }

    @PutMapping("/{courseId}/lessons/{lessonId}")
    public LessonResponse updateLesson(@PathVariable UUID lessonId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(lessonService.update(lessonId, request.title(), request.description(), request.contentType(), request.contentRef(), request.required(), auth.getName()));
    }

    @DeleteMapping("/{courseId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable UUID lessonId, Authentication auth) { lessonService.delete(lessonId, auth.getName()); }

    @PostMapping("/{courseId}/lessons/reorder")
    public java.util.List<LessonResponse> reorder(@PathVariable UUID courseId, @RequestBody ReorderRequest request,
                                                  Authentication auth) {
        lessonService.reorder(courseId, request.lessonIds(), auth.getName());
        return lessons(courseId);
    }

    private CourseResponse transition(UUID id, CourseStatus status, Authentication auth) {
        return CourseResponse.from(service.transition(id, status, auth.getName()));
    }

    public record CourseRequest(@NotBlank String title, String description) { }
    public record CourseResponse(UUID id, String title, String description, CourseStatus status,
                                 String updatedBy, long version) {
        static CourseResponse from(CourseJpaEntity c) {
            return new CourseResponse(c.getId(), c.getTitle(), c.getDescription(), c.getStatus(), c.getUpdatedBy(), c.getVersion());
        }
    }
    public record ReorderRequest(java.util.List<UUID> lessonIds) { }
    public record LessonRequest(@NotBlank String title, String description, int sequence, @NotBlank String contentType,
                                @NotBlank String contentRef, boolean required) { }
    public record LessonResponse(UUID id, UUID courseId, String title, int sequence, String contentType, String contentRef) {
        static LessonResponse from(LessonJpaEntity l) { return new LessonResponse(l.getId(), l.getCourseId(), l.getTitle(), l.getSequence(), l.getContentType().name(), l.getContentRef()); }
    }
}
