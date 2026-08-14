package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase.AttachProofCommand;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase.ReviewProofCommand;
import github.lms.lemuel.deposit.application.port.out.ExtractTransferProofPort;
import github.lms.lemuel.deposit.application.port.out.LoadDepositProofPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositProofPort;
import github.lms.lemuel.deposit.config.ProofOcrProperties;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotFoundException;
import github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 예치금 증빙 첨부·리뷰 유스케이스 테스트 (지연 대사 변형).
 *
 * <p>고정하는 것: ① 첨부 시점에는 값 대사를 하지 않는다(대조할 정본이 없다) — 신뢰도 미달·이체일
 * 판독 불가만 즉시 NEEDS_REVIEW ② 같은 파일 재업로드는 OCR 을 다시 부르지 않는다(멱등 + 비용 방어)
 * ③ 판독 실패는 503 무폴백.
 */
class DepositProofServiceTest {

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final byte[] CONTENT = "transfer-proof-bytes".getBytes(StandardCharsets.UTF_8);

    private ExtractTransferProofPort extractPort;
    private SaveDepositProofPort savePort;
    private LoadDepositProofPort loadPort;

    private DepositProofService service;

    @BeforeEach
    void setUp() {
        extractPort = mock(ExtractTransferProofPort.class);
        savePort = mock(SaveDepositProofPort.class);
        loadPort = mock(LoadDepositProofPort.class);
        service = new DepositProofService(extractPort, savePort, loadPort,
                new ProofOcrProperties("key", null, null, null, null, null), FIXED);
    }

    private static ExtractedTransferProof extracted(String confidence, LocalDate date) {
        return new ExtractedTransferProof("홍길동", date, new BigDecimal("3000000"),
                new BigDecimal(confidence));
    }

    private static AttachProofCommand command() {
        return new AttachProofCommand(7L, "MANUAL_TOPUP", "TOPUP-001", 99L,
                "이체확인증.png", "image/png", CONTENT);
    }

    @Test
    @DisplayName("업로드 → OCR → EXTRACTED(기표 대기) 저장 — 값 대사는 하지 않는다")
    void attachStaysExtracted() {
        when(loadPort.findByReferenceAndFileHash(eq(7L), eq("MANUAL_TOPUP"), eq("TOPUP-001"), any()))
                .thenReturn(Optional.empty());
        when(extractPort.isConfigured()).thenReturn(true);
        when(extractPort.modelName()).thenReturn("gemini-2.5-flash");
        when(extractPort.extract(CONTENT, "image/png"))
                .thenReturn(extracted("0.93", LocalDate.of(2026, 8, 12)));
        when(savePort.saveNew(any(), eq(CONTENT))).thenAnswer(inv -> inv.getArgument(0));

        DepositProof saved = service.attach(command());

        assertThat(saved.getStatus()).isEqualTo(DepositProofStatus.EXTRACTED);
        assertThat(saved.getSellerId()).isEqualTo(7L);
        assertThat(saved.getReferenceId()).isEqualTo("TOPUP-001");
        assertThat(saved.getOcrModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("신뢰도 미달·이체일 판독 불가는 첨부 시점에 즉시 NEEDS_REVIEW — 리뷰 경로 확보")
    void attachRoutesReviewDefectsImmediately() {
        when(loadPort.findByReferenceAndFileHash(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(extractPort.isConfigured()).thenReturn(true);
        when(extractPort.modelName()).thenReturn("gemini-2.5-flash");
        when(savePort.saveNew(any(), eq(CONTENT))).thenAnswer(inv -> inv.getArgument(0));

        when(extractPort.extract(CONTENT, "image/png"))
                .thenReturn(extracted("0.50", LocalDate.of(2026, 8, 12)));
        DepositProof lowConfidence = service.attach(command());
        assertThat(lowConfidence.getStatus()).isEqualTo(DepositProofStatus.NEEDS_REVIEW);
        assertThat(lowConfidence.getMatchNote()).contains("신뢰도");

        when(extractPort.extract(CONTENT, "image/png")).thenReturn(extracted("0.93", null));
        DepositProof noDate = service.attach(command());
        assertThat(noDate.getStatus()).isEqualTo(DepositProofStatus.NEEDS_REVIEW);
        assertThat(noDate.getMatchNote()).contains("이체일");
    }

    @Test
    @DisplayName("같은 파일 재업로드는 기존 증빙 반환 — OCR 재호출 없음 (멱등 + 비용 방어)")
    void idempotentReupload() {
        DepositProof existing = DepositProof.extracted(7L, "MANUAL_TOPUP", "TOPUP-001", 99L,
                "이체확인증.png", "image/png", DepositProofService.sha256Hex(CONTENT), 1024L,
                extracted("0.93", LocalDate.of(2026, 8, 12)), "gemini-2.5-flash",
                LocalDateTime.of(2026, 8, 14, 9, 0));
        when(loadPort.findByReferenceAndFileHash(7L, "MANUAL_TOPUP", "TOPUP-001",
                DepositProofService.sha256Hex(CONTENT))).thenReturn(Optional.of(existing));

        DepositProof result = service.attach(command());

        assertThat(result).isSameAs(existing);
        verify(extractPort, never()).extract(any(), any());
        verify(savePort, never()).saveNew(any(), any());
    }

    @Test
    @DisplayName("OCR 미구성이면 503, 빈 파일은 400 동형 예외")
    void ocrNotConfiguredAndEmptyContent() {
        when(loadPort.findByReferenceAndFileHash(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(extractPort.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.attach(command()))
                .isInstanceOf(DepositProofOcrUnavailableException.class);

        assertThatThrownBy(() -> service.attach(new AttachProofCommand(
                7L, "MANUAL_TOPUP", "TOPUP-001", 99L, "f.png", "image/png", new byte[0])))
                .isInstanceOf(InvalidDepositProofException.class);
    }

    @Test
    @DisplayName("리뷰 확정 — NEEDS_REVIEW 증빙을 MATCHED 로 종결하고 저장한다")
    void reviewMatches() {
        DepositProof needsReview = DepositProof.extracted(7L, "MANUAL_TOPUP", "TOPUP-001", 99L,
                "이체확인증.png", "image/png", "hash", 1024L,
                extracted("0.93", null), "gemini-2.5-flash", LocalDateTime.of(2026, 8, 14, 9, 0));
        needsReview.applyDecision(
                github.lms.lemuel.deposit.domain.DepositProofMatchDecision.needsReview("이체일 판독 불가"),
                LocalDateTime.of(2026, 8, 14, 9, 0));
        when(loadPort.findById(5L)).thenReturn(Optional.of(needsReview));
        when(savePort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositProof reviewed = service.review(new ReviewProofCommand(5L, 11L, true, "은행 앱 육안 대조"));

        assertThat(reviewed.getStatus()).isEqualTo(DepositProofStatus.MATCHED);
        assertThat(reviewed.getReviewedBy()).isEqualTo(11L);
    }

    @Test
    @DisplayName("리뷰 대상 미존재는 404 동형 예외")
    void reviewNotFound() {
        when(loadPort.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(new ReviewProofCommand(5L, 11L, true, "note")))
                .isInstanceOf(DepositProofNotFoundException.class);
    }
}
