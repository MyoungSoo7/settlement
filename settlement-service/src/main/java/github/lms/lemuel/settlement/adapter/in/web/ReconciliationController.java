package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Reconciliation", description = "정산 일일 대사(이중장부) API")
@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationController {

    private final ReconcileDailyTotalsUseCase reconcileUseCase;

    public ReconciliationController(ReconcileDailyTotalsUseCase reconcileUseCase) {
        this.reconcileUseCase = reconcileUseCase;
    }

    @Operation(summary = "일일 대사 실행",
            description = "해당 날짜의 결제/환불/정산 금액이 일치하는지 검증. matched=false 면 원장 불일치.")
    @GetMapping
    public ResponseEntity<ReconciliationReport> run(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(reconcileUseCase.reconcile(date));
    }
}
