package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이벤트 직렬화 실패(toJson 예외 분기) 가드 — ObjectMapper 가 JsonProcessingException 을 던지면
 * 각 퍼블리셔는 IllegalStateException 으로 래핑하고 Outbox 에 저장하지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherSerializationFailureTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock ObjectMapper objectMapper;

    private static JsonProcessingException boom() {
        return new JsonProcessingException("직렬화 실패") { };
    }

    @Test
    @DisplayName("LoanEventPublisherAdapter: 직렬화 실패 시 IllegalStateException, 저장 안 함")
    void loanPublisher_serializationFailure() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenThrow(boom());
        LoanEventPublisherAdapter publisher = new LoanEventPublisherAdapter(saveOutboxEventPort, objectMapper);

        assertThatThrownBy(() -> publisher.publishRepaymentApplied(9001L, 777L, new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class);

        verify(saveOutboxEventPort, never()).save(any());
    }

    @Test
    @DisplayName("CorporateLoanEventPublisherAdapter: 직렬화 실패 시 IllegalStateException, 저장 안 함")
    void corporatePublisher_serializationFailure() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenThrow(boom());
        CorporateLoanEventPublisherAdapter publisher =
                new CorporateLoanEventPublisherAdapter(saveOutboxEventPort, objectMapper);
        CorporateLoan loan = CorporateLoan.reconstitute(7L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now());

        assertThatThrownBy(() -> publisher.publishDisbursed(loan))
                .isInstanceOf(IllegalStateException.class);

        verify(saveOutboxEventPort, never()).save(any());
    }
}
