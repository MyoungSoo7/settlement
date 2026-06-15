package github.lms.lemuel.reservation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataReservationJpaRepository extends JpaRepository<ReservationJpaEntity, Long> {

    List<ReservationJpaEntity> findByCompanyIdOrderByScheduledDateDesc(Long companyId);

    List<ReservationJpaEntity> findByTechnicianIdOrderByScheduledDateAsc(Long technicianId);

    /**
     * 관리자 대시보드 조회 — 시공일자/상태 선택 필터.
     * (scheduled_date, status) 인덱스를 타도록 일자 우선 정렬.
     */
    @Query("""
            SELECT r FROM ReservationJpaEntity r
            WHERE (:scheduledDate IS NULL OR r.scheduledDate = :scheduledDate)
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.scheduledDate ASC, r.status ASC
            """)
    List<ReservationJpaEntity> searchForDashboard(@Param("scheduledDate") LocalDate scheduledDate,
                                                  @Param("status") String status);
}
