package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.Settlement;

import java.time.LocalDate;
import java.util.List;

public interface LoadReleasableHoldbackPort {
    /**
     * release_date 가 today 이전 (포함) 이고 아직 released=false 이며 holdback_amount > 0 인 settlement 목록.
     */
    List<Settlement> findReleasableOn(LocalDate today, int limit);
}
