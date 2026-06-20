package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementResponse;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Settlement", description = "정산 조회 및 정산서 PDF 다운로드 API")
@Validated
@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final GetSettlementUseCase getSettlementUseCase;
    private final GenerateSettlementPdfUseCase generateSettlementPdfUseCase;

    @Operation(summary = "정산 단건 조회", description = "정산 ID로 정산 정보를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<SettlementResponse> getSettlement(
            @Parameter(description = "정산 ID", required = true) @PathVariable @Positive(message = "정산 ID는 양수여야 합니다") Long id) {
        Settlement settlement = getSettlementUseCase.getSettlementById(id);
        return ResponseEntity.ok(SettlementResponse.from(settlement));
    }

    @Operation(summary = "결제 ID로 정산 조회", description = "결제 ID에 연결된 정산을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산을 찾을 수 없음")
    })
    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<SettlementResponse> getSettlementByPaymentId(
            @Parameter(description = "결제 ID", required = true) @PathVariable @Positive(message = "결제 ID는 양수여야 합니다") Long paymentId) {
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
    @Operation(summary = "정산서 PDF 다운로드",
            description = "iText 8로 생성한 정산서를 PDF 첨부 파일로 다운로드한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF 반환"),
            @ApiResponse(responseCode = "404", description = "정산을 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "PDF 생성 실패")
    })
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadSettlementPdf(
            @Parameter(description = "정산 ID", required = true) @PathVariable Long id) {
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
