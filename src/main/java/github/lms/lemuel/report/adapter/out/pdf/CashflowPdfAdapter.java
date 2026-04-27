package github.lms.lemuel.report.adapter.out.pdf;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import github.lms.lemuel.report.application.port.out.RenderCashflowReportPdfPort;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.ReconciliationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Cashflow 리포트 PDF 어댑터.
 * <p>SettlementPdfAdapter 와 동일한 색상·폰트·헬퍼 패턴을 따라 시각적 일관성을 유지한다.
 */
@Slf4j
@Component
public class CashflowPdfAdapter implements RenderCashflowReportPdfPort {

    private static final DeviceRgb COLOR_PRIMARY    = new DeviceRgb(30, 64, 175);
    private static final DeviceRgb COLOR_HEADER_BG  = new DeviceRgb(239, 246, 255);
    private static final DeviceRgb COLOR_ROW_ALT    = new DeviceRgb(249, 250, 251);
    private static final DeviceRgb COLOR_TOTAL_BG   = new DeviceRgb(30, 64, 175);
    private static final DeviceRgb COLOR_MATCHED    = new DeviceRgb(22, 163, 74);
    private static final DeviceRgb COLOR_MISMATCH   = new DeviceRgb(220, 38, 38);
    private static final DeviceRgb COLOR_GRAY       = new DeviceRgb(107, 114, 128);
    private static final DeviceRgb COLOR_DIVIDER    = new DeviceRgb(229, 231, 235);

    private static final NumberFormat KRW_FORMAT = NumberFormat.getNumberInstance(Locale.KOREA);
    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public byte[] render(CashflowReport report) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(40, 50, 40, 50);

            PdfFont regular = PdfFontFactory.createFont(
                    "HYGoThic-Medium", "UniKS-UCS2-H", EmbeddingStrategy.PREFER_EMBEDDED);
            PdfFont bold = PdfFontFactory.createFont(
                    "HYSMyeongJo-Medium", "UniKS-UCS2-H", EmbeddingStrategy.PREFER_EMBEDDED);

            renderHeader(doc, regular, bold);
            renderDivider(doc);
            renderPeriodInfo(doc, report, regular, bold);
            renderDivider(doc);
            renderTotals(doc, report, regular, bold);
            renderDivider(doc);
            renderBuckets(doc, report, regular, bold);
            renderDivider(doc);
            renderReconciliation(doc, report.reconciliation(), regular, bold);
            renderDivider(doc);
            renderFooter(doc, regular);

