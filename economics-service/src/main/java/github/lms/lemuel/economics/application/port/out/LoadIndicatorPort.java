package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.Indicator;

import java.util.List;
import java.util.Optional;

public interface LoadIndicatorPort {

    List<Indicator> findAll();

    Optional<Indicator> findByCode(String code);
}
