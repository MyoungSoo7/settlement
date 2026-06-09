package github.lms.lemuel.reservation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataReservationJpaRepository extends JpaRepository<ReservationJpaEntity, Long> {

    List<ReservationJpaEntity> findByCompanyIdOrderByScheduledDateDesc(Long companyId);

    List<ReservationJpaEntity> findByTechnicianIdOrderByScheduledDateAsc(Long technicianId);
}
