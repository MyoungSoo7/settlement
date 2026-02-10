package github.lms.lemuel.repository;

import github.lms.lemuel.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    List<Payment> findByStatus(Payment.PaymentStatus status);
    
    @Query("SELECT p FROM Payment p WHERE p.status = 'CAPTURED' AND p.updatedAt >= :startDate AND p.updatedAt < :endDate")
    List<Payment> findCapturedPaymentsBetween(LocalDateTime startDate, LocalDateTime endDate);
}
