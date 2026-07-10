package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.AccountEntry;

/**
 * 이벤트에서 파생된 GL 분개를 원장에 기록하는 인바운드 포트 (Kafka 컨슈머가 호출).
 */
public interface RecordAccountEntryUseCase {

    void record(AccountEntry entry);
}
