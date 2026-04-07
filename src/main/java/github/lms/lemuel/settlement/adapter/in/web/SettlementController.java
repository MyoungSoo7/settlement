package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementResponse;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Settlement API Controller
 */
@Validated
@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final GetSettlementUseCase getSettlementUseCase;
    private final GenerateSettlementPdfUseCase generateSettlementPdfUseCase;

    @GetMapping("/{id}")
    public ResponseEntity<SettlementResponse> getSettlement(
            @PathVariable @Positive(message = "정산 ID는 양수여야 합니다") Long id) {
        Settlement settlement = getSettlementUseCase.getSettlementById(id);
        return ResponseEntity.ok(SettlementResponse.from(settlement));
    }

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<SettlementResponse> getSettlementByPaymentId(
            @PathVariable @Positive(message = "결제 ID는 양수여야 합니다") Long paymentId) {
        var settlements = getSettlementUseCase.getSettlementsByPaymentId(paymentId);
        if (settlements.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(SettlementResponse.from(settlements.get(0)));
    }

    /**
     * 정산서 PDF 다운로드
     * GET /settlements/{id}/pdf
     *
     * <p>iText 8 (AGPL)로 생성한 정산서를 PDF 파일로 응답한다.
     * Content-Disposition: attachment 로 설정해 브라우저가 즉시 다운로드한다.
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadSettlementPdf(@PathVariable Long id) {
        byte[] pdf = generateSettlementPdfUseCase.generate(id);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("settlement-" + id + ".pdf")
                        .build());
        headers.setContentLength(pdf.length);

        return ResponseEntity.ok().headers(headers).body(pdf);
    }
}
