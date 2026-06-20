package github.lms.lemuel.chargeback.adapter.in.web;

import github.lms.lemuel.chargeback.application.port.in.DecideChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase.OpenChargebackCommand;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카드사 분쟁 운영자 콘솔.
 *
 * <p>{@code /admin/chargebacks/**} 는 SecurityConfig 에서 ROLE_ADMIN 강제.
 *
 * <p>API 표면:
 * <ul>
 *   <li>POST /admin/chargebacks                       — 수동 등록 (MANUAL)</li>
 *   <li>GET  /admin/chargebacks?status=OPEN&max=20    — 상태별 목록</li>
 *   <li>GET  /admin/chargebacks/{id}                  — 상세</li>
 *   <li>POST /admin/chargebacks/{id}/accept           — 셀러 책임 인정 → settlement_adjustments 차감</li>
 *   <li>POST /admin/chargebacks/{id}/reject           — 셀러 증빙 인정 → 분쟁 종결</li>
 * </ul>
 *
 * <p>PG webhook 자동 등록 채널은 별도 컨트롤러 (Phase 3) — HMAC 서명 검증 등 별도 보안 설계 필요.
 */
@Tag(name = "Chargeback Admin", description = "카드사 분쟁 운영자 콘솔")
@RestController
@RequestMapping("/admin/chargebacks")
public class ChargebackAdminController {

    private final OpenChargebackUseCase openUseCase;
    private final DecideChargebackUseCase decideUseCase;
    private final LoadChargebackPort loadPort;

    public ChargebackAdminController(OpenChargebackUseCase openUseCase,
                                      DecideChargebackUseCase decideUseCase,
                                      LoadChargebackPort loadPort) {
        this.openUseCase = openUseCase;
        this.decideUseCase = decideUseCase;
        this.loadPort = loadPort;
    }

    @Operation(summary = "수동 분쟁 등록 (PG 통지 누락·시연용)")
    @PostMapping
    public ResponseEntity<ChargebackResponse> openManual(@RequestBody OpenManualRequest req) {
        Chargeback cb = openUseCase.open(new OpenChargebackCommand(
                req.paymentId(), req.settlementId(), req.amount(),
                req.reasonCode(), req.reasonDetail(),
                ChargebackSource.MANUAL, null
        ));
        return ResponseEntity.ok(ChargebackResponse.from(cb));
    }

    @Operation(summary = "상태별 분쟁 목록")
    @GetMapping
    public ResponseEntity<List<ChargebackResponse>> list(
            @RequestParam(defaultValue = "OPEN") ChargebackStatus status,
            @RequestParam(defaultValue = "20") int max) {
        List<ChargebackResponse> result = loadPort.findByStatus(status, max).stream()
                .map(ChargebackResponse::from).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "분쟁 상세")
    @GetMapping("/{id}")
    public ResponseEntity<ChargebackResponse> get(@PathVariable Long id) {
        return loadPort.findById(id)
                .map(ChargebackResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "셀러 책임 인정 — settlement_adjustments 음수 row 자동 생성")
    @PostMapping("/{id}/accept")
    public ResponseEntity<ChargebackResponse> accept(@PathVariable Long id,
                                                      @RequestBody DecisionRequest req) {
        Chargeback cb = decideUseCase.accept(id, currentOperator(), req.note());
        return ResponseEntity.ok(ChargebackResponse.from(cb));
    }

    @Operation(summary = "셀러 증빙 인정 — 분쟁 종결, 정산 영향 없음. 사유 필수.")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ChargebackResponse> reject(@PathVariable Long id,
                                                      @RequestBody DecisionRequest req) {
        Chargeback cb = decideUseCase.reject(id, currentOperator(), req.note());
        return ResponseEntity.ok(ChargebackResponse.from(cb));
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }

    public record OpenManualRequest(
            Long paymentId,
            Long settlementId,
            BigDecimal amount,
            ChargebackReason reasonCode,
            String reasonDetail
    ) { }

    public record DecisionRequest(String note) { }

    public record ChargebackResponse(Map<String, Object> chargeback) {
        static ChargebackResponse from(Chargeback c) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", c.getId());
            body.put("paymentId", c.getPaymentId());
            body.put("settlementId", c.getSettlementId());
            body.put("amount", c.getAmount());
            body.put("reasonCode", c.getReasonCode().name());
            body.put("reasonDetail", c.getReasonDetail());
            body.put("status", c.getStatus().name());
            body.put("source", c.getSource().name());
            body.put("pgChargebackId", c.getPgChargebackId());
            body.put("decidedBy", c.getDecidedBy());
            body.put("decisionNote", c.getDecisionNote());
            body.put("raisedAt", c.getRaisedAt());
            body.put("decidedAt", c.getDecidedAt());
            return new ChargebackResponse(body);
        }
    }
}
