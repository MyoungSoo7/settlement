package github.lms.lemuel.commondata.application.port.in;

import github.lms.lemuel.commondata.domain.DataSource;

import java.util.List;

public interface GetDataSourcesUseCase {

    List<DataSource> getSources();

    DataSource getSource(String code);
}
