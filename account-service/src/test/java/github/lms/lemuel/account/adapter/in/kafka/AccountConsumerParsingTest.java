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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private java.util.List<AccountEntry> captureAll(int times) {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase, org.mockito.Mockito.times(times)).record(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("settlement.created 정본 샘플 → 즉시분(DR CASH/CR SELLER_PAYABLE) + 유보분(DR CASH/CR HOLDBACK_PAYABLE) 2전표 (Option ①)")
    void settlementCreatedSplitsImmediateAndHoldback() throws Exception {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var node = objectMapper.readTree(EventContractValidator.canonicalSample("lemuel.settlement.created"));
        java.math.BigDecimal net = new java.math.BigDecimal(node.get("amount").asText());
        java.math.BigDecimal holdback = node.has("holdbackAmount") && !node.get("holdbackAmount").isNull()
                ? new java.math.BigDecimal(node.get("holdbackAmount").asText()) : java.math.BigDecimal.ZERO;
        java.math.BigDecimal immediate = net.subtract(holdback);
        int expected = (immediate.signum() > 0 ? 1 : 0) + (holdback.signum() > 0 ? 1 : 0);

        SettlementCreatedConsumer c = new SettlementCreatedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onSettlementCreated(canonicalRecordOf("lemuel.settlement.created"), mock(Acknowledgment.class));

        java.util.List<AccountEntry> entries = captureAll(expected);
        AccountEntry imm = entries.stream().filter(e -> e.getRefType().equals("SETTLEMENT_CREATED")).findFirst().orElseThrow();
        assertThat(imm.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(imm.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(imm.getAmount()).isEqualByComparingTo(immediate);
        if (holdback.signum() > 0) {
            AccountEntry hb = entries.stream().filter(e -> e.getRefType().equals("SETTLEMENT_HOLDBACK_RECOGNIZED")).findFirst().orElseThrow();
            assertThat(hb.getDebitAccount()).isEqualTo(GlAccount.CASH);
            assertThat(hb.getCreditAccount()).isEqualTo(GlAccount.HOLDBACK_PAYABLE);
            assertThat(hb.getAmount()).isEqualByComparingTo(holdback);
        }
    }

    @Test
    @DisplayName("settlement.created holdbackAmount 누락 → 즉시분 1전표만(하위호환)")
    void settlementCreatedWithoutHoldback_recordsImmediateOnly() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCreatedConsumer c = new SettlementCreatedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        String payload = "{\"settlementId\":9001,\"sellerId\":777,\"amount\":43425,\"dueDate\":null}";

        c.onSettlementCreated(recordOf("lemuel.settlement.created", payload), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
    }

    @Test
    @DisplayName("settlement.holdback_released 정본 샘플 → DR HOLDBACK_PAYABLE / CR SELLER_PAYABLE")
    void holdbackReleased() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementHoldbackReleasedConsumer c = new SettlementHoldbackReleasedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onHoldbackReleased(canonicalRecordOf("lemuel.settlement.holdback_released"), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.HOLDBACK_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getRefType()).isEqualTo("HOLDBACK_RELEASED");
    }

    @Test
    @DisplayName("settlement.holdback_consumed 정본 샘플 → DR HOLDBACK_PAYABLE / CR CASH (refId=sourceAdjustmentId)")
    void holdbackConsumed() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementHoldbackConsumedConsumer c = new SettlementHoldbackConsumedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onHoldbackConsumed(canonicalRecordOf("lemuel.settlement.holdback_consumed"), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.HOLDBACK_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("HOLDBACK_CONSUMED");
        assertThat(e.getRefId()).isEqualTo("5501");
    }

    @Test
    @DisplayName("settlement.adjusted 정본 샘플(targetLeg=SELLER_PAYABLE) → DR SELLER_PAYABLE / CR CASH")
    void settlementAdjusted() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementAdjustedConsumer c = new SettlementAdjustedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onSettlementAdjusted(canonicalRecordOf("lemuel.settlement.adjusted"), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("SETTLEMENT_ADJUSTED");
    }

    @Test
    @DisplayName("settlement.adjusted targetLeg=HOLDBACK_PAYABLE → DR HOLDBACK_PAYABLE / CR CASH")
    void settlementAdjustedHoldbackLeg() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementAdjustedConsumer c = new SettlementAdjustedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        String payload = "{\"adjustmentId\":5502,\"settlementId\":9001,\"sellerId\":777,\"amount\":5000,\"targetLeg\":\"HOLDBACK_PAYABLE\"}";
        EventContractValidator.assertValid("lemuel.settlement.adjusted", payload);
        c.onSettlementAdjusted(recordOf("lemuel.settlement.adjusted", payload), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.HOLDBACK_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
    }

    @Test
    @DisplayName("settlement.adjusted 알 수 없는 targetLeg → IllegalArgumentException(즉시 DLT) + 분개 미적재")
    void settlementAdjustedUnknownLeg_throws() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementAdjustedConsumer c = new SettlementAdjustedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        String payload = "{\"adjustmentId\":5502,\"sellerId\":777,\"amount\":5000,\"targetLeg\":\"BOGUS\"}";
        assertThatThrownBy(() -> c.onSettlementAdjusted(recordOf("lemuel.settlement.adjusted", payload), mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    @DisplayName("settlement.canceled 정본 샘플 → 즉시 잔여·유보 잔여 2전표(DR SELLER_PAYABLE/HOLDBACK_PAYABLE / CR CASH)")
    void settlementCanceled() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCanceledConsumer c = new SettlementCanceledConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onSettlementCanceled(canonicalRecordOf("lemuel.settlement.canceled"), mock(Acknowledgment.class));
        java.util.List<AccountEntry> entries = captureAll(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
            assertThat(e.getRefType()).isEqualTo("SETTLEMENT_CANCELED_PAYABLE");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getDebitAccount()).isEqualTo(GlAccount.HOLDBACK_PAYABLE);
            assertThat(e.getRefType()).isEqualTo("SETTLEMENT_CANCELED_HOLDBACK");
        });
    }

    @Test
    @DisplayName("seller_recovery.opened 정본 샘플 → DR SELLER_RECOVERY_RECEIVABLE / CR CASH (refId=recoveryId)")
    void recoveryOpened() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SellerRecoveryOpenedConsumer c = new SellerRecoveryOpenedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onRecoveryOpened(canonicalRecordOf("lemuel.seller_recovery.opened"), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_RECOVERY_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("RECOVERY_OPENED");
        assertThat(e.getRefId()).isEqualTo("3001");
    }

    @Test
    @DisplayName("seller_recovery.offset 정본 샘플 → DR SELLER_PAYABLE / CR SELLER_RECOVERY_RECEIVABLE (refId=allocationId)")
    void recoveryOffset() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SellerRecoveryOffsetConsumer c = new SellerRecoveryOffsetConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);
        c.onRecoveryOffset(canonicalRecordOf("lemuel.seller_recovery.offset"), mock(Acknowledgment.class));
        AccountEntry e = capture();
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_RECOVERY_RECEIVABLE);
        assertThat(e.getRefType()).isEqualTo("RECOVERY_OFFSET");
        assertThat(e.getRefId()).isEqualTo("4001");
    }

    @Test
    @DisplayName("settlement.confirmed 정본 샘플 → GL 무전표 (Option A, record 미호출)")
    void settlementConfirmedIsNoGlEntry() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementConfirmedConsumer c = new SettlementConfirmedConsumer(
                processedEventRepository, objectMapper);

        c.onSettlementConfirmed(canonicalRecordOf("lemuel.settlement.confirmed"), mock(Acknowledgment.class));

        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    @DisplayName("payout.completed 정본 샘플 → DR SELLER_PAYABLE / CR CASH (Option A 미지급금 상계+현금 유출)")
    void payoutCompleted() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        PayoutCompletedConsumer c = new PayoutCompletedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        c.onPayoutCompleted(canonicalRecordOf("lemuel.payout.completed"), mock(Acknowledgment.class));

        AccountEntry e = capture();
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefId()).isEqualTo("7001"); // refId=payoutId (멱등 자연키)
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

    @Test
    @DisplayName("필수 필드(amount) 누락 → IllegalArgumentException(비재시도, 즉시 DLT) + 분개 미적재")
    void missingRequiredField_throwsIaeWithoutRecording() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCreatedConsumer c = new SettlementCreatedConsumer(
                recordAccountEntryUseCase, processedEventRepository, objectMapper);

        assertThatThrownBy(() -> c.onSettlementCreated(
                recordOf("lemuel.settlement.created", "{\"settlementId\":9001,\"sellerId\":777,\"dueDate\":null}"),
                mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
