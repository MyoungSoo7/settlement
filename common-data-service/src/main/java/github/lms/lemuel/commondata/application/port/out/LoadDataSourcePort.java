package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataSource;

import java.util.List;
import java.util.Optional;

public interface LoadDataSourcePort {

    List<DataSource> findAll();

    Optional<DataSource> findByCode(String code);
}
