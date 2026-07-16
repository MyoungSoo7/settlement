package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.common.events.contract.EventContractValidator;
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
 * 컨슈머 계약 테스트 (ADR 0024) — 각 토픽의 <b>정본 샘플</b>(shared-common contracts 픽스처)을 실제
 * 컨슈머에 통과시켜, 이벤트→분개 매핑(차/대 계정·owner·refId·금액)이 계약 값 그대로 적재되는지 검증한다.
 * 프로듀서가 계약 필드를 바꾸면 이 테스트가 빌드 시점에 깨진다 — account 는 GL 의 유일한 집계자라
 * 계약 드리프트가 런타임 분개 오류로 새는 것을 여기서 차단한다.
 * (정본 샘플에 없는 경계 케이스만 인라인 JSON 을 쓰되, 스키마 유효성을 함께 고정한다.)
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

    private static ConsumerRecord<String, String> canonicalRecordOf(String topic) {
        return recordOf(topic, EventContractValidator.canonicalSample(topic));
    }

    private AccountEntry capture() {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("settlement.created 정본 샘플 → DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE")
    void settlementCreated() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCreatedConsumer c = new SettlementCreatedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onSettlementCreated(canonicalRecordOf("lemuel.settlement.created"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefId()).isEqualTo("9001");
    }

    @Test
    @DisplayName("settlement.confirmed 정본 샘플 → DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED (예정 상계)")
    void settlementConfirmed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementConfirmedConsumer c = new SettlementConfirmedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onSettlementConfirmed(canonicalRecordOf("lemuel.settlement.confirmed"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefId()).isEqualTo("9001");
    }

    @Test
    @DisplayName("loan.disbursement_requested 정본 샘플 → DR LOAN_RECEIVABLE / CR CASH")
    void loanDisbursed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanDisbursementRequestedConsumer c = new LoanDisbursementRequestedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onLoanDisbursed(canonicalRecordOf("lemuel.loan.disbursement_requested"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefId()).isEqualTo("501");
        assertThat(e.getAmount()).isEqualByComparingTo("800000");
    }

    @Test
    @DisplayName("loan.corporate_loan_disbursed 정본 샘플 → CORPORATE, principal 만 분개")
    void corporateLoanDisbursed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        CorporateLoanDisbursedConsumer c = new CorporateLoanDisbursedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onCorporateLoanDisbursed(canonicalRecordOf("lemuel.loan.corporate_loan_disbursed"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.CORPORATE);
        assertThat(e.getOwnerId()).isEqualTo("005930");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CORPORATE_LOAN_RECEIVABLE);
        assertThat(e.getRefId()).isEqualTo("5001");
        assertThat(e.getAmount()).isEqualByComparingTo("1000000"); // principal 만, fee(660) 무시
    }

    @Test
    @DisplayName("investment.executed 정본 샘플 → DR INVESTMENT_ASSET / CR CASH")
    void investmentExecuted() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        InvestmentExecutedConsumer c = new InvestmentExecutedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onInvestmentExecuted(canonicalRecordOf("lemuel.investment.executed"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.INVESTMENT_ASSET);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefId()).isEqualTo("5001");
        assertThat(e.getAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("loan.repayment_applied 정본 샘플(deducted>0) → DR CASH / CR LOAN_RECEIVABLE")
    void loanRepaidPositive() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanRepaymentAppliedConsumer c = new LoanRepaymentAppliedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onLoanRepaid(canonicalRecordOf("lemuel.loan.repayment_applied"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("loan.repayment_applied deducted=0(계약 minimum 0 허용) → 분개 생략(record 미호출)")
    void loanRepaidZeroSkips() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanRepaymentAppliedConsumer c = new LoanRepaymentAppliedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        String payload = "{\"settlementId\":9001,\"sellerId\":777,\"deducted\":0}";
        EventContractValidator.assertValid("lemuel.loan.repayment_applied", payload);

        c.onLoanRepaid(recordOf("lemuel.loan.repayment_applied", payload), mock(Acknowledgment.class));

        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
