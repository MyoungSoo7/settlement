package github.lms.lemuel.education.domain;

import github.lms.lemuel.education.domain.exception.LessonOrderViolationException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class Lesson {
    private Lesson() { }

    public static boolean validateReorder(List<UUID> existingIds, List<UUID> requestedIds) {
        if (existingIds.size() != requestedIds.size()
                || new HashSet<>(existingIds).size() != existingIds.size()
                || !new HashSet<>(existingIds).equals(new HashSet<>(requestedIds))) {
            throw new LessonOrderViolationException("lesson order must contain each course lesson exactly once");
        }
        return true;
    }
}
