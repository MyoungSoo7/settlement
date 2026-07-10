package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — loan 이 발행하는 기업대출 실행 이벤트가 shared-common 계약 스키마를
 * 통과하고, 라우팅에 필요한 aggregateType/eventType 이 정확한지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CorporateLoanEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    CorporateLoanEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new CorporateLoanEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private CorporateLoan disbursedLoan(BigDecimal fee) {
        return CorporateLoan.reconstitute(5001L, "005930", "삼성전자", new BigDecimal("1000000"),
                fee, new BigDecimal("1000000").add(fee), 30, 82, "A",
                CorporateLoanStatus.DISBURSED, null);
    }

    @Test
    @DisplayName("CorporateLoanDisbursed 페이로드는 lemuel.loan.corporate_loan_disbursed 계약을 만족한다")
    void disbursed_satisfiesContract() {
        publisher.publishDisbursed(disbursedLoan(new BigDecimal("660")));

        OutboxEvent event = capture();
        assertThat(event.getAggregateType()).isEqualTo("Loan");
        assertThat(event.getEventType()).isEqualTo("CorporateLoanDisbursed");
        EventContractValidator.assertValid("lemuel.loan.corporate_loan_disbursed", event.getPayload());
    }

    @Test
    @DisplayName("수수료 0 페이로드도 계약을 만족한다 — minimum 0 허용")
    void disbursed_zeroFee_satisfiesContract() {
        publisher.publishDisbursed(disbursedLoan(BigDecimal.ZERO));

        EventContractValidator.assertValid("lemuel.loan.corporate_loan_disbursed", capture().getPayload());
    }

    private OutboxEvent capture() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }
}
