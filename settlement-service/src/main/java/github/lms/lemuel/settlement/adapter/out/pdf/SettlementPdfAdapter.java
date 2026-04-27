package github.lms.lemuel.settlement.adapter.out.pdf;

import com.itextpdf.io.font.PdfEncodings;
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
import github.lms.lemuel.settlement.application.port.out.SettlementPdfPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Slf4j
@Component
public class SettlementPdfAdapter implements SettlementPdfPort {

    // ── 색상 ────────────────────────────────────────────────
    private static final DeviceRgb COLOR_PRIMARY    = new DeviceRgb(30, 64, 175);   // 인디고
    private static final DeviceRgb COLOR_HEADER_BG  = new DeviceRgb(239, 246, 255); // 연한 파랑
    private static final DeviceRgb COLOR_ROW_ALT    = new DeviceRgb(249, 250, 251); // 연한 회색
    private static final DeviceRgb COLOR_TOTAL_BG   = new DeviceRgb(30, 64, 175);   // 합계 행 배경
    private static final DeviceRgb COLOR_DONE       = new DeviceRgb(22, 163, 74);   // 초록
    private static final DeviceRgb COLOR_FAILED     = new DeviceRgb(220, 38, 38);   // 빨강
    private static final DeviceRgb COLOR_PROCESSING = new DeviceRgb(234, 179, 8);   // 노랑
    private static final DeviceRgb COLOR_GRAY       = new DeviceRgb(107, 114, 128); // 회색

    // ── 포매터 ──────────────────────────────────────────────
    private static final NumberFormat KRW_FORMAT = NumberFormat.getNumberInstance(Locale.KOREA);
    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");

