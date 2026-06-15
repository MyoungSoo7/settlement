package github.lms.lemuel.reservation.integration;

import github.lms.lemuel.reservation.adapter.out.persistence.SpringDataTechnicianViewRepository;
import github.lms.lemuel.reservation.adapter.out.persistence.TechnicianViewJpaEntity;
import github.lms.lemuel.reservation.adapter.out.technician.ProjectionTechnicianAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reservation-service 가 자체 DB(Testcontainers PostgreSQL)로 <b>독립 부팅</b>하고
 * Flyway 마이그레이션(V1~V4, 교차 FK 없음)이 적용되며, 기사 프로젝션이 동작함을 검증한다.
 *
 * <p>Kafka 는 비활성(app.kafka.enabled=false) — 컨슈머/토픽 없이 부트 + DB 검증에 집중.
 * order-service 가 테스트 클래스패스에 없어 MSA 코드 경계가 유지된 채 부팅됨을 증명한다.
 */
@SpringBootTest
@Testcontainers
class ReservationServiceBootIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("reservations_db");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.kafka.enabled", () -> "false");
        // JwtProperties(app.jwt.secret) — 32 bytes 이상 필요
        registry.add("app.jwt.secret", () -> "test-secret-key-0123456789abcdef-xyz");
        registry.add("app.jwt.issuer", () -> "lemuel-test");
        registry.add("app.jwt.ttl-seconds", () -> "3600");
    }

    @Autowired
    SpringDataTechnicianViewRepository technicianViewRepository;

    @Autowired
    ProjectionTechnicianAdapter projectionTechnicianAdapter;

    @Test
    void boots_andMigratesOwnSchema_andProjectionWorks() {
        // Flyway 가 reservation.technician_view 를 만들었으면 저장/조회가 동작
        TechnicianViewJpaEntity tech = new TechnicianViewJpaEntity();
        tech.setUserId(100L);
        tech.setRole("TECHNICIAN");
        tech.setMembershipStatus("APPROVED");
        tech.setActive(true);
        tech.setUpdatedAt(LocalDateTime.now());
        technicianViewRepository.save(tech);

        // 승인된 기사 → 배정 가능
        assertThat(projectionTechnicianAdapter.isAssignableTechnician(100L)).isTrue();
        // 미존재 → 불가 (교차 user DB 조회 없이 로컬 프로젝션만으로 판단)
        assertThat(projectionTechnicianAdapter.isAssignableTechnician(999L)).isFalse();

        // 정지된 기사 → 불가
        TechnicianViewJpaEntity suspended = new TechnicianViewJpaEntity();
        suspended.setUserId(101L);
        suspended.setRole("TECHNICIAN");
        suspended.setMembershipStatus("SUSPENDED");
        suspended.setActive(true);
        suspended.setUpdatedAt(LocalDateTime.now());
        technicianViewRepository.save(suspended);
        assertThat(projectionTechnicianAdapter.isAssignableTechnician(101L)).isFalse();
    }
}
