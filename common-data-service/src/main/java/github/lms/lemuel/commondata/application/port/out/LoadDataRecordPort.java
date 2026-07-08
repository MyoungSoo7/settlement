package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataRecord;

import java.util.List;

public interface LoadDataRecordPort {

    List<DataRecord> findLatest(String sourceCode, int limit);
}