    @Override
    public byte[] render(Settlement settlement) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf, PageSize.A4);
            document.setMargins(40, 50, 40, 50);

            // 한국어 폰트 (font-asian 모듈의 내장 폰트)
            PdfFont fontRegular = PdfFontFactory.createFont(
                    "HYGoThic-Medium", "UniKS-UCS2-H", EmbeddingStrategy.PREFER_EMBEDDED);
            PdfFont fontBold = PdfFontFactory.createFont(
                    "HYSMyeongJo-Medium", "UniKS-UCS2-H", EmbeddingStrategy.PREFER_EMBEDDED);

            renderHeader(document, settlement, fontRegular, fontBold);
            renderDivider(document);
            renderMetaInfo(document, settlement, fontRegular, fontBold);
            renderDivider(document);
            renderAmountTable(document, settlement, fontRegular, fontBold);

            if (settlement.getFailureReason() != null && !settlement.getFailureReason().isBlank()) {
                renderFailureReason(document, settlement, fontRegular);
            }

            renderDivider(document);
            renderFooter(document, fontRegular);

            document.close();

        } catch (IOException e) {
            log.error("정산서 PDF 생성 실패 (settlementId={})", settlement.getId(), e);
            throw new PdfRenderException("정산서 PDF 생성에 실패했습니다.", e);
        }

        return baos.toByteArray();
    }

    // ── 헤더: 서비스명 + 제목 ───────────────────────────────

    private void renderHeader(Document doc, Settlement settlement,
                              PdfFont regular, PdfFont bold) {
        Table header = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(Border.NO_BORDER);

        // 왼쪽: 서비스명
        Cell left = new Cell()
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph("LEMUEL")
                        .setFont(bold).setFontSize(22).setFontColor(COLOR_PRIMARY))
                .add(new Paragraph("정산 관리 시스템")
                        .setFont(regular).setFontSize(9).setFontColor(COLOR_GRAY));
        header.addCell(left);

        // 오른쪽: 문서 제목 + 상태 배지
        Cell right = new Cell()
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph("정 산 서")
                        .setFont(bold).setFontSize(24).setFontColor(COLOR_PRIMARY)
                        .setTextAlignment(TextAlignment.RIGHT))
                .add(statusBadge(settlement.getStatus(), regular));

        header.addCell(right);
        doc.add(header);
    }

    // ── 정산 기본 정보 ──────────────────────────────────────

    private void renderMetaInfo(Document doc, Settlement settlement,
                                PdfFont regular, PdfFont bold) {
        Table meta = new Table(UnitValue.createPercentArray(new float[]{25, 35, 20, 20}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(Border.NO_BORDER)
                .setBackgroundColor(COLOR_HEADER_BG)
                .setPadding(8);

        addMetaRow(meta, "정산 번호", "#" + settlement.getId(),
                   "결제 번호", "#" + settlement.getPaymentId(), regular, bold);
        addMetaRow(meta, "주문 번호", "#" + settlement.getOrderId(),
                   "정산일",
                   settlement.getSettlementDate() != null
                           ? settlement.getSettlementDate().format(DATE_FORMAT) : "-",
                   regular, bold);

        if (settlement.getConfirmedAt() != null) {
            addMetaRow(meta, "확정 일시",
                       settlement.getConfirmedAt().format(DT_FORMAT),
                       "", "", regular, bold);
        }

        doc.add(meta);
    }

    private void addMetaRow(Table table,
                            String label1, String value1,
                            String label2, String value2,
                            PdfFont regular, PdfFont bold) {
        table.addCell(labelCell(label1, regular));
        table.addCell(valueCell(value1, bold));
        table.addCell(labelCell(label2, regular));
        table.addCell(valueCell(value2, bold));
    }

    // ── 금액 내역 테이블 ────────────────────────────────────

    private void renderAmountTable(Document doc, Settlement settlement,
                                   PdfFont regular, PdfFont bold) {
        doc.add(new Paragraph("금액 내역")
                .setFont(bold).setFontSize(12).setFontColor(COLOR_PRIMARY)
                .setMarginTop(12).setMarginBottom(6));

        Table table = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100));

        // 테이블 헤더
        table.addHeaderCell(headerCell("항목", bold));
        table.addHeaderCell(headerCell("금액", bold));

        // 결제 금액
        table.addCell(rowLabelCell("결제 금액", regular, false));
        table.addCell(rowAmountCell(settlement.getPaymentAmount(), false, false));

        // 환불 금액 (0이면 '-' 표시)
        boolean hasRefund = settlement.getRefundedAmount()
                .compareTo(BigDecimal.ZERO) > 0;
        table.addCell(rowLabelCell("환불 금액", regular, true));
        table.addCell(hasRefund
                ? rowAmountCell(settlement.getRefundedAmount(), true, true)
                : rowTextCell("—", regular, true));

        // 수수료
        table.addCell(rowLabelCell("수수료 (3%)", regular, false));
        table.addCell(rowAmountCell(settlement.getCommission(), true, false));

        // 실 지급액 (강조)
        table.addCell(totalLabelCell("실 지급액", bold));
        table.addCell(totalAmountCell(settlement.getNetAmount(), bold));

        doc.add(table);
    }

    // ── 실패 사유 ───────────────────────────────────────────

    private void renderFailureReason(Document doc, Settlement settlement, PdfFont regular) {
        doc.add(new Paragraph("⚠ 실패 사유:  " + settlement.getFailureReason())
                .setFont(regular).setFontSize(10).setFontColor(COLOR_FAILED)
                .setMarginTop(10)
                .setPaddingLeft(8)
                .setBorderLeft(new SolidBorder(COLOR_FAILED, 3)));
    }

    // ── 푸터 ────────────────────────────────────────────────

    private void renderFooter(Document doc, PdfFont regular) {
        doc.add(new Paragraph("본 정산서는 전자문서로 자동 생성되었습니다.\n"
                + "생성 일시: " + LocalDateTime.now().format(DT_FORMAT))
                .setFont(regular).setFontSize(8).setFontColor(COLOR_GRAY)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(4));
    }

    // ── 구분선 ──────────────────────────────────────────────

    private void renderDivider(Document doc) {
        doc.add(new Paragraph("")
                .setMarginTop(8).setMarginBottom(8)
                .setBorderBottom(new SolidBorder(new DeviceRgb(229, 231, 235), 1)));
    }

    // ── 셀 헬퍼 ────────────────────────────────────────────

    private Paragraph statusBadge(SettlementStatus status, PdfFont font) {
        DeviceRgb color = switch (status) {
            case DONE -> COLOR_DONE;
            case FAILED, CANCELED -> COLOR_FAILED;
            case PROCESSING -> COLOR_PROCESSING;
            case REQUESTED -> COLOR_GRAY;
        };
        String label = switch (status) {
            case DONE -> "완료";
            case FAILED -> "실패";
            case CANCELED -> "취소";
            case PROCESSING -> "처리 중";
            case REQUESTED -> "요청됨";
        };
        return new Paragraph("● " + label)
                .setFont(font).setFontSize(10).setFontColor(color)
                .setTextAlignment(TextAlignment.RIGHT);
    }

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
                .setPadding(8);
    }

    private Cell rowLabelCell(String text, PdfFont font, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorderTop(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(10))
                .setPadding(8);
    }

    private Cell rowAmountCell(BigDecimal amount, boolean negative, boolean alt) {
        String text = (negative ? "(₩" : "₩") + KRW_FORMAT.format(amount) + (negative ? ")" : "");
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorderTop(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFontSize(10)
                        .setFontColor(negative ? COLOR_FAILED : ColorConstants.BLACK)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(8);
    }

    private Cell rowTextCell(String text, PdfFont font, boolean alt) {
        return new Cell()
                .setBackgroundColor(alt ? COLOR_ROW_ALT : ColorConstants.WHITE)
                .setBorderTop(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .add(new Paragraph(text).setFont(font).setFontSize(10)
                        .setFontColor(COLOR_GRAY)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(8);
    }

    private Cell totalLabelCell(String text, PdfFont font) {
        return new Cell()
                .setBackgroundColor(COLOR_TOTAL_BG)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph(text)
                        .setFont(font).setFontSize(11)
                        .setFontColor(ColorConstants.WHITE))
                .setPadding(10);
    }

    private Cell totalAmountCell(BigDecimal amount, PdfFont font) {
        return new Cell()
                .setBackgroundColor(COLOR_TOTAL_BG)
                .setBorder(Border.NO_BORDER)
                .add(new Paragraph("₩" + KRW_FORMAT.format(amount))
                        .setFont(font).setFontSize(13)
                        .setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setPadding(10);
    }
}