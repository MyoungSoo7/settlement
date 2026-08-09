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
import github.lms.lemuel.insurance.application.port.out.RenderProposalSheetPdfPort;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 가입설계서 PDF 렌더러 — iText, 한글 내장 폰트 ({@link ProductDisclosurePdfAdapter} 와 동일 관례).
 *
 * <p>adapter/out/pdf — 커버리지 게이트 제외(통합 검증 대상).
 * 설계서 기재 사항: 피보험자·보험나이·상품·보장금액·납입기간·적용요율·산출 보험료·유효기한.
 */
@Component
public class ProposalSheetPdfAdapter implements RenderProposalSheetPdfPort {

    private static final DeviceRgb COLOR_PRIMARY = new DeviceRgb(15, 76, 129);
    private static final DeviceRgb COLOR_HEADER_BG = new DeviceRgb(240, 246, 252);
    private static final DeviceRgb COLOR_GRAY = new DeviceRgb(107, 114, 128);

    private static final NumberFormat KRW = NumberFormat.getNumberInstance(Locale.KOREA);

    @Override
    public byte[] render(ProposalQuote proposal, ProductSnapshot product) {
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

            doc.add(new Paragraph("보험 가입설계서")
                    .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY)
                    .setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph("설계번호 " + proposal.getProposalId())
                    .setFont(regular).setFontSize(9).setFontColor(COLOR_GRAY)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(20));

            Table table = new Table(UnitValue.createPercentArray(new float[]{30, 70}))
                    .useAllAvailableWidth();
            addRow(table, regular, "피보험자", proposal.getInsuredName()
                    + " (" + (proposal.getInsuredGender() == Gender.M ? "남" : "여")
                    + ", 보험나이 " + proposal.getInsuranceAge() + "세)");
            addRow(table, regular, "상품", product.productName() + " (" + product.productCode() + ")");
            addRow(table, regular, "보장금액", won(proposal.getCoverageAmount()));
            addRow(table, regular, "납입기간", proposal.getPaymentTermYears() + "년");
            addRow(table, regular, "적용요율", proposal.getAppliedRatePerMille().toPlainString()
                    + " (보장 1,000원당, 요율 #" + proposal.getRateTableId() + ")");
            addRow(table, regular, "연 보험료", won(proposal.getAnnualPremium()));
            addRow(table, regular, "설계 유효기한", proposal.getValidUntil().toString());
            doc.add(table);

            doc.add(new Paragraph("안내")
                    .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY).setMarginTop(22));
            doc.add(new Paragraph(
                    "· 본 설계서의 보험료는 산출 시점의 요율 기준이며, 유효기한 경과 후에는 재설계가 필요합니다.\n"
                            + "· 청약 전환 시 보험료·보장금액은 본 설계서의 산출값이 그대로 적용됩니다.\n"
                            + "· 실제 계약 조건은 청약 심사 결과에 따라 달라질 수 있습니다.")
                    .setFont(regular).setFontSize(9).setFixedLeading(16));

            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("가입설계서 PDF 렌더링 실패: " + proposal.getProposalId(), e);
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
