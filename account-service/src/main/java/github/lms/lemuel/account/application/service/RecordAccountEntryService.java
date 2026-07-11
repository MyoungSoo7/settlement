package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트에서 파생된 GL 분개를 원장에 적재한다.
 *
 * <p>자연키 {@code (source_topic, ref_type, ref_id)} UNIQUE 로 중복 수신이 스키마 수준에서 멱등 처리되며,
 * 컨슈머 측 {@code processed_events} 멱등과 함께 2단 방어를 이룬다.
 */
@Service
public class RecordAccountEntryService implements RecordAccountEntryUseCase {

    private final AppendAccountEntryPort appendAccountEntryPort;

    public RecordAccountEntryService(AppendAccountEntryPort appendAccountEntryPort) {
        this.appendAccountEntryPort = appendAccountEntryPort;
    }

    @Override
    @Transactional
    public void record(AccountEntry entry) {
        appendAccountEntryPort.append(entry);
    }
}
