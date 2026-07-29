package github.lms.lemuel.loan.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — loan 이 발행하는 담보/개인신용 대출 이벤트 2종이 shared-common
 * 계약 스키마를 통과하고, 라우팅에 필요한 aggregateType/eventType 이 정확한지 검증한다.
 *
 * <p>Phase 1 에는 컨슈머가 없어 컨슈머 계약 테스트는 없다. 그래도 프로듀서 쪽 계약을 지금 못 박아 두면,
 * Phase 2 에서 account-service 가 소비를 시작할 때 프로듀서를 고치지 않아도 된다.
 */
@ExtendWith(MockitoExtension.class)
class SecuredLoanEventContractTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 10, 0);

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    SecuredLoanEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new SecuredLoanEventPublisherAdapter(saveOutboxEventPort, OutboxJson.mapper());
    }

    private SecuredLoan mortgage(SecuredLoanStatus status, BigDecimal outstanding) {
        Collateral collateral = Collateral.reconstitute(3001L, CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000.00"), NOW, CollateralStatus.ACTIVE);
        return SecuredLoan.reconstitute(7001L, Borrower.individual(42L, "홍길동"),
                LoanProductType.MORTGAGE, collateral, new BigDecimal("300000000.00"), 360,
                new BigDecimal("4.30"), RepaymentMethod.EQUAL_PAYMENT, null, null,
                outstanding, status, NOW);
    }

    private SecuredLoan personalCredit(SecuredLoanStatus status, BigDecimal outstanding) {
        return SecuredLoan.reconstitute(7002L, Borrower.corporate(7L, "레무엘커머스", "1234567890"),
                LoanProductType.PERSONAL_CREDIT, null, new BigDecimal("30000000.00"), 36,
                new BigDecimal("6.00"), RepaymentMethod.EQUAL_PAYMENT, 780, "B",
                outstanding, status, NOW);
    }

    // ─── 실행 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SecuredLoanDisbursed 페이로드는 lemuel.loan.secured_loan_disbursed 계약을 만족한다")
    void disbursed_satisfiesContract() {
        publisher.publishDisbursed(mortgage(SecuredLoanStatus.DISBURSED, new BigDecimal("300000000.00")));

        OutboxEvent event = capture();
        assertThat(event.getAggregateType()).isEqualTo("Loan");
        assertThat(event.getEventType()).isEqualTo("SecuredLoanDisbursed");
        assertThat(event.getAggregateId()).isEqualTo("7001");
        EventContractValidator.assertValid("lemuel.loan.secured_loan_disbursed", event.getPayload());
    }

    @Test
    @DisplayName("담보 없는 개인신용 실행 페이로드도 계약을 만족한다 — 담보는 페이로드에 없다")
    void disbursed_personalCredit_satisfiesContract() {
        publisher.publishDisbursed(personalCredit(SecuredLoanStatus.DISBURSED, new BigDecimal("30000000.00")));

        OutboxEvent event = capture();
        assertThat(event.getEventType()).isEqualTo("SecuredLoanDisbursed");
        EventContractValidator.assertValid("lemuel.loan.secured_loan_disbursed", event.getPayload());
    }

    // ─── 완제 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SecuredLoanRepaid 페이로드는 lemuel.loan.secured_loan_repaid 계약을 만족한다")
    void repaid_satisfiesContract() {
        publisher.publishRepaid(mortgage(SecuredLoanStatus.REPAID, BigDecimal.ZERO),
                new BigDecimal("1075000.00"));

        OutboxEvent event = capture();
        assertThat(event.getAggregateType()).isEqualTo("Loan");
        assertThat(event.getEventType()).isEqualTo("SecuredLoanRepaid");
        EventContractValidator.assertValid("lemuel.loan.secured_loan_repaid", event.getPayload());
    }

    @Test
    @DisplayName("이자 0 완제 페이로드도 계약을 만족한다 — 비음수 패턴 허용")
    void repaid_zeroInterest_satisfiesContract() {
        publisher.publishRepaid(mortgage(SecuredLoanStatus.REPAID, BigDecimal.ZERO), BigDecimal.ZERO);

        EventContractValidator.assertValid("lemuel.loan.secured_loan_repaid", capture().getPayload());
    }

    // ─── 소비측이 상품·차주를 구분할 수 있어야 GL 매핑이 가능하다 ──────────────────

    @Test
    @DisplayName("페이로드는 상품유형과 차주유형을 실어 소비측 계정 분기를 가능하게 한다")
    void payloadCarriesProductAndBorrowerType() {
        publisher.publishDisbursed(personalCredit(SecuredLoanStatus.DISBURSED, new BigDecimal("30000000.00")));

        String payload = capture().getPayload();
        assertThat(payload).contains("\"productType\":\"PERSONAL_CREDIT\"");
        assertThat(payload).contains("\"borrowerType\":\"CORPORATE\"");
    }

    private OutboxEvent capture() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }
}
