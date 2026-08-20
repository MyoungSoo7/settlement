package github.lms.lemuel.education.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface EducationAuditPort {
    void record(String action, String resourceType, UUID resourceId, String actor, String detail);
}
