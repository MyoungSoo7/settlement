package github.lms.lemuel.payout.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.payout.application.port.in.RegisterSellerBankAccountUseCase;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 셀러 지급 계좌 셀프서비스 API — 셀러 본인이 송금 대상 계좌를 등록·정정·조회한다.
 *
 * <p><b>IDOR 방지</b>: 셀러 식별자는 요청(본문·경로·쿼리)에서 받지 않고 JWT 주체
 * ({@link AuthPrincipal#userId()})에서만 파생한다 — 본문에 sellerId 류 필드가 실려 와도 바인딩되지
 * 않는다. userId 가 없는 구(舊) 토큰은 403 으로 거절한다(전역 핸들러의 500 폴백을 타지 않도록 로컬 매핑).
 * 관리자 대행 등록은 별도 콘솔({@link SellerBankAccountAdminController})이 담당한다.
 *
 * <p>계좌번호는 조회·감사로그 모두 마스킹만 노출하고, 저장 시 암호화는 영속 어댑터의
 * {@code FieldEncryptionConverter} 가 담당한다(도메인 무오염).
 *
 * <p>셀러 자격 검증은 하지 않는다(보안 검수 LOW 수용) — 비셀러 USER 도 자기 행을 만들 수 있으나,
 * payout 은 정산 확정·홀드백 해제 레코드의 sellerId 로만 계좌를 해석하므로 정산이 없는 사용자의
 * 행은 실자금 경로에 닿지 않는 무해 레코드다. 셀러 자격 대조가 필요해지면 organization 프로젝션
 * 소비 배선이 선행이다.
 */
@Tag(name = "Seller Bank Account (Self)", description = "셀러 본인 지급 계좌 등록·정정·조회")
@RestController
@RequestMapping("/api/seller/bank-account")
public class SellerBankAccountSelfController {

    private final RegisterSellerBankAccountUseCase registerUseCase;
    private final LoadSellerBankAccountRegistrationPort loadPort;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public SellerBankAccountSelfController(RegisterSellerBankAccountUseCase registerUseCase,
                                           LoadSellerBankAccountRegistrationPort loadPort,
                                           AuditLogger auditLogger,
                                           ObjectMapper objectMapper) {
        this.registerUseCase = registerUseCase;
        this.loadPort = loadPort;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "본인 지급 계좌 등록·정정 (upsert)")
    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody UpsertRequest request) {
        Authentication auth = currentAuthentication();
        Long sellerId = currentSellerId(auth);
        if (sellerId == null) {
            return forbidden();
        }
        SellerBankAccountRegistration saved = registerUseCase.register(
                sellerId, request.bankCode(), request.accountNumber(), request.accountHolder());
        // PII 계좌 변경 — 실자금 경로의 선행 조작이라 감사 추적. 계좌번호 원문은 남기지 않고 마스킹만.
        auditLogger.record(AuditAction.SELLER_BANK_ACCOUNT_REGISTERED, "SellerBankAccount",
                String.valueOf(sellerId),
                toJson(Map.of("operator", auth.getName(), "channel", "SELF", "sellerId", sellerId,
                        "bank", saved.getBankCode(),
                        "account", saved.toBankAccount().maskedAccountNumber())));
        return ResponseEntity.ok(view(saved));
    }

    @Operation(summary = "본인 지급 계좌 조회 (계좌번호 마스킹)")
    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        Long sellerId = currentSellerId(currentAuthentication());
        if (sellerId == null) {
            return forbidden();
        }
        return loadPort.findBySellerId(sellerId)
                .map(reg -> ResponseEntity.ok(view(reg)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static Long currentSellerId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "본인 확인이 불가한 토큰입니다 — 재로그인 후 다시 시도하세요"));
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"audit_serialization_failed\"}";
        }
    }

    /** 셀러 식별자 필드는 의도적으로 없다 — JWT 주체에서만 파생 (IDOR 차단). */
    public record UpsertRequest(String bankCode, String accountNumber, String accountHolder) {
    }
}
