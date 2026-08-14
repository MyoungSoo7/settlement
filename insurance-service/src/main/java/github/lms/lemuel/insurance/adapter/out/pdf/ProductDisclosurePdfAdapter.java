package github.lms.lemuel.insurance.adapter.out.pdf;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderDisclosurePdfPort;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 대면 상품설명서 PDF 렌더러 — iText, 한글 내장 폰트 (settlement TaxInvoicePdfAdapter 와 동일 관례).
 *
 * <p>adapter/out/pdf — 커버리지 게이트 제외(통합 검증 대상).
 * 설명서 필수 기재 사항: 상품명·유형·연 보험료·보장금액·소비자 안내 문구(청약철회·실효 규정).
 */
@Component
public class ProductDisclosurePdfAdapter implements RenderDisclosurePdfPort {

    private static final DeviceRgb COLOR_PRIMARY = new DeviceRgb(15, 76, 129);
    private static final DeviceRgb COLOR_HEADER_BG = new DeviceRgb(240, 246, 252);
    private static final DeviceRgb COLOR_GRAY = new DeviceRgb(107, 114, 128);

    private static final NumberFormat KRW = NumberFormat.getNumberInstance(Locale.KOREA);

    @Override
    public byte[] render(ProductSnapshot product) {
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

            doc.add(new Paragraph("보험 상품설명서")
                    .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY)
                    .setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph("본 설명서는 보험업법에 따른 대면 판매 상품 설명 자료입니다.")
                    .setFont(regular).setFontSize(9).setFontColor(COLOR_GRAY)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(20));

            Table table = new Table(UnitValue.createPercentArray(new float[]{30, 70}))
                    .useAllAvailableWidth();
            addRow(table, regular, "상품코드", product.productCode());
            addRow(table, regular, "상품명", product.productName());
            addRow(table, regular, "상품유형", product.productType());
            addRow(table, regular, "연 보험료", won(product.annualPremium()));
            addRow(table, regular, "보장금액", won(product.coverageAmount()));
            if (product.insurerCode() != null) {
                addRow(table, regular, "원수사", product.insurerCode());
            }
            doc.add(table);

            doc.add(new Paragraph("소비자 안내")
                    .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY).setMarginTop(22));
            doc.add(new Paragraph(
                    "· 청약철회: 보험증권 수령일부터 15일 이내(청약일부터 30일 한도)에 청약을 철회할 수 있습니다.\n"
                            + "· 계약 실효: 보험료가 2회 연속 미납되면 계약이 실효되며, 실효일부터 일정 기간 내 "
                            + "연체보험료를 전액 납입하면 부활할 수 있습니다.\n"
                            + "· 이 설명서의 교부 사실과 문서 원본 해시는 완전판매 증빙으로 기록·보존됩니다.")
                    .setFont(regular).setFontSize(9).setFixedLeading(16));

            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("상품설명서 PDF 렌더링 실패: " + product.productCode(), e);
        }
    }

    private void addRow(Table table, PdfFont font, String label, String value) {
        table.addCell(new Cell().add(new Paragraph(label).setFont(font).setFontSize(10))
                .setBackgroundColor(COLOR_HEADER_BG).setBorder(Border.NO_BORDER).setPadding(8));
        table.addCell(new Cell().add(new Paragraph(value).setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER).setPadding(8));
    }

    private static String won(BigDecimal amount) {
        return KRW.format(amount) + " 원";
    }
}
