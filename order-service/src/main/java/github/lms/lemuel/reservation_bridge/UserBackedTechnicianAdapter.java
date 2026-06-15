package github.lms.lemuel.reservation_bridge;

import github.lms.lemuel.reservation.application.port.out.ReservationTechnicianPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.UserRole;
import org.springframework.stereotype.Component;

/**
 * Phase A 전용 in-process 어댑터.
 *
 * <p>reservation 모듈은 user 를 import 하지 않지만(코드 경계 0), 단일 배포(Phase A)에서는
 * 같은 JVM 의 {@link LoadUserPort} 로 기사 자격을 검증한다. 이 브리지는 order-service 조립
 * 루트에만 존재하며 reservation 모듈 밖이므로 user 접근이 합법이다.
 *
 * <p>Phase B(독립 배포 + DB 분리)에서 Kafka 이벤트로 동기화되는 로컬 기사 프로젝션 어댑터로
 * 교체·삭제한다.
 */
@Component
public class UserBackedTechnicianAdapter implements ReservationTechnicianPort {

    private final LoadUserPort loadUserPort;

    public UserBackedTechnicianAdapter(LoadUserPort loadUserPort) {
        this.loadUserPort = loadUserPort;
    }

    @Override
    public boolean isAssignableTechnician(Long technicianId) {
        return loadUserPort.findById(technicianId)
                .map(u -> u.getRole() == UserRole.TECHNICIAN && u.canUseService())
                .orElse(false);
    }
}
