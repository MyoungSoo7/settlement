package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerOutboxPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerOutboxAdminServiceTest {

    @Mock LoadLedgerOutboxPort loadPort;
    @Mock SaveLedgerOutboxPort savePort;
    @InjectMocks LedgerOutboxAdminService service;

    @Test
    @DisplayName("listFailed / countFailed 는 로드 포트에 위임한다")
    void listAndCountDelegate() {
        LedgerOutboxTask t = LedgerOutboxTask.create(1L);
        when(loadPort.findFailed(10)).thenReturn(List.of(t));
        when(loadPort.countFailed()).thenReturn(3L);

        assertThat(service.listFailed(10)).containsExactly(t);
        assertThat(service.countFailed()).isEqualTo(3L);
    }

    @Test
    @DisplayName("requeueFailed 는 저장 포트에 위임하고 재큐 건수를 반환한다")
    void requeueDelegates() {
        when(savePort.requeueFailed(100)).thenReturn(4);

        int requeued = service.requeueFailed(100);

        assertThat(requeued).isEqualTo(4);
        verify(savePort).requeueFailed(100);
    }
}
