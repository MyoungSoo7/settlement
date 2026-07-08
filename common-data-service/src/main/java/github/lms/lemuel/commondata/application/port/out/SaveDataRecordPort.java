package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataRecord;

public interface SaveDataRecordPort {

    /** (sourceCode, recordKey) UNIQUE upsert — 재수집은 payload/collectedAt 갱신. */
    void upsert(DataRecord record);
}
