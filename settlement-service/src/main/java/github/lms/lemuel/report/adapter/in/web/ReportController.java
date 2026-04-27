package github.lms.lemuel.report.adapter.in.web;

import github.lms.lemuel.report.adapter.in.web.dto.CashflowReportResponse;
import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase;
import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase.CashflowReportCommand;
import github.lms.lemuel.report.application.port.out.RenderCashflowReportPdfPort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowReport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 자금 흐름 리포트 API — T3-⑨(a).
 *
 * <p>현재는 Cashflow Summary 만 제공한다:
 * <ul>
 *   <li>GET /api/reports/cashflow?from=2026-01-01&to=2026-01-31&groupBy=day|week|month</li>
 * </ul>
 * <p>보안: SecurityConfig 에서 /api/reports/** 를 ADMIN/MANAGER 역할로 제한.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final GenerateCashflowReportUseCase generateCashflowReportUseCase;
    private final RenderCashflowReportPdfPort renderCashflowReportPdfPort;

    @GetMapping("/cashflow")
    public ResponseEntity<CashflowReportResponse> getCashflow(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {

        CashflowReportCommand command = new CashflowReportCommand(
                from, to, BucketGranularity.from(groupBy));
        CashflowReport report = generateCashflowReportUseCase.generate(command);
        return ResponseEntity.ok(CashflowReportResponse.from(report));
    }

    @GetMapping(value = "/cashflow/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getCashflowPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {

        CashflowReportCommand command = new CashflowReportCommand(
                from, to, BucketGranularity.from(groupBy));
        CashflowReport report = generateCashflowReportUseCase.generate(command);
        byte[] pdf = renderCashflowReportPdfPort.render(report);

        String filename = String.format("cashflow-%s_to_%s_%s.pdf",
                from, to, groupBy);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    /**
     * 판매자별 Cashflow 리포트.
     * <p>Reconciliation 은 시스템 전체 불변식이라 판매자 단위에서는 빈 값(matched=true, checksRun=0)으로 반환된다.
     */
    @GetMapping("/sellers/{sellerId}/cashflow")
    public ResponseEntity<CashflowReportResponse> getSellerCashflow(
            @PathVariable Long sellerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {

        CashflowReportCommand command = new CashflowReportCommand(
                from, to, BucketGranularity.from(groupBy), sellerId);
        CashflowReport report = generateCashflowReportUseCase.generate(command);
        return ResponseEntity.ok(CashflowReportResponse.from(report));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "required parameter missing: " + e.getParameterName()));
    }
}
