package github.lms.lemuel.tax.adapter.out.ocr;

import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 텍스트 레이어 파서 어댑터 — AI 키 없이도 PoC 전 구간(업로드→대사)을 돌릴 수 있게 하는 기본 구현.
 *
 * <p>이 어댑터는 "AI 인 척" 하지 않는다. 결정적 파싱이므로 신뢰도는 항상 1.00 이고, 이미지·PDF 처럼
 * 텍스트 레이어가 없는 입력은 <b>지어내지 않고</b> 503 으로 끊어 AI 프로바이더 설정을 요구한다.
 */
class TextLayerTaxInvoiceOcrAdapterTest {

    private final TextLayerTaxInvoiceOcrAdapter adapter = new TextLayerTaxInvoiceOcrAdapter();

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final String SAMPLE = """
            전자세금계산서
            공급자 등록번호: 101-81-00001
            공급받는자 등록번호: 220-81-00001
            작성일자: 2026-08-01
            공급가액: 1,000,000
            세액: 100,000
            합계금액: 1,100,000
            승인번호: TI-0000000005
            """;

    @Test
    @DisplayName("라벨에서 필드를 읽어낸다 — 콤마·하이픈 정규화 포함")
    void parsesLabels() {
        OcrExtraction extraction = adapter.extract(utf8(SAMPLE), "text/plain");

        assertThat(extraction.supplierBusinessNo()).isEqualTo("101-81-00001");
        assertThat(extraction.buyerBusinessNo()).isEqualTo("220-81-00001");
        assertThat(extraction.writtenDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000000");
        assertThat(extraction.taxAmount()).isEqualByComparingTo("100000");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("1100000");
        assertThat(extraction.approvalNumber()).isEqualTo("TI-0000000005");
    }

    @Test
    @DisplayName("결정적 파서이므로 신뢰도는 1.00 이다 (모델 추정치가 아니다)")
    void deterministicConfidence() {
        assertThat(adapter.extract(utf8(SAMPLE), "text/plain").amountConfidence())
                .isEqualByComparingTo("1.00");
        assertThat(adapter.extract(utf8(SAMPLE), "text/plain").approvalNumberConfidence())
                .isEqualByComparingTo("1.00");
        assertThat(adapter.isConfigured()).isTrue();
        assertThat(adapter.modelName()).isEqualTo("text-layer-v1");
    }

    @Test
    @DisplayName("전각 콜론·공백 변형·날짜 구분자(.)도 읽는다")
    void toleratesFormatting() {
        String variant = """
            공급자 등록번호 ： 101-81-00001
            작성일자 ： 2026.08.01
            공급  가액 ：  1000000
            세  액 ： 100000
            총액 ： 1100000
            """;

        OcrExtraction extraction = adapter.extract(utf8(variant), "text/plain");

        assertThat(extraction.writtenDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000000");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("1100000");
        assertThat(extraction.approvalNumber()).isNull();       // 없는 필드는 지어내지 않는다
        assertThat(extraction.buyerBusinessNo()).isNull();
    }

    @Test
    @DisplayName("★ 이미지·PDF 는 이 어댑터가 처리하지 못한다 — 지어내지 않고 503")
    void refusesBinaryFormats() {
        assertThatThrownBy(() -> adapter.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("app.tax.ocr.provider");

        assertThatThrownBy(() -> adapter.extract(new byte[]{1, 2, 3}, "application/pdf"))
                .isInstanceOf(TaxOcrUnavailableException.class);
    }

    @Test
    @DisplayName("필수 금액·작성일자를 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void missingRequiredFields() {
        assertThatThrownBy(() -> adapter.extract(utf8("공급자 등록번호: 101-81-00001\n"), "text/plain"))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("읽지 못했습니다");

        String noDate = """
                공급가액: 1,000
                세액: 100
                합계금액: 1,100
                """;
        assertThatThrownBy(() -> adapter.extract(utf8(noDate), "text/plain"))
                .isInstanceOf(TaxOcrUnavailableException.class);
    }

    @Test
    @DisplayName("빈 입력도 503")
    void emptyInput() {
        assertThatThrownBy(() -> adapter.extract(new byte[0], "text/plain"))
                .isInstanceOf(TaxOcrUnavailableException.class);
        assertThatThrownBy(() -> adapter.extract(null, "text/plain"))
                .isInstanceOf(TaxOcrUnavailableException.class);
    }
}
