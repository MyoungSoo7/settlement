package github.lms.lemuel.reservation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataReservationProcessedEventRepository
        extends JpaRepository<ReservationProcessedEventJpaEntity, ReservationProcessedEventJpaEntity.Pk> {
}
