package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.out.LoadDepositProofPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositProofPort;
import github.lms.lemuel.deposit.config.ProofOcrProperties;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofMatchDecision;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotMatchedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수기 기표 증빙 대사 게이트 (지연 대사) 테스트.
 *
 * <p>고정하는 것: ① 증빙이 없으면 무개입(점진 도입 — Kafka 자동 기표 무영향) ② EXTRACTED 는 기표
 * 시점에 요청 값과 대사되어 MATCHED 만 통과 ③ NEEDS_REVIEW·MISMATCHED 는 422 ④ 기표일은
 * 이체일 ±허용일수(기본 3일)로 수기 리드타임을 흡수한다.
 */
class DepositProofGateTest {

    /** 기표일: 2026-08-14 (KST) */
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final BigDecimal AMOUNT = new BigDecimal("3000000.00");

    private LoadDepositProofPort loadPort;
    private SaveDepositProofPort savePort;
    private DepositProofGate gate;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadDepositProofPort.class);
        savePort = mock(SaveDepositProofPort.class);
        gate = gateWith(false);
        when(savePort.update(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DepositProofGate gateWith(boolean required) {
        return new DepositProofGate(loadPort, savePort,
                new ProofOcrProperties("key", null, null, null, null, null, required, null), FIXED);
    }

    private static DepositProof proofWith(String amount, LocalDate transferDate) {
        return DepositProof.extracted(7L, "MANUAL_TOPUP", "TOPUP-001", 99L,
                "이체확인증.png", "image/png", "hash", 1024L,
                new ExtractedTransferProof("홍길동", transferDate, new BigDecimal(amount),
                        new BigDecimal("0.93")),
                "gemini-2.5-flash", LocalDateTime.of(2026, 8, 13, 9, 0));
    }

    @Test
    @DisplayName("증빙이 없으면 무개입 — Kafka 자동 기표(SETTLEMENT/PAYOUT)가 그대로 흐른다")
    void noProofNoGate() {
        when(loadPort.findLatestByReference(7L, "SETTLEMENT", "123")).thenReturn(Optional.empty());

        assertThatCode(() -> gate.assertMatchedIfProofExists(7L, "SETTLEMENT", "123", AMOUNT))
                .doesNotThrowAnyException();
        verify(savePort, never()).update(any());
    }

    @Test
    @DisplayName("EXTRACTED — 기표 시점 지연 대사: 금액·이체일 일치면 MATCHED 영속 후 통과")
    void deferredReconMatches() {
        DepositProof proof = proofWith("3000000", LocalDate.of(2026, 8, 12));   // 2일 전 이체 ≤ 3일
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(proof));

        gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT);

        ArgumentCaptor<DepositProof> captor = ArgumentCaptor.forClass(DepositProof.class);
        verify(savePort).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DepositProofStatus.MATCHED);
    }

    @Test
    @DisplayName("EXTRACTED — 금액 불일치면 422, 이체일 리드타임 초과(4일)도 422")
    void deferredReconMismatches() {
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(proofWith("2999999", LocalDate.of(2026, 8, 12))));
        assertThatThrownBy(() -> gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .isInstanceOf(DepositProofNotMatchedException.class)
                .hasMessageContaining("이체금액");

        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(proofWith("3000000", LocalDate.of(2026, 8, 10))));
        assertThatThrownBy(() -> gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .isInstanceOf(DepositProofNotMatchedException.class)
                .hasMessageContaining("이체일");
    }

    @Test
    @DisplayName("NEEDS_REVIEW·MISMATCHED 증빙으로는 기표 불가(422) — 리뷰 종결·재첨부가 먼저다")
    void pendingOrRejectedBlocks() {
        DepositProof needsReview = proofWith("3000000", LocalDate.of(2026, 8, 12));
        needsReview.applyDecision(DepositProofMatchDecision.needsReview("신뢰도 미달"),
                LocalDateTime.of(2026, 8, 13, 9, 0));
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(needsReview));
        assertThatThrownBy(() -> gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .isInstanceOf(DepositProofNotMatchedException.class);

        DepositProof mismatched = proofWith("3000000", LocalDate.of(2026, 8, 12));
        mismatched.applyDecision(DepositProofMatchDecision.mismatched("이체금액 불일치"),
                LocalDateTime.of(2026, 8, 13, 9, 0));
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(mismatched));
        assertThatThrownBy(() -> gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .isInstanceOf(DepositProofNotMatchedException.class);
    }

    @Test
    @DisplayName("전면 강제(required=true)면 수기 참조의 증빙 미첨부 기표가 422 로 거절된다")
    void requiredModeBlocksMissingProof() {
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateWith(true)
                .assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .isInstanceOf(DepositProofNotMatchedException.class)
                .hasMessageContaining("첨부되지 않아");
    }

    @Test
    @DisplayName("전면 강제여도 면제 referenceType(SETTLEMENT·PAYOUT)은 통과 — Kafka 자동 기표를 멈추지 않는다")
    void requiredModeExemptsKafkaReferenceTypes() {
        when(loadPort.findLatestByReference(7L, "SETTLEMENT", "123")).thenReturn(Optional.empty());
        when(loadPort.findLatestByReference(7L, "PAYOUT", "456")).thenReturn(Optional.empty());

        assertThatCode(() -> {
            gateWith(true).assertMatchedIfProofExists(7L, "SETTLEMENT", "123", AMOUNT);
            gateWith(true).assertMatchedIfProofExists(7L, "PAYOUT", "456", AMOUNT);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MATCHED 증빙(운영자 육안 확정 포함)은 통과 — 재판정하지 않는다")
    void matchedPasses() {
        DepositProof matched = proofWith("3000000", LocalDate.of(2026, 8, 12));
        matched.applyDecision(DepositProofMatchDecision.matched(), LocalDateTime.of(2026, 8, 13, 9, 0));
        when(loadPort.findLatestByReference(7L, "MANUAL_TOPUP", "TOPUP-001"))
                .thenReturn(Optional.of(matched));

        assertThatCode(() -> gate.assertMatchedIfProofExists(7L, "MANUAL_TOPUP", "TOPUP-001", AMOUNT))
                .doesNotThrowAnyException();
        verify(savePort, never()).update(any());
    }
}
