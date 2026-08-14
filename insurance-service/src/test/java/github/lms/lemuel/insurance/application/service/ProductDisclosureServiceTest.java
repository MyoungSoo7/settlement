package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.DeliveredDisclosure;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.RecordDeliveryCommand;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase.RenderedDisclosure;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderDisclosurePdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveDisclosureDeliveryPort;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationOwnershipException;
import github.lms.lemuel.insurance.domain.exception.InvalidDisclosureException;
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
    @Mock LoadApplicationPort loadApplicationPort;
    @Mock RenderDisclosurePdfPort renderPdfPort;
    @Mock SaveDisclosureDeliveryPort saveDeliveryPort;
    @Mock AuditLogger auditLogger;

    private ProductDisclosureService service() {
        return new ProductDisclosureService(
                loadProductPort, loadApplicationPort, renderPdfPort, saveDeliveryPort, auditLogger);
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

    // ── 청약 대조 (완전판매 증빙의 전제) ──────────────────────────────────
    //
    // 대조가 없으면 교부 증빙은 "청약과 무관한 아무 문서 1건"이 되고, 승인의 완전판매 게이트가
    // 그 1건으로 열린다 — 계약자에게 아무것도 교부하지 않은 계약이 발행될 수 있다.

    private static final String APPLICATION_ID = "app-1";

    private static InsuranceApplication application() {
        return InsuranceApplication.submit(null, "PROD-1", "fc-100", "김피보", "홍길동",
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"), SalesChannel.FC, null);
    }

    private RecordDeliveryCommand deliveryOf(String productCode, SalesChannel channel,
                                             String deliveredBy, String contractorName) {
        return new RecordDeliveryCommand(APPLICATION_ID, productCode, channel,
                deliveredBy, null, contractorName);
    }

    @Test
    @DisplayName("청약 대조 — 청약을 참조하지 않는 교부(상담 단계 설명서)는 조회 자체를 하지 않는다")
    void deliveryWithoutApplicationSkipsLookup() {
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product(true)));
        when(renderPdfPort.render(any())).thenReturn(PDF);
        when(saveDeliveryPort.save(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        service().record(new RecordDeliveryCommand(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "홍길동"));

        verify(loadApplicationPort, never()).findByApplicationId(any());
    }

    @Test
    @DisplayName("청약 대조 — 없는 청약을 참조하면 404, 기록하지 않는다")
    void unknownApplicationIsRejected() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-1", SalesChannel.FC, "fc-100", "홍길동")))
                .isInstanceOf(ApplicationNotFoundException.class);
        verify(saveDeliveryPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("청약 대조 — 남의 청약에는 교부 증빙을 붙일 수 없다 (403)")
    void foreignApplicationIsRejected() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-1", SalesChannel.FC, "fc-999", "홍길동")))
                .isInstanceOf(ApplicationOwnershipException.class);
        verify(saveDeliveryPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("청약 대조 — 소유권을 상품 불일치보다 먼저 본다 (남의 청약 내용을 추측할 수 없다)")
    void ownershipIsCheckedBeforeContent() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));

        // 상품·채널·계약자가 모두 어긋난 요청이라도 남의 청약이면 소유권 실패로만 답한다.
        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-OTHER", SalesChannel.BANCA, "fc-999", "다른사람")))
                .isInstanceOf(ApplicationOwnershipException.class);
    }

    @Test
    @DisplayName("청약 대조 — 청약과 다른 상품의 설명서는 증빙이 아니다")
    void productMismatchIsRejected() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-OTHER", SalesChannel.FC, "fc-100", "홍길동")))
                .isInstanceOf(InvalidDisclosureException.class);
        verify(saveDeliveryPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("청약 대조 — 청약과 다른 판매채널로는 교부할 수 없다")
    void channelMismatchIsRejected() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-1", SalesChannel.BANCA, "fc-100", "홍길동")))
                .isInstanceOf(InvalidDisclosureException.class);
        verify(saveDeliveryPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("청약 대조 — 계약자가 다르면 '그 사람에게 설명했다'는 증빙이 아니다 (이름은 응답에 싣지 않는다)")
    void contractorMismatchIsRejected() {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service().record(
                deliveryOf("PROD-1", SalesChannel.FC, "fc-100", "다른사람")))
                .isInstanceOf(InvalidDisclosureException.class)
                .hasMessageNotContaining("다른사람")
                .hasMessageNotContaining("홍길동");
        verify(saveDeliveryPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("청약 대조 — 소유·상품·채널·계약자가 모두 일치하면 교부된다 (과잉 차단 방지)")
    void matchingApplicationIsAccepted() throws Exception {
        when(loadApplicationPort.findByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(application()));
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product(true)));
        when(renderPdfPort.render(any())).thenReturn(PDF);
        when(saveDeliveryPort.save(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        DeliveredDisclosure delivered = service().record(
                deliveryOf("PROD-1", SalesChannel.FC, "fc-100", "홍길동"));

        assertThat(delivered.delivery().getDocumentSha256()).isEqualTo(sha256Of(PDF));
        verify(saveDeliveryPort).save(any(), any());
    }
}
