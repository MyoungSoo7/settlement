package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.in.RecordPayoutBounceUseCase.BounceOutcome;
import github.lms.lemuel.payout.application.port.out.LoadPayoutBouncePort;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutBouncePort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutBounce;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import github.lms.lemuel.payout.domain.exception.PayoutBounceNotAllowedException;
import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Seed D1 반송 재지급 — "완료 송금 반송 → 정정계좌 재지급, 이중지급 없이 정확히 한 번" 규약.
 *
 * <p>독립 코드리뷰 HIGH 시정: 재지급 계좌 해석은 폴백 없는 {@link LoadSellerBankAccountRegistrationPort}
 * 전용이어야 하고(미등록 셀러는 거부), 등록 계좌가 원 계좌와 동일하면 거부해야 한다(정정 선행 강제).
 */
@ExtendWith(MockitoExtension.class)
class RecordPayoutBounceServiceTest {

    private static final Long PAYOUT_ID = 500L;
    private static final Long SELLER_ID = 7L;
    private static final BigDecimal AMOUNT = new BigDecimal("95500.00");
    private static final SellerBankAccount OLD_ACCOUNT =
            new SellerBankAccount("KB", "111-11-111111", "홍길동");
    private static final SellerBankAccount CORRECTED_ACCOUNT =
            new SellerBankAccount("SHINHAN", "222-22-222222", "홍길동");

    @Mock LoadPayoutPort loadPayoutPort;
    @Mock SavePayoutPort savePayoutPort;
    @Mock LoadPayoutBouncePort loadBouncePort;
    @Mock SavePayoutBouncePort saveBouncePort;
    @Mock LoadSellerBankAccountRegistrationPort registrationPort;
    @Mock AuditLogger auditLogger;

    private RecordPayoutBounceService service;

    @BeforeEach
    void setUp() {
        service = new RecordPayoutBounceService(loadPayoutPort, savePayoutPort,
                loadBouncePort, saveBouncePort, registrationPort, auditLogger);
    }

    private Payout completedOriginal() {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(PAYOUT_ID, 100L, PayoutType.IMMEDIATE, SELLER_ID, AMOUNT, OLD_ACCOUNT,
                PayoutStatus.COMPLETED, "FB-1", null, 0, null, now, now, now, null, now, now);
    }

    private static SellerBankAccountRegistration registrationOf(SellerBankAccount account) {
        return SellerBankAccountRegistration.register(
                SELLER_ID, account.bankCode(), account.bankAccountNumber(), account.accountHolderName());
    }

