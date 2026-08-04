package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.StatementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** card_statements 테이블 Spring Data JPA 리포지터리. */
public interface SpringDataCardStatementRepository extends JpaRepository<CardStatementJpaEntity, Long> {

    Optional<CardStatementJpaEntity> findByCardAccountIdAndBillingYearMonth(
            Long cardAccountId, String billingYearMonth);

    List<CardStatementJpaEntity> findByBillingYearMonthAndStatus(
            String billingYearMonth, StatementStatus status);

    /**
     * 만기 경과 + 미납 명세서 목록 — 연체 배치 전용.
     * status 가 CLOSED 또는 PARTIALLY_PAID 이고 dueDate 가 today 이전인 행.
     */
    @Query("SELECT s FROM CardStatementJpaEntity s " +
           "WHERE s.status IN ('CLOSED', 'PARTIALLY_PAID') " +
           "AND s.dueDate IS NOT NULL AND s.dueDate < :today")
    List<CardStatementJpaEntity> findOverdueAndUnpaid(LocalDate today);
}
