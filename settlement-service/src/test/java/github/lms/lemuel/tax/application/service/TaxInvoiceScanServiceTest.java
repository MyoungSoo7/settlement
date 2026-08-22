package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase.UploadTaxInvoiceScanCommand;
import github.lms.lemuel.tax.application.port.out.ExtractTaxInvoiceFieldsPort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoiceScanPort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoiceScanPort;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스캔 업로드 유스케이스 — OCR 호출 → 도메인 검증 → 자동 대사 → 저장의 조립을 검증한다.
 *
 * <p>중점: <b>멱등</b>(같은 파일 재업로드는 AI 재호출 없이 기존 스캔 반환)과 <b>대사 분기</b>
 * (MATCHED/MISMATCHED/UNMATCHED), 그리고 OCR 미구성·실패의 503 계약.
 */
class TaxInvoiceScanServiceTest {

    private static final Long SELLER = 7L;
    private static final LocalDate WRITTEN = LocalDate.of(2026, 8, 1);
    private static final byte[] CONTENT = "세금계산서 스캔본".getBytes(StandardCharsets.UTF_8);

    private ExtractTaxInvoiceFieldsPort ocrPort;
    private SaveTaxInvoiceScanPort savePort;
    private LoadTaxInvoiceScanPort loadScanPort;
    private LoadTaxInvoicePort loadInvoicePort;
    private TaxInvoiceScanService service;

