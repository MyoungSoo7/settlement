package github.lms.lemuel.account.application.port.out;

import github.lms.lemuel.account.domain.AccountEntry;

/**
 * GL 분개를 원장에 적재하는 아웃바운드 포트.
 *
 * <p>구현체는 자연키 {@code (source_topic, ref_type, ref_id)} UNIQUE 로 중복 적재를 멱등 차단한다
 * (컨슈머 측 {@code processed_events} 와 이중 방어).
 */
public interface AppendAccountEntryPort {

    void append(AccountEntry entry);
}
