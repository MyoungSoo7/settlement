package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.DeliveredDisclosure;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.RecordDeliveryCommand;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase.RenderedDisclosure;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderDisclosurePdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveDisclosureDeliveryPort;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상품설명서 렌더링·교부 서비스 테스트 — 서버 계산 해시 + 판매종료 상품 차단이 핵심.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductDisclosureService — 상품설명서 렌더링·교부")
class ProductDisclosureServiceTest {

    private static final byte[] PDF = "fake-pdf-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock LoadInsuranceProductPort loadProductPort;
    @Mock RenderDisclosurePdfPort renderPdfPort;
    @Mock SaveDisclosureDeliveryPort saveDeliveryPort;
    @Mock AuditLogger auditLogger;

    private ProductDisclosureService service() {
        return new ProductDisclosureService(loadProductPort, renderPdfPort, saveDeliveryPort, auditLogger);
    }

    private static ProductSnapshot product(boolean active) {
        return new ProductSnapshot("PROD-1", "레무엘 종신보험", "LIFE",
                new BigDecimal("1200000.00"), new BigDecimal("100000000.00"),
                new BigDecimal("0.035000"), "INS-A", active);
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("렌더링 — 반환 해시는 반환 PDF 바이트의 SHA-256 과 정확히 일치한다")
    void renderReturnsHashOfExactBytes() throws Exception {
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product(true)));
        when(renderPdfPort.render(any())).thenReturn(PDF);

        RenderedDisclosure rendered = service().render("PROD-1");

        assertThat(rendered.pdf()).isEqualTo(PDF);
        assertThat(rendered.sha256()).isEqualTo(sha256Of(PDF));
        assertThat(rendered.productName()).isEqualTo("레무엘 종신보험");
    }

    @Test
    @DisplayName("없는 상품은 404 동형 예외")
    void renderRejectsUnknownProduct() {
        when(loadProductPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().render("NOPE"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("판매 종료(inactive) 상품은 신규 교부 대상이 아니다 — 404 동형")
    void renderRejectsInactiveProduct() {
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product(false)));

        assertThatThrownBy(() -> service().render("PROD-1"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("교부 — 서버가 렌더링한 바이트의 해시가 증빙으로 저장되고, 같은 바이트가 반환된다")
    void recordStoresServerComputedHashAndReturnsSameBytes() throws Exception {
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product(true)));
        when(renderPdfPort.render(any())).thenReturn(PDF);
        when(saveDeliveryPort.save(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));  // 저장 결과 = 입력 그대로

        DeliveredDisclosure delivered = service().record(new RecordDeliveryCommand(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "홍길동"));

        assertThat(delivered.pdf()).isEqualTo(PDF);
        assertThat(delivered.delivery().getDocumentSha256()).isEqualTo(sha256Of(PDF));

        ArgumentCaptor<DisclosureDelivery> saved = ArgumentCaptor.forClass(DisclosureDelivery.class);
        verify(saveDeliveryPort).save(saved.capture(), eq(product(true)));
        assertThat(saved.getValue().getDocumentSha256()).isEqualTo(sha256Of(PDF));
        verify(auditLogger).record(any(), eq("DisclosureDelivery"), any(), any());
    }

    @Test
    @DisplayName("교부 — 없는 상품이면 기록 없이 거부한다")
    void recordRejectsUnknownProductWithoutSaving() {
        when(loadProductPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().record(new RecordDeliveryCommand(
                null, "NOPE", SalesChannel.FC, "fc-100", null, "홍길동")))
                .isInstanceOf(ProductNotFoundException.class);
        verify(saveDeliveryPort, never()).save(any(), any());
    }
}
