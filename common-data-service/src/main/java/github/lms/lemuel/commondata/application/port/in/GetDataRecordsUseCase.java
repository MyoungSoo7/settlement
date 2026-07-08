package github.lms.lemuel.commondata.application.port.in;

import github.lms.lemuel.commondata.domain.DataRecord;

import java.util.List;

public interface GetDataRecordsUseCase {

    /** 소스의 최신 수집 레코드 — collectedAt 내림차순, limit 상한은 서비스가 강제. */
    List<DataRecord> getRecords(String sourceCode, int limit);
}
