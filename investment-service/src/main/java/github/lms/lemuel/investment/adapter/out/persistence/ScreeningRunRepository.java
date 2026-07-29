package github.lms.lemuel.investment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface ScreeningRunRepository extends JpaRepository<ScreeningRunJpaEntity, LocalDate> {

    @Query("select max(r.quoteBaseDate) from ScreeningRunJpaEntity r")
    Optional<LocalDate> findLatestQuoteBaseDate();
}
