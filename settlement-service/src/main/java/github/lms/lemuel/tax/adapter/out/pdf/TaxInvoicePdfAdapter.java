package github.lms.lemuel.tax.adapter.out.pdf;

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
import github.lms.lemuel.common.pdf.GhostscriptService;
import github.lms.lemuel.tax.application.port.out.RenderTaxInvoicePdfPort;
import github.lms.lemuel.tax.domain.TaxInvoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 세금계산서 PDF 어댑터 — iText 로 계산서를 렌더링하고, 가능하면 GhostscriptService 로 PDF/A(장기 보존)로
 * 아카이빙한다. gs 바이너리가 없거나(테스트/개발) 변환이 실패하면 iText 원본 PDF 바이트로 안전 폴백한다.
 *
 * <p>adapter/out/pdf — 커버리지 게이트 제외(통합 검증 대상).
 */
@Slf4j
@Component
public class TaxInvoicePdfAdapter implements RenderTaxInvoicePdfPort {

    private static final DeviceRgb COLOR_PRIMARY = new DeviceRgb(30, 64, 175);
    private static final DeviceRgb COLOR_HEADER_BG = new DeviceRgb(239, 246, 255);
    private static final DeviceRgb COLOR_GRAY = new DeviceRgb(107, 114, 128);
    private static final DeviceRgb COLOR_TOTAL_BG = new DeviceRgb(30, 64, 175);
    private static final DeviceRgb COLOR_DIVIDER = new DeviceRgb(229, 231, 235);

    private static final NumberFormat KRW_FORMAT = NumberFormat.getNumberInstance(Locale.KOREA);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GhostscriptService ghostscriptService;

    public TaxInvoicePdfAdapter(GhostscriptService ghostscriptService) {
        this.ghostscriptService = ghostscriptService;
    }

    @Override
    public byte[] render(TaxInvoice invoice) {
        byte[] pdf = renderItext(invoice);
        return archiveAsPdfA(pdf, invoice.getIssueNumber());
    }

    private byte[] renderItext(TaxInvoice invoice) {
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

            doc.add(new Paragraph("세 금 계 산 서")
                    .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY)
                    .setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph("공급자: LEMUEL 정산 플랫폼   ·   공급받는자: 셀러 #" + invoice.getSellerId())
                    .setFont(regular).setFontSize(9).setFontColor(COLOR_GRAY)
                    .setTextAlignment(TextAlignment.CENTER));
            renderDivider(doc);

            Table meta = new Table(UnitValue.createPercentArray(new float[]{30, 30, 20, 20}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setBorder(Border.NO_BORDER).setBackgroundColor(COLOR_HEADER_BG).setPadding(8);
            meta.addCell(labelCell("발행번호", regular));
            meta.addCell(valueCell(invoice.getIssueNumber(), bold));
            meta.addCell(labelCell("발행일", regular));
            meta.addCell(valueCell(invoice.getIssueDate().format(DATE_FORMAT), bold));
            meta.addCell(labelCell("정산번호", regular));
            meta.addCell(valueCell(String.valueOf(invoice.getSettlementId()), bold));
            meta.addCell(labelCell("", regular));
            meta.addCell(valueCell("", bold));
            doc.add(meta);
            renderDivider(doc);

            Table table = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                    .setWidth(UnitValue.createPercentValue(100));
            table.addCell(rowLabelCell("공급가액 (수수료)", regular));
            table.addCell(rowAmountCell(invoice.getSupplyAmount()));
            table.addCell(rowLabelCell("세액 (부가세 10%)", regular));
            table.addCell(rowAmountCell(invoice.getTaxAmount()));
            table.addCell(totalLabelCell("합계", bold));
            table.addCell(totalAmountCell(invoice.getTotalAmount(), bold));
            doc.add(table);

            renderDivider(doc);
            doc.add(new Paragraph("본 세금계산서는 전자문서로 자동 생성되었습니다 (ADR 0029, MVP — e-Tax 실연동 별도).")
                    .setFont(regular).setFontSize(8).setFontColor(COLOR_GRAY)
                    .setTextAlignment(TextAlignment.CENTER));

            doc.close();
        } catch (IOException e) {
            log.error("세금계산서 PDF 생성 실패 (settlementId={})", invoice.getSettlementId(), e);
            throw new TaxInvoicePdfRenderException("세금계산서 PDF 생성에 실패했습니다.", e);
        }
        return baos.toByteArray();
    }

    /** gs 로 PDF/A 아카이빙 시도 — 실패(바이너리 부재 등) 시 원본 PDF 로 폴백. */
    private byte[] archiveAsPdfA(byte[] source, String issueNumber) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("tax-invoice-" + issueNumber + "-", ".pdf");
            output = Files.createTempFile("tax-invoice-" + issueNumber + "-pdfa-", ".pdf");
            Files.write(input, source);
            ghostscriptService.convertToPdfA(input, output);
            return Files.readAllBytes(output);
        } catch (IOException | RuntimeException e) {
            // GhostscriptException 은 IOException 하위 — gs 부재/변환 실패 시 원본 PDF 로 폴백.
            log.warn("PDF/A 아카이빙 폴백(원본 PDF 반환): {}", e.getMessage());
            return source;
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 무시(운영 임시 디렉토리 정리 정책이 회수).
        }
    }

    private void renderDivider(Document doc) {
        doc.add(new Paragraph("").setMarginTop(8).setMarginBottom(8)
                .setBorderBottom(new SolidBorder(COLOR_DIVIDER, 1)));
    }

    private Cell labelCell(String text, PdfFont font) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(9).setFontColor(COLOR_GRAY)).setPadding(4);
    }

    private Cell valueCell(String text, PdfFont font) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(10)).setPadding(4);
    }

    private Cell rowLabelCell(String text, PdfFont font) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(10)).setPadding(6);
    }

    private Cell rowAmountCell(BigDecimal amount) {
        return new Cell().setBorder(Border.NO_BORDER)
                .add(new Paragraph("₩" + KRW_FORMAT.format(amount)).setFontSize(10)
                        .setFontColor(ColorConstants.BLACK).setTextAlignment(TextAlignment.RIGHT)).setPadding(6);
    }

    private Cell totalLabelCell(String text, PdfFont font) {
        return new Cell().setBackgroundColor(COLOR_TOTAL_BG).setBorder(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(11).setFontColor(ColorConstants.WHITE))
                .setPadding(8);
    }

    private Cell totalAmountCell(BigDecimal amount, PdfFont font) {
        return new Cell().setBackgroundColor(COLOR_TOTAL_BG).setBorder(Border.NO_BORDER)
                .add(new Paragraph("₩" + KRW_FORMAT.format(amount)).setFont(font).setFontSize(13)
                        .setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.RIGHT)).setPadding(8);
    }
}
