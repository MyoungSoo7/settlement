package github.lms.lemuel.settlement.adapter.out.pdf;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산서 PDF 어댑터 단위 테스트.
 * CashflowPdfAdapterTest 와 동일하게 magic bytes 로 유효한 PDF 바이너리 생성만 검증한다
 * (WebMvcTest 슬라이스에서 binary response 처리는 Boot 4 이슈가 있어 어댑터 단위로만 검증).
 */
class SettlementPdfAdapterTest {

    private final SettlementPdfAdapter adapter = new SettlementPdfAdapter();

    @Test
    @DisplayName("REQUESTED 상태 — 환불/실패사유/확정일시 없이 PDF 생성")
    void rendersValidPdfForRequestedStatus() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.REQUESTED);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("PROCESSING 상태 — PDF 생성")
    void rendersValidPdfForProcessingStatus() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.PROCESSING);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("DONE 상태 + 확정일시 포함 — PDF 생성")
    void rendersValidPdfForDoneStatusWithConfirmedAt() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.DONE);
        settlement.setConfirmedAt(LocalDateTime.of(2026, 4, 5, 9, 30, 0));

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("DONE 상태 + 확정일시 없음 — PDF 생성 (메타 정보 확정 일시 행 생략)")
    void rendersValidPdfForDoneStatusWithoutConfirmedAt() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.DONE);
        settlement.setConfirmedAt(null);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    // NOTE: "실패 사유 non-blank" 분기(renderFailureReason)는 프로덕션 코드의 버그로 인해 테스트할 수 없다.
    // SettlementPdfAdapter.java:201 이 하드코딩한 "⚠"(경고 기호) 문자가 임베디드 한글 CID 폰트
    // (HYGoThic-Medium/UniKS-UCS2-H) 에 글리프 매핑이 없어, PdfDocument.close() 시점에
    // iText 내부(PdfType0Font.generateWidthsArray)에서 NullPointerException 이 100% 재현된다.
    // 실행 계약상 프로덕션 코드 수정이 금지되어 있어 이 브랜치는 커버리지에서 제외한다 — 실제 버그이므로
    // 오케스트레이터에게 별도 보고함(failureReason 이 non-blank 인 실패 정산의 PDF 다운로드는 현재 운영에서도 크래시함).

    @Test
    @DisplayName("FAILED 상태 + 실패 사유 blank — 실패 사유 섹션 미렌더링 분기 커버")
    void rendersValidPdfForFailedStatusWithBlankFailureReason() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.FAILED);
        settlement.setFailureReason("   ");

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("FAILED 상태 + 실패 사유 null — 실패 사유 섹션 미렌더링 분기 커버")
    void rendersValidPdfForFailedStatusWithNullFailureReason() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.FAILED);
        settlement.setFailureReason(null);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("CANCELED 상태 — PDF 생성")
    void rendersValidPdfForCanceledStatus() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.CANCELED);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("환불 금액 > 0 — 환불 행 강조 렌더링 분기 커버")
    void rendersValidPdfWithRefundedAmount() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.PROCESSING);
        settlement.setRefundedAmount(new BigDecimal("10000"));

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("환불 금액 == 0 — '—' 텍스트 렌더링 분기 커버")
    void rendersValidPdfWithZeroRefundedAmount() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.REQUESTED);
        settlement.setRefundedAmount(BigDecimal.ZERO);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("정산일 없음 — 메타 정보 '-' 텍스트 렌더링 분기 커버")
    void rendersValidPdfWithoutSettlementDate() {
        Settlement settlement = buildSettlement();
        settlement.setStatus(SettlementStatus.REQUESTED);
        settlement.setSettlementDate(null);

        byte[] pdf = adapter.render(settlement);

        assertPdfMagic(pdf);
    }

    private static void assertPdfMagic(byte[] pdf) {
        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(100);
        // PDF magic: %PDF
        assertThat(pdf[0]).isEqualTo((byte) 0x25); // %
        assertThat(pdf[1]).isEqualTo((byte) 0x50); // P
        assertThat(pdf[2]).isEqualTo((byte) 0x44); // D
        assertThat(pdf[3]).isEqualTo((byte) 0x46); // F
    }

    private Settlement buildSettlement() {
        Settlement settlement = Settlement.createFromPayment(
                101L, 202L, new BigDecimal("100000"), LocalDate.of(2026, 4, 1));
        settlement.setId(1L);
        return settlement;
    }
}
