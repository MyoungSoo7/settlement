package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataSource;

public interface SaveDataSourcePort {

    /** code UNIQUE upsert — 저장된(id 채워진) 도메인을 돌려준다. */
    DataSource upsert(DataSource source);
}
