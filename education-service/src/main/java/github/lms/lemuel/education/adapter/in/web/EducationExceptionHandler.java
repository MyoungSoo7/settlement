package github.lms.lemuel.education.adapter.in.web;

import github.lms.lemuel.education.application.service.CourseAdminService.CourseNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class EducationExceptionHandler {
    @ExceptionHandler(CourseNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(CourseNotFoundException exception) { return Map.of("code", "COURSE_NOT_FOUND", "message", exception.getMessage()); }
}
