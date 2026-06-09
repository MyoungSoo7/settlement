package github.lms.lemuel.reservation.adapter.out.persistence;

import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ReservationPersistenceAdapter implements SaveReservationPort, LoadReservationPort {

    private final SpringDataReservationJpaRepository repository;
    private final ReservationPersistenceMapper mapper;

    @Override
    public Reservation save(Reservation reservation) {
        ReservationJpaEntity entity = mapper.toEntity(reservation);
        ReservationJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Reservation> findById(Long reservationId) {
        return repository.findById(reservationId).map(mapper::toDomain);
    }

    @Override
    public List<Reservation> findByCompanyId(Long companyId) {
        return repository.findByCompanyIdOrderByScheduledDateDesc(companyId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }
}
