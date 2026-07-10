package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 파싱 테스트 — 각 토픽의 payload(인라인 JSON 정본)를 실제 컨슈머에 통과시켜,
 * 이벤트→분개 매핑(차/대 계정·owner·refId·금액)이 계약 값 그대로 적재되는지 검증한다.
 * (외부 픽스처 의존 없음 — 병렬 작업 중 계약 픽스처 부재 대비.)
 */
@ExtendWith(MockitoExtension.class)
class AccountConsumerParsingTest {

    @Mock RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private AccountEntry capture() {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("settlement.created → DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE")
    void settlementCreated() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCreatedConsumer c = new SettlementCreatedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onSettlementCreated(recordOf("lemuel.settlement.created",
                "{\"settlementId\":9001,\"sellerId\":777,\"amount\":43425,\"dueDate\":\"2026-07-10\"}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefId()).isEqualTo("9001");
    }

    @Test
    @DisplayName("settlement.confirmed → DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED (예정 상계)")
    void settlementConfirmed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementConfirmedConsumer c = new SettlementConfirmedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onSettlementConfirmed(recordOf("lemuel.settlement.confirmed",
                "{\"settlementId\":9001,\"sellerId\":777,\"amount\":43425}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefId()).isEqualTo("9001");
    }

    @Test
    @DisplayName("loan.disbursement_requested → DR LOAN_RECEIVABLE / CR CASH")
    void loanDisbursed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanDisbursementRequestedConsumer c = new LoanDisbursementRequestedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onLoanDisbursed(recordOf("lemuel.loan.disbursement_requested",
                "{\"loanId\":501,\"sellerId\":55,\"amount\":800000}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefId()).isEqualTo("501");
        assertThat(e.getAmount()).isEqualByComparingTo("800000");
    }

    @Test
    @DisplayName("loan.corporate_loan_disbursed → CORPORATE, principal 만 분개")
    void corporateLoanDisbursed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        CorporateLoanDisbursedConsumer c = new CorporateLoanDisbursedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onCorporateLoanDisbursed(recordOf("lemuel.loan.corporate_loan_disbursed",
                "{\"loanId\":9,\"stockCode\":\"005930\",\"corpName\":\"삼성전자\",\"principal\":5000000,\"fee\":12000}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.CORPORATE);
        assertThat(e.getOwnerId()).isEqualTo("005930");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CORPORATE_LOAN_RECEIVABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("5000000"); // principal 만, fee 무시
    }

    @Test
    @DisplayName("investment.executed → DR INVESTMENT_ASSET / CR CASH")
    void investmentExecuted() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        InvestmentExecutedConsumer c = new InvestmentExecutedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onInvestmentExecuted(recordOf("lemuel.investment.executed",
                "{\"orderId\":3003,\"sellerId\":55,\"stockCode\":\"000660\",\"amount\":250000}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.INVESTMENT_ASSET);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefId()).isEqualTo("3003");
    }

    @Test
    @DisplayName("loan.repayment_applied deducted>0 → DR CASH / CR LOAN_RECEIVABLE")
    void loanRepaidPositive() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanRepaymentAppliedConsumer c = new LoanRepaymentAppliedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onLoanRepaid(recordOf("lemuel.loan.repayment_applied",
                "{\"settlementId\":9001,\"sellerId\":55,\"deducted\":12345}"),
                mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("12345");
    }

    @Test
    @DisplayName("loan.repayment_applied deducted=0 → 분개 생략(record 미호출)")
    void loanRepaidZeroSkips() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanRepaymentAppliedConsumer c = new LoanRepaymentAppliedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onLoanRepaid(recordOf("lemuel.loan.repayment_applied",
                "{\"settlementId\":9001,\"sellerId\":55,\"deducted\":0}"),
                mock(Acknowledgment.class));

        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
