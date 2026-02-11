package github.lms.lemuel.repository;

import github.lms.lemuel.domain.SettlementScheduleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementScheduleConfigRepository extends JpaRepository<SettlementScheduleConfig, Long> {

    Optional<SettlementScheduleConfig> findByConfigKey(String configKey);

    List<SettlementScheduleConfig> findByEnabled(Boolean enabled);

    Optional<SettlementScheduleConfig> findByConfigKeyAndMerchantId(String configKey, Long merchantId);
}