    @BeforeEach
    void setUp() {
        ocrPort = mock(ExtractTaxInvoiceFieldsPort.class);
        savePort = mock(SaveTaxInvoiceScanPort.class);
        loadScanPort = mock(LoadTaxInvoiceScanPort.class);
        loadInvoicePort = mock(LoadTaxInvoicePort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T03:00:00Z"), ZoneOffset.UTC);

        when(ocrPort.isConfigured()).thenReturn(true);
        when(ocrPort.modelName()).thenReturn("test-ocr");
        when(loadScanPort.findBySellerIdAndFileHash(anyLong(), anyString())).thenReturn(Optional.empty());
        when(savePort.save(any(TaxInvoiceScan.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new TaxInvoiceScanService(ocrPort, savePort, loadScanPort, loadInvoicePort, clock,
                new BigDecimal("0.80"));
    }

    private static UploadTaxInvoiceScanCommand command() {
        return new UploadTaxInvoiceScanCommand(SELLER, "invoice.png", "image/png", CONTENT);
    }

    private static OcrExtraction extraction(String supply, String tax, String total, String approvalNumber) {
        return extraction(supply, tax, total, approvalNumber, "0.95", "0.95");
    }

    /** 축별 신뢰도를 다르게 주는 변형 — 금액은 또렷한데 승인번호만 뭉개진 경우 등. */
    private static OcrExtraction extraction(String supply, String tax, String total,
                                            String approvalNumber, String amountConfidence,
                                            String approvalConfidence) {
        return new OcrExtraction("101-81-00001", "101-81-00001", WRITTEN,
                new BigDecimal(supply), new BigDecimal(tax), new BigDecimal(total),
                approvalNumber, new BigDecimal(amountConfidence), new BigDecimal(approvalConfidence));
    }

    private static TaxInvoice issued(Long sellerId, String supply, String tax) {
        BigDecimal supplyAmount = new BigDecimal(supply);
        BigDecimal taxAmount = new BigDecimal(tax);
        return TaxInvoice.rehydrate(42L, 5L, sellerId, supplyAmount, taxAmount, supplyAmount.add(taxAmount),
                WRITTEN, TaxInvoice.numberFor(5L), LocalDateTime.of(2026, 8, 1, 12, 0));
    }

    @Test
    @DisplayName("승인번호가 우리 발행분을 가리키고 금액이 같으면 MATCHED 로 저장된다")
    void matched() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TI-0000000005"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "100000", "10000")));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
        assertThat(scan.getLinkedTaxInvoiceId()).isEqualTo(42L);
        assertThat(scan.getOcrModel()).isEqualTo("test-ocr");
        assertThat(scan.getSellerId()).isEqualTo(SELLER);
        verify(savePort).save(any(TaxInvoiceScan.class));
    }

    @Test
    @DisplayName("금액이 어긋나면 MISMATCHED — 사유가 남는다")
    void mismatched() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TI-0000000005"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "90000", "9000")));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MISMATCHED);
        assertThat(scan.getReviewNote()).contains("공급가액");
    }

    @Test
    @DisplayName("승인번호가 우리 형식이 아니면 발행분을 조회조차 하지 않고 UNMATCHED")
    void unmatchedWithForeignApprovalNumber() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "20260801-99999999"));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);
        verify(loadInvoicePort, never()).findBySettlementId(anyLong());
    }

    @Test
    @DisplayName("★ 남의 셀러 발행분을 가리켜도 대사되지 않는다 (IDOR)")
    void crossSellerIsUnmatched() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TI-0000000005"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(99L, "100000", "10000")));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);
        assertThat(scan.getLinkedTaxInvoiceId()).isNull();
    }

    @Test
    @DisplayName("★ 같은 파일 재업로드는 기존 스캔을 그대로 돌려주고 AI 를 다시 호출하지 않는다 (멱등·비용)")
    void idempotentReupload() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TI-0000000005"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "100000", "10000")));

        TaxInvoiceScan first = service.extract(command());
        when(loadScanPort.findBySellerIdAndFileHash(SELLER, first.getFileHash()))
                .thenReturn(Optional.of(first));

        TaxInvoiceScan second = service.extract(command());

        assertThat(second).isSameAs(first);
        verify(ocrPort, times(1)).extract(any(), anyString());
        verify(savePort, times(1)).save(any(TaxInvoiceScan.class));
    }

    @Test
    @DisplayName("파일 해시는 내용에서 파생된다 — 같은 내용이면 같은 해시(64자 hex)")
    void fileHashIsContentDerived() {
        when(ocrPort.extract(any(), anyString()))
                .thenReturn(extraction("100000", "10000", "110000", null));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getFileHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("OCR 미구성이면 503 — 저장하지 않는다")
    void ocrNotConfigured() {
        when(ocrPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.extract(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TAX_OCR_UNAVAILABLE);
        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("지원하지 않는 형식·빈 파일은 415/400 으로 막고 AI 를 호출하지 않는다")
    void rejectsUnsupportedUploads() {
        assertThatThrownBy(() -> service.extract(
                new UploadTaxInvoiceScanCommand(SELLER, "invoice.exe", "application/octet-stream", CONTENT)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TAX_SCAN_UNSUPPORTED_FILE);

        assertThatThrownBy(() -> service.extract(
                new UploadTaxInvoiceScanCommand(SELLER, "empty.png", "image/png", new byte[0])))
                .isInstanceOf(BusinessException.class);

        verify(ocrPort, never()).extract(any(), anyString());
    }

    @Test
    @DisplayName("조회 — 없는 스캔은 404 계약")
    void getMissingScan() {
        when(loadScanPort.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.byId(404L)).isEmpty();
        assertThatThrownBy(() -> service.reject(404L, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TAX_INVOICE_SCAN_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자 반려 — REJECTED 로 전이해 저장한다")
    void reject() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", null));
        TaxInvoiceScan scan = service.extract(command());
        when(loadScanPort.findById(1L)).thenReturn(Optional.of(scan));

        TaxInvoiceScan rejected = service.reject(1L, "위조 의심");

        assertThat(rejected.getStatus()).isEqualTo(TaxInvoiceScanStatus.REJECTED);
        assertThat(rejected.getReviewNote()).isEqualTo("위조 의심");
    }

    @Test
    @DisplayName("재대사 — 발행이 뒤늦게 생겼으면 UNMATCHED 가 MATCHED 로 바뀐다")
    void rematch() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TI-0000000005"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.empty());
        TaxInvoiceScan scan = service.extract(command());
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);

        when(loadScanPort.findById(1L)).thenReturn(Optional.of(scan));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "100000", "10000")));

        TaxInvoiceScan rematched = service.rematch(1L);

        assertThat(rematched.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
        assertThat(rematched.getLinkedTaxInvoiceId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("저신뢰 추출은 자동 대사를 하지 않는다 — 믿을 수 없는 값으로 결론을 기록하지 않는다")
    void lowConfidenceSkipsAutoReconciliation() {
        // 종전에는 저신뢰여도 그대로 대사해 UNMATCHED("발행분을 못 찾았다")를 기록했다. 승인번호를
        // 못 읽어서 못 찾은 것을 "없다" 고 단정한 셈이라, 조사 이력에 틀린 결론이 남았다.
        when(ocrPort.extract(CONTENT, "image/png")).thenReturn(new OcrExtraction(
                "101-81-00001", null, WRITTEN, new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("110000"), null, new BigDecimal("0.42"), new BigDecimal("0.42")));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getExtracted().needsReview(new BigDecimal("0.80"))).isTrue();
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.EXTRACTED);
    }

    @Test
    @DisplayName("저신뢰인데 금액이 우연히 맞아도 MATCHED(종결)로 자동 확정하지 않는다")
    void lowConfidenceIsNeverAutoConfirmedIntoTerminalState() {
        // MATCHED 는 종결이라 관리자가 반려조차 할 수 없다. 믿을 수 없는 판독이 되돌릴 수 없는
        // 상태로 굳는 것이 이 도메인에서 가장 비싼 실패다.
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", TaxInvoice.numberFor(5L),
                        "0.95", "0.40"));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "100000", "10000")));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.EXTRACTED);
        assertThat(scan.getStatus().isTerminal()).isFalse();
    }

    @Test
    @DisplayName("금액이 또렷해도 승인번호가 뭉개졌으면 리뷰 대상이다 — 축이 다르면 확신도 다르다")
    void crispAmountsDoNotVouchForTheApprovalNumber() {
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", "TX-2026-0000005",
                        "0.99", "0.35"));

        TaxInvoiceScan scan = service.extract(command());

        assertThat(scan.getExtracted().needsReview(new BigDecimal("0.80"))).isTrue();
        assertThat(scan.getExtracted().amountConfidence()).isEqualByComparingTo("0.99");
        assertThat(scan.getExtracted().approvalNumberConfidence()).isEqualByComparingTo("0.35");
    }

    @Test
    @DisplayName("rematch 는 관리자 결정이라 저신뢰여도 대사한다 — 자동 확정만 막는다")
    void rematchReconcilesEvenWhenLowConfidence() {
        // 자동 경로만 보수적이어야 한다. 사람이 눈으로 보고 누른 재대사까지 막으면 저신뢰 스캔은
        // 영영 EXTRACTED 에 갇혀 반려 외에 길이 없다.
        when(ocrPort.extract(CONTENT, "image/png"))
                .thenReturn(extraction("100000", "10000", "110000", TaxInvoice.numberFor(5L),
                        "0.40", "0.40"));
        TaxInvoiceScan scan = service.extract(command());
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.EXTRACTED);

        when(loadScanPort.findById(1L)).thenReturn(Optional.of(scan));
        when(loadInvoicePort.findBySettlementId(5L)).thenReturn(Optional.of(issued(SELLER, "100000", "10000")));

        TaxInvoiceScan rematched = service.rematch(1L);

        assertThat(rematched.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
    }
}
