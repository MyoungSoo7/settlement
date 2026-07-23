package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — loan 이 settlement 로 발행하는 상환 차감 이벤트가
 * shared-common 의 계약 스키마를 통과해야 한다. settlement 의 순지급액 계산이 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class LoanEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    LoanEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new LoanEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("LoanRepaymentApplied 페이로드는 lemuel.loan.repayment_applied 계약을 만족한다")
    void repaymentApplied_satisfiesContract() {
        publisher.publishRepaymentApplied(9001L, 777L, new BigDecimal("10000"));

        EventContractValidator.assertValid("lemuel.loan.repayment_applied", savedPayload());
    }

    @Test
    @DisplayName("차감 0(대출 없는 셀러) 페이로드도 계약을 만족한다 — minimum 0 허용")
    void repaymentApplied_zeroDeduction_satisfiesContract() {
        publisher.publishRepaymentApplied(9001L, 777L, BigDecimal.ZERO);

        EventContractValidator.assertValid("lemuel.loan.repayment_applied", savedPayload());
    }

    @Test
    @DisplayName("LoanDisbursementRequested 페이로드는 lemuel.loan.disbursement_requested 계약을 만족한다")
    void disbursementRequested_satisfiesContract() {
        LoanAdvance loan = LoanAdvance.reconstitute(501L, 777L,
                new BigDecimal("800000"), new BigDecimal("8000"),
                new BigDecimal("808000"), LoanStatus.REQUESTED);

        publisher.publishDisbursementRequested(loan);

        EventContractValidator.assertValid("lemuel.loan.disbursement_requested", savedPayload());
    }
}
