package github.lms.lemuel.payout.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.in.RegisterSellerBankAccountUseCase;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 셀러 지급 계좌 레지스트리 운영자 콘솔 — 등록·정정·조회.
 *
 * <p>인가: {@code /admin/seller-bank-accounts/**} 는 shared-common SecurityConfig 가 ADMIN/MANAGER 로
 * 게이트한다(셀러 식별자를 관리자 입력으로 받으므로 권한 게이트로 IDOR 방지). 계좌번호는 조회 시
 * 마스킹만 노출한다.
 */
@Tag(name = "Seller Bank Account Registry", description = "셀러 지급 계좌 등록·정정 운영자 콘솔")
@RestController
@RequestMapping("/admin/seller-bank-accounts")
public class SellerBankAccountAdminController {

    private final RegisterSellerBankAccountUseCase registerUseCase;
    private final LoadSellerBankAccountRegistrationPort loadPort;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public SellerBankAccountAdminController(RegisterSellerBankAccountUseCase registerUseCase,
                                            LoadSellerBankAccountRegistrationPort loadPort,
                                            AuditLogger auditLogger,
                                            ObjectMapper objectMapper) {
        this.registerUseCase = registerUseCase;
        this.loadPort = loadPort;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "셀러 지급 계좌 등록 (신규 또는 정정 upsert)")
    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody UpsertRequest request) {
        return upsert(request.sellerId(), request.bankCode(), request.accountNumber(), request.accountHolder());
    }

    @Operation(summary = "셀러 지급 계좌 정정")
    @PutMapping("/{sellerId}")
    public ResponseEntity<Map<String, Object>> change(@PathVariable Long sellerId,
                                                      @RequestBody ChangeRequest request) {
        return upsert(sellerId, request.bankCode(), request.accountNumber(), request.accountHolder());
    }

    @Operation(summary = "셀러 지급 계좌 조회 (계좌번호 마스킹)")
    @GetMapping("/{sellerId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long sellerId) {
        return loadPort.findBySellerId(sellerId)
                .map(reg -> ResponseEntity.ok(view(reg)))
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Map<String, Object>> upsert(Long sellerId, String bankCode,
                                                       String accountNumber, String accountHolder) {
        SellerBankAccountRegistration saved =
                registerUseCase.register(sellerId, bankCode, accountNumber, accountHolder);
        // PII 계좌 변경 — 실자금 경로의 선행 조작이라 감사 추적. 계좌번호 원문은 남기지 않고 마스킹만.
        auditLogger.record(AuditAction.SELLER_BANK_ACCOUNT_REGISTERED, "SellerBankAccount",
                String.valueOf(sellerId),
                toJson(Map.of("operator", currentOperator(), "sellerId", sellerId,
                        "bank", saved.getBankCode(),
                        "account", saved.toBankAccount().maskedAccountNumber())));
        return ResponseEntity.ok(view(saved));
    }

    private Map<String, Object> view(SellerBankAccountRegistration reg) {
        SellerBankAccount snapshot = reg.toBankAccount();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerId", reg.getSellerId());
        body.put("bank", reg.getBankCode());
        body.put("account", snapshot.maskedAccountNumber());
        body.put("holder", reg.getAccountHolder());
        body.put("updatedAt", reg.getUpdatedAt());
        return body;
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"audit_serialization_failed\"}";
        }
    }

    public record UpsertRequest(Long sellerId, String bankCode, String accountNumber, String accountHolder) {
    }

    public record ChangeRequest(String bankCode, String accountNumber, String accountHolder) {
    }
}
