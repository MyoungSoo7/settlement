package github.lms.lemuel.reservation.adapter.out.technician;

import github.lms.lemuel.reservation.adapter.out.persistence.SpringDataTechnicianViewRepository;
import github.lms.lemuel.reservation.application.port.out.ReservationTechnicianPort;
import org.springframework.stereotype.Component;

/**
 * Phase B 기사 검증 어댑터 — 로컬 기사 프로젝션(technician_view)만 조회한다.
 * user-service / user DB 에 대한 런타임 결합 0 (Event-Carried State Transfer).
 */
@Component
public class ProjectionTechnicianAdapter implements ReservationTechnicianPort {

    private static final String ROLE_TECHNICIAN = "TECHNICIAN";
    private static final String STATUS_APPROVED = "APPROVED";

    private final SpringDataTechnicianViewRepository repository;

    public ProjectionTechnicianAdapter(SpringDataTechnicianViewRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isAssignableTechnician(Long technicianId) {
        return repository.findById(technicianId)
                .map(v -> ROLE_TECHNICIAN.equals(v.getRole())
                        && STATUS_APPROVED.equals(v.getMembershipStatus())
                        && v.isActive())
                .orElse(false);
    }
}
