package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.BackfillTechnicianProjectionUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기사 프로젝션 백필 — 기존 TECHNICIAN 전원의 멤버십 이벤트를 outbox 로 재발행한다.
 *
 * <p>발행은 outbox(Transactional Outbox)에 적재되고 OutboxPublisherScheduler 가 Kafka 로 보낸다.
 * reservation-service 의 UserMembershipEventConsumer 가 (consumer_group,event_id) 멱등으로 처리하므로
 * 본 백필을 여러 번 실행해도 안전하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicianProjectionBackfillService implements BackfillTechnicianProjectionUseCase {

    private final LoadUserPort loadUserPort;
    private final PublishUserEventPort publishUserEventPort;

    @Override
    @Transactional
    public int backfillTechnicians() {
        List<User> technicians = loadUserPort.findAll().stream()
                .filter(u -> u.getRole() == UserRole.TECHNICIAN)
                .toList();

        for (User u : technicians) {
            publishUserEventPort.publishMembershipChanged(
                    u.getId(),
                    u.getRole().name(),
                    u.getMembershipStatus().name(),
                    u.isActive());
        }

        log.info("기사 프로젝션 백필 발행 완료: {}건", technicians.size());
        return technicians.size();
    }
}
