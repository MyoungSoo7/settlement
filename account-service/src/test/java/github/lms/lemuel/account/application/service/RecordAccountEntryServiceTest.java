package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecordAccountEntryServiceTest {

    @Mock AppendAccountEntryPort appendAccountEntryPort;
    @InjectMocks RecordAccountEntryService service;

    @Test
    void 분개를_그대로_원장에_적재한다() {
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));
        service.record(entry);
        verify(appendAccountEntryPort).append(entry);
    }
}