            doc.close();
        } catch (IOException e) {
            log.error("Cashflow PDF 생성 실패 (from={}, to={})", report.from(), report.to(), e);
            throw new CashflowPdfRenderException("Cashflow PDF 생성에 실패했습니다.", e);
        }
        return baos.toByteArray();
    }

    // ── 헤더 ───────────────────────────────────────────────

    private void renderHeader(Document doc, PdfFont regular, PdfFont bold) {
        Table header = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(Border.NO_BORDER);

        Cell left = new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph("LEMUEL")
                        .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY))
                .add(new Paragraph("정산 관리 시스템")
                        .setFont(regular).setFontSize(9).setFontColor(COLOR_GRAY));
        header.addCell(left);

        Cell right = new Cell().setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph("자 금 흐 름 리 포 트")
                        .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY)
                        .setTextAlignment(TextAlignment.RIGHT));
        header.addCell(right);

        doc.add(header);
    }

    // ── 기간 정보 ──────────────────────────────────────────

    private void renderPeriodInfo(Document doc, CashflowReport report,
                                  PdfFont regular, PdfFont bold) {
        Table meta = new Table(UnitValue.createPercentArray(new float[]{25, 35, 20, 20}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(Border.NO_BORDER)
                .setBackgroundColor(COLOR_HEADER_BG)
                .setPadding(8);

        meta.addCell(labelCell("기간 시작", regular));
        meta.addCell(valueCell(report.from().format(DATE_FORMAT), bold));
        meta.addCell(labelCell("기간 종료", regular));
        meta.addCell(valueCell(report.to().format(DATE_FORMAT), bold));

        meta.addCell(labelCell("집계 단위", regular));
        meta.addCell(valueCell(report.granularity().name().toLowerCase(), bold));
        meta.addCell(labelCell("버킷 수", regular));
        meta.addCell(valueCell(String.valueOf(report.buckets().size()), bold));

        doc.add(meta);
    }

    // ── 총계 ───────────────────────────────────────────────

    private void renderTotals(Document doc, CashflowReport report,
                              PdfFont regular, PdfFont bold) {
        doc.add(new Paragraph("기간 총계")
                .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY)
                .setMarginTop(8).setMarginBottom(6));

        Table table = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100));

        table.addHeaderCell(headerCell("항목", bold));
        table.addHeaderCell(headerCell("금액 / 값", bold));

        var t = report.totals();
        addAmountRow(table, "총 결제액 (GMV)", t.gmv(), regular, false, false);
        addAmountRow(table, "환불 총액", t.refundedAmount(), regular, true, true);
        addAmountRow(table, "수수료 총액", t.commissionAmount(), regular, false, false);
        addCountRow(table, "거래 건수", t.transactionCount(), regular, true);

        BigDecimal refundRatePct = t.refundRate()
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        table.addCell(rowLabelCell("환불률", regular, false));
        table.addCell(rowTextCell(refundRatePct.toPlainString() + "%", regular, false));

        // 순 정산액 (강조)
        table.addCell(totalLabelCell("순 정산액", bold));
        table.addCell(totalAmountCell(t.netSettlement(), bold));

        doc.add(table);
    }

    // ── 버킷 ───────────────────────────────────────────────

    private void renderBuckets(Document doc, CashflowReport report,
                               PdfFont regular, PdfFont bold) {
        doc.add(new Paragraph("기간별 세부")
                .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY)
                .setMarginTop(8).setMarginBottom(6));

        if (report.buckets().isEmpty()) {
            doc.add(new Paragraph("해당 기간에 집계할 정산 내역이 없습니다.")
                    .setFont(regular).setFontSize(10).setFontColor(COLOR_GRAY));
            return;
        }

        Table table = new Table(UnitValue.createPercentArray(new float[]{20, 12, 18, 17, 15, 18}))
                .setWidth(UnitValue.createPercentValue(100));

        table.addHeaderCell(headerCell("버킷", bold));
        table.addHeaderCell(headerCell("건수", bold));
        table.addHeaderCell(headerCell("GMV", bold));
        table.addHeaderCell(headerCell("환불", bold));
        table.addHeaderCell(headerCell("수수료", bold));
        table.addHeaderCell(headerCell("순정산", bold));

        int i = 0;
        for (CashflowBucket b : report.buckets()) {
            boolean alt = (i++ % 2) == 1;
            table.addCell(rowLabelCell(b.bucket().format(DATE_FORMAT), regular, alt));
            table.addCell(rowNumberCell(String.valueOf(b.transactionCount()), regular, alt));
            table.addCell(rowAmountCell(b.gmv(), false, alt));
            boolean hasRefund = b.refundedAmount().compareTo(BigDecimal.ZERO) > 0;
            table.addCell(hasRefund
                    ? rowAmountCell(b.refundedAmount(), true, alt)
                    : rowTextCell("—", regular, alt));
            table.addCell(rowAmountCell(b.commissionAmount(), false, alt));
            table.addCell(rowAmountCell(b.netSettlement(), false, alt));
        }

        doc.add(table);
    }

    // ── 대사 섹션 ──────────────────────────────────────────

    private void renderReconciliation(Document doc, CashflowReconciliation recon,
                                      PdfFont regular, PdfFont bold) {
        doc.add(new Paragraph("숫자 검증 (Reconciliation)")
                .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY)
                .setMarginTop(8).setMarginBottom(6));

        // 상태 배지
        DeviceRgb color = recon.matched() ? COLOR_MATCHED : COLOR_MISMATCH;
        String badge = recon.matched()
                ? "● 모든 불변식 통과 (" + recon.checksRun() + "/" + recon.checksRun() + ")"
                : "● 대사 실패 — " + recon.mismatches().size() + "/" + recon.checksRun() + " 불변식 깨짐";
        doc.add(new Paragraph(badge)
                .setFont(bold).setFontSize(11).setFontColor(color)
                .setMarginBottom(6));

        if (recon.checks().isEmpty()) {
            doc.add(new Paragraph("실행된 대사 체크가 없습니다.")
                    .setFont(regular).setFontSize(10).setFontColor(COLOR_GRAY));
            return;
        }

        Table table = new Table(UnitValue.createPercentArray(new float[]{38, 10, 17, 17, 18}))
                .setWidth(UnitValue.createPercentValue(100));

        table.addHeaderCell(headerCell("불변식", bold));
        table.addHeaderCell(headerCell("결과", bold));
        table.addHeaderCell(headerCell("기대값", bold));
        table.addHeaderCell(headerCell("실제값", bold));
        table.addHeaderCell(headerCell("차이", bold));

        int i = 0;
        for (ReconciliationCheck c : recon.checks()) {
            boolean alt = (i++ % 2) == 1;
            table.addCell(rowLabelCell(c.name(), regular, alt));
            String mark = c.passed() ? "OK" : "FAIL";
            DeviceRgb markColor = c.passed() ? COLOR_MATCHED : COLOR_MISMATCH;
            table.addCell(rowStatusCell(mark, bold, markColor, alt));
            table.addCell(rowAmountCell(c.expected(), false, alt));
            table.addCell(rowAmountCell(c.actual(), false, alt));
            table.addCell(rowAmountCell(c.discrepancy(),
                    c.discrepancy().compareTo(BigDecimal.ZERO) != 0, alt));
        }
        doc.add(table);

        // mismatches 가 있으면 세부를 나열
        if (!recon.mismatches().isEmpty()) {
            doc.add(new Paragraph("실패 세부")
                    .setFont(bold).setFontSize(10).setFontColor(COLOR_MISMATCH)
                    .setMarginTop(8).setMarginBottom(4));
            for (ReconciliationCheck m : recon.mismatches()) {
                doc.add(new Paragraph("• " + m.name() + " → " + m.detail())
                        .setFont(regular).setFontSize(9).setFontColor(COLOR_MISMATCH)
                        .setPaddingLeft(8)
                        .setBorderLeft(new SolidBorder(COLOR_MISMATCH, 2)));
            }
        }
    }

    // ── 푸터 ───────────────────────────────────────────────

    private void renderFooter(Document doc, PdfFont regular) {
        doc.add(new Paragraph("본 리포트는 전자문서로 자동 생성되었습니다.\n"
                + "생성 일시: " + LocalDateTime.now().format(DT_FORMAT))
                .setFont(regular).setFontSize(8).setFontColor(COLOR_GRAY)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(4));
    }

    // ── 구분선 ─────────────────────────────────────────────

    private void renderDivider(Document doc) {
        doc.add(new Paragraph("")
                .setMarginTop(8).setMarginBottom(8)
                .setBorderBottom(new SolidBorder(COLOR_DIVIDER, 1)));
    }

    // ── 행 헬퍼 ────────────────────────────────────────────

    private void addAmountRow(Table table, String label, BigDecimal value,
                              PdfFont regular, boolean alt, boolean negative) {
        table.addCell(rowLabelCell(label, regular, alt));
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
            table.addCell(rowTextCell("—", regular, alt));
        } else {
            table.addCell(rowAmountCell(value, negative, alt));
        }
    }

    private void addCountRow(Table table, String label, long value,
                             PdfFont regular, boolean alt) {
        table.addCell(rowLabelCell(label, regular, alt));
        table.addCell(rowTextCell(KRW_FORMAT.format(value) + " 건", regular, alt));
    }

    // ── 셀 헬퍼 (SettlementPdfAdapter 와 동일 스타일) ────

    private Cell labelCell(String text, PdfFont font) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFont(font).setFontSize(9).setFontColor(COLOR_GRAY))
                .setPadding(4);
    }

    private Cell valueCell(String text, PdfFont font) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFont(font).setFontSize(10))
                .setPadding(4);
    }

    private Cell headerCell(String text, PdfFont font) {
        return new Cell()
                .setBackgroundColor(COLOR_PRIMARY)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFont(font).setFontSize(10)
                        .setFontColor(ColorConstants.WHITE))
                .setPadding(6);
    }

    private Cell rowLabelCell(String text, PdfFont font, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(9))
                .setPadding(5);
    }

    private Cell rowAmountCell(BigDecimal amount, boolean negative, boolean alt) {
        String text = "₩" + KRW_FORMAT.format(amount);
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFontSize(9)
                        .setFontColor(negative ? COLOR_MISMATCH : ColorConstants.BLACK)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(5);
    }

    private Cell rowNumberCell(String text, PdfFont font, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(9)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(5);
    }

    private Cell rowTextCell(String text, PdfFont font, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(9)
                        .setFontColor(COLOR_GRAY)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(5);
    }

    private Cell rowStatusCell(String text, PdfFont font, DeviceRgb color, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(9)
                        .setFontColor(color)
                        .setTextAlignment(TextAlignment.CENTER))
                .setPadding(5);
    }

    private Cell totalLabelCell(String text, PdfFont font) {
        return new Cell()
                .setBackgroundColor(COLOR_TOTAL_BG)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFont(font).setFontSize(11)
                        .setFontColor(ColorConstants.WHITE))
                .setPadding(8);
    }

    private Cell totalAmountCell(BigDecimal amount, PdfFont font) {
        return new Cell()
                .setBackgroundColor(COLOR_TOTAL_BG)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph("₩" + KRW_FORMAT.format(amount))
                        .setFont(font).setFontSize(13)
                        .setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(8);
    }
}