    @Test
    @DisplayName("반송 대상 payout 미존재 → 타입 예외")
    void payoutNotFound() {
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordBounce(PAYOUT_ID, "ACCOUNT_CLOSED", "op"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        verifyNoInteractions(saveBouncePort, savePayoutPort);
    }

    @Test
    @DisplayName("비COMPLETED 송금 반송 시도 → PayoutBounceNotAllowedException (아무것도 저장 안 함)")
    void rejectsNonCompleted() {
        LocalDateTime now = LocalDateTime.now();
        Payout requested = Payout.rehydrate(PAYOUT_ID, 100L, PayoutType.IMMEDIATE, SELLER_ID, AMOUNT,
                OLD_ACCOUNT, PayoutStatus.REQUESTED, null, null, 0, null, now, null, null, null, now, now);
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(requested));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordBounce(PAYOUT_ID, "reason", "op"))
                .isInstanceOf(PayoutBounceNotAllowedException.class);
        verifyNoInteractions(saveBouncePort, savePayoutPort);
    }

    @Test
    @DisplayName("등록 계좌 없는 셀러 bounce → 거부 (계좌 정정 선행 강제, 폴백 없음)")
    void rejectsWhenAccountNotRegistered() {
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(completedOriginal()));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.empty());
        when(registrationPort.findBySellerId(SELLER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordBounce(PAYOUT_ID, "ACCOUNT_CLOSED", "op"))
                .isInstanceOf(PayoutBounceNotAllowedException.class)
                .hasMessageContaining("등록된 지급계좌 없음");
        verifyNoInteractions(saveBouncePort, savePayoutPort, auditLogger);
    }

    @Test
    @DisplayName("등록 계좌가 원 계좌와 동일 → 거부 (정정되지 않은 재지급 차단)")
    void rejectsWhenRegisteredAccountSameAsOriginal() {
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(completedOriginal()));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.empty());
        when(registrationPort.findBySellerId(SELLER_ID)).thenReturn(Optional.of(registrationOf(OLD_ACCOUNT)));

        assertThatThrownBy(() -> service.recordBounce(PAYOUT_ID, "ACCOUNT_CLOSED", "op"))
                .isInstanceOf(PayoutBounceNotAllowedException.class)
                .hasMessageContaining("동일 계좌");
        verifyNoInteractions(saveBouncePort, savePayoutPort, auditLogger);
    }

    @Test
    @DisplayName("정상: 정정된(다른) 계좌 등록 후 반송 기록 + 신규 REQUESTED payout(settlementId=null) 재발행")
    void recordsAndReissuesWithCorrectedAccount() {
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(completedOriginal()));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.empty());
        when(registrationPort.findBySellerId(SELLER_ID)).thenReturn(Optional.of(registrationOf(CORRECTED_ACCOUNT)));
        stubBounceSaveAssignId();
        when(savePayoutPort.save(any(Payout.class))).thenAnswer(inv -> {
            Payout p = inv.getArgument(0);
            p.assignId(999L);
            return p;
        });

        BounceOutcome outcome = service.recordBounce(PAYOUT_ID, "ACCOUNT_CLOSED", "op-1");

        // 재발행 payout — 정정 계좌, 원 금액·유형 승계, settlementId=null(이중지급 가드 보존)
        ArgumentCaptor<Payout> reissueCaptor = ArgumentCaptor.forClass(Payout.class);
        verify(savePayoutPort).save(reissueCaptor.capture());
        Payout reissued = reissueCaptor.getValue();
        assertThat(reissued.getSettlementId()).isNull();
        assertThat(reissued.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(reissued.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(reissued.getPayoutType()).isEqualTo(PayoutType.IMMEDIATE);
        assertThat(reissued.getStatus()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(reissued.getAccount().bankCode()).isEqualTo("SHINHAN");

        assertThat(outcome.bounce().getResolvedPayoutId()).isEqualTo(999L);
        assertThat(outcome.reissuedPayout().getId()).isEqualTo(999L);
        verify(auditLogger).record(eq(AuditAction.PAYOUT_BOUNCE_RECORDED), eq("Payout"),
                eq(String.valueOf(PAYOUT_ID)), any());
    }

    @Test
    @DisplayName("멱등: 이미 반송된 payout 재호출 → 재지급 재생성 없이 기존 bounce·재발행 payout 반환")
    void idempotentReplay() {
        PayoutBounce existing = PayoutBounce.rehydrate(1L, PAYOUT_ID, "ACCOUNT_CLOSED", 999L, "op",
                LocalDateTime.now(), LocalDateTime.now());
        Payout reissued = completedOriginal();
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(completedOriginal()));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.of(existing));
        when(loadPayoutPort.findById(999L)).thenReturn(Optional.of(reissued));

        BounceOutcome outcome = service.recordBounce(PAYOUT_ID, "ACCOUNT_CLOSED", "op");

        assertThat(outcome.bounce()).isSameAs(existing);
        assertThat(outcome.reissuedPayout()).isSameAs(reissued);
        verify(saveBouncePort, never()).save(any());
        verify(savePayoutPort, never()).save(any());
        verifyNoInteractions(auditLogger, registrationPort);
    }

    @Test
    @DisplayName("동시 이중 반송(UNIQUE 경합) → PayoutConcurrentClaimException, 재지급 미생성")
    void concurrentBounceConflict() {
        when(loadPayoutPort.findById(PAYOUT_ID)).thenReturn(Optional.of(completedOriginal()));
        when(loadBouncePort.findByPayoutId(PAYOUT_ID)).thenReturn(Optional.empty());
        when(registrationPort.findBySellerId(SELLER_ID)).thenReturn(Optional.of(registrationOf(CORRECTED_ACCOUNT)));
        when(saveBouncePort.save(any())).thenThrow(new DataIntegrityViolationException("uq_payout_bounce_payout"));

        assertThatThrownBy(() -> service.recordBounce(PAYOUT_ID, "reason", "op"))
                .isInstanceOf(PayoutConcurrentClaimException.class);
        verify(savePayoutPort, never()).save(any());
    }

    private void stubBounceSaveAssignId() {
        when(saveBouncePort.save(any())).thenAnswer(inv -> {
            PayoutBounce b = inv.getArgument(0);
            if (b.getId() == null) {
                return PayoutBounce.rehydrate(1L, b.getPayoutId(), b.getReason(), b.getResolvedPayoutId(),
                        b.getOperatorId(), b.getBouncedAt(), b.getCreatedAt());
            }
            return b;
        });
    }
}
