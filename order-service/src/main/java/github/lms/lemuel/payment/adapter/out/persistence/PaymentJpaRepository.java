package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for PaymentJpaEntity
 * PaymentRepository와 통합됨
 */
@Repository
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {

    Optional<PaymentJpaEntity> findByOrderId(Long orderId);

    List<PaymentJpaEntity> findByStatus(String status);

    /**
     * 특정 기간 동안 캡처된 결제 조회 (정산 생성용)
     * @param startDateTime 시작 시간
     * @param endDateTime 종료 시간
     * @param status 결제 상태
     * @return 캡처된 결제 목록
     */
    List<PaymentJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime startDateTime,
            LocalDateTime endDateTime,
            String status);

    /**
     * JPQL 쿼리로 캡처된 결제 조회
     * @param startDate 시작 시간
     * @param endDate 종료 시간
     * @return 캡처된 결제 목록
     */
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.status = 'CAPTURED' AND p.updatedAt >= :startDate AND p.updatedAt < :endDate")
    List<PaymentJpaEntity> findCapturedPaymentsBetween(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Spring Batch용 페이징 쿼리
     * @param startDate 시작 시간
     * @param endDate 종료 시간
     * @param status 상태
     * @param pageable 페이징 정보
     * @return 페이징된 결제 목록
     */
    Page<PaymentJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime startDate,
            LocalDateTime endDate,
            String status,
            Pageable pageable
    );
}
