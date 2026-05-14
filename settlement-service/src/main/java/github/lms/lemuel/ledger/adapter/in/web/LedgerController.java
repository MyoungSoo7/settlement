package github.lms.lemuel.ledger.adapter.in.web;

import github.lms.lemuel.ledger.adapter.in.web.response.LedgerEntryResponse;
import github.lms.lemuel.ledger.application.port.in.GetLedgerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final GetLedgerUseCase getLedgerUseCase;

    /** 정산 1건에 속한 모든 원장 분개 row. */
    @GetMapping("/settlements/{settlementId}")
    public ResponseEntity<List<LedgerEntryResponse>> getBySettlementId(@PathVariable Long settlementId) {
        return ResponseEntity.ok(
                getLedgerUseCase.getBySettlementId(settlementId)
                        .stream().map(LedgerEntryResponse::from).toList()
        );
    }

    /** 환불 1건에 속한 역분개 row. */
    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<List<LedgerEntryResponse>> getByRefundId(@PathVariable Long refundId) {
        return ResponseEntity.ok(
                getLedgerUseCase.getByRefundId(refundId)
                        .stream().map(LedgerEntryResponse::from).toList()
        );
    }

    /** 기간별 원장 — 보고/감사. */
    @GetMapping("/entries")
    public ResponseEntity<List<LedgerEntryResponse>> getEntries(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                getLedgerUseCase.getBySettlementDateBetween(from, to)
                        .stream().map(LedgerEntryResponse::from).toList()
        );
    }
}
