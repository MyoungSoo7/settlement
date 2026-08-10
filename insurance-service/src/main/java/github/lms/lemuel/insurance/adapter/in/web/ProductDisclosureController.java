package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.DeliveredDisclosure;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.RecordDeliveryCommand;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase.RenderedDisclosure;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.InvalidDisclosureException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 대면 상품설명서 API — 미리보기 다운로드 + 교부(문서 발급 & 완전판매 증빙 기록).
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * 교부자는 로그인 주체가 아니라 명시 입력(deliveredBy)이다 — 창구 공용 단말 시나리오.
 */
@RestController
@RequestMapping("/api/insurance")
public class ProductDisclosureController {

    private final RenderProductDisclosureUseCase renderUseCase;
    private final RecordDisclosureDeliveryUseCase recordUseCase;

    public ProductDisclosureController(
            RenderProductDisclosureUseCase renderUseCase,
            RecordDisclosureDeliveryUseCase recordUseCase) {
        this.renderUseCase = renderUseCase;
        this.recordUseCase = recordUseCase;
    }

    /** 상품설명서 미리보기/재출력 — 교부 증빙은 남지 않는다. */
    @GetMapping("/products/{productCode}/disclosure")
    public ResponseEntity<byte[]> preview(@PathVariable String productCode) {
        RenderedDisclosure rendered = renderUseCase.render(productCode);
        return pdfResponse(rendered.pdf(), rendered.productCode(), rendered.sha256());
    }

    /**
     * 교부 — 문서 발급 + 증빙 기록의 단일 행위.
     * 응답 PDF 의 SHA-256 이 곧 저장된 증빙 해시다 (X-Document-Sha256 헤더로도 반환).
     */
    @PostMapping("/disclosures")
    public ResponseEntity<byte[]> deliver(@RequestBody DeliverRequest request) {
        DeliveredDisclosure delivered = recordUseCase.record(new RecordDeliveryCommand(
                request.applicationId(), request.productCode(), request.salesChannel(),
                request.deliveredBy(), request.partnerBankCode(), request.contractorName()));
        return pdfResponse(delivered.pdf(), delivered.delivery().getProductCode(),
                delivered.delivery().getDocumentSha256());
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String productCode, String sha256) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"disclosure-" + productCode + ".pdf\"")
                .header("X-Document-Sha256", sha256)
                .body(pdf);
    }

    /**
     * @param applicationId   청약 경유 교부 시 (선택)
     * @param partnerBankCode BANCA 교부 시 필수 (도메인이 강제)
     */
    public record DeliverRequest(String applicationId,
                                 @NotBlank String productCode,
                                 @NotNull SalesChannel salesChannel,
                                 @NotBlank String deliveredBy,
                                 String partnerBankCode,
                                 @NotBlank String contractorName) {
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, String>> productNotFound(ProductNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidDisclosureException.class)
    public ResponseEntity<Map<String, String>> invalidDisclosure(InvalidDisclosureException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
