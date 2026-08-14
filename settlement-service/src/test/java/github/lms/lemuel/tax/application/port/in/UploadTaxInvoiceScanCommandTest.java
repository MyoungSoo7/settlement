package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase.UploadTaxInvoiceScanCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스캔본 업로드 명령의 동치성 — 파일 본문은 참조가 아니라 <b>내용</b>으로 비교한다.
 *
 * <p>레코드 기본 구현은 {@code byte[]} 를 참조 동일성으로 비교한다. 같은 파일을 두 번 읽어 만든 명령이
 * 서로 다른 것으로 판정되면 "같은 파일 재업로드는 새 추출 없이 기존 스캔 반환" 계약이 조용히 깨진다.
 */
class UploadTaxInvoiceScanCommandTest {

    private static UploadTaxInvoiceScanCommand command(byte[] content) {
        return new UploadTaxInvoiceScanCommand(1L, "invoice.pdf", "application/pdf", content);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("내용이 같으면 배열 인스턴스가 달라도 같은 명령이다 (equals·hashCode)")
    void equalsByContent() {
        UploadTaxInvoiceScanCommand one = command(bytes("scan-bytes"));
        UploadTaxInvoiceScanCommand other = command(bytes("scan-bytes"));

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    @DisplayName("본문이나 셀러가 다르면 다른 명령이다")
    void differsByContentOrSeller() {
        assertThat(command(bytes("a"))).isNotEqualTo(command(bytes("b")));
        assertThat(command(bytes("a")))
                .isNotEqualTo(new UploadTaxInvoiceScanCommand(2L, "invoice.pdf", "application/pdf", bytes("a")));
        // 타입이 다른 값과는 assertThat(...).isNotEqualTo 대신 equals 직접 호출로 본다
        // — 서로 다른 타입을 비교하는 어서션은 "항상 통과"라 검증력이 없다는 지적을 받는다(java:S5845).
        assertThat(command(bytes("a")).equals("레코드가 아닌 것")).isFalse();
    }

    @Test
    @DisplayName("toString 은 파일 본문 대신 길이만 남긴다 — 바이트가 로그로 새지 않게")
    void toStringHidesContent() {
        assertThat(command(bytes("secret")).toString())
                .contains("contentBytes=6")
                .doesNotContain("secret");
    }
}
