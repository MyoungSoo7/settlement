package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.adapter.in.web.dto.AccountSummaryResponse;
import github.lms.lemuel.account.adapter.in.web.dto.EntryPageResponse;
import github.lms.lemuel.account.adapter.in.web.dto.TrialBalanceResponse;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.InvestmentAggregate;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.LoanAggregate;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.SettlementAggregate;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정계 조회 API — owner 잔액·분개·대출/투자/정산 집계·시산표. (JWT 인증)
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountQueryUseCase accountQueryUseCase;

    public AccountController(AccountQueryUseCase accountQueryUseCase) {
        this.accountQueryUseCase = accountQueryUseCase;
    }

    @GetMapping("/accounts/{ownerType}/{ownerId}")
    public ResponseEntity<AccountSummaryResponse> account(@PathVariable String ownerType,
                                                          @PathVariable String ownerId) {
        return ResponseEntity.ok(AccountSummaryResponse.from(
                accountQueryUseCase.accountSummary(parseOwnerType(ownerType), ownerId)));
    }

    @GetMapping("/accounts/{ownerType}/{ownerId}/entries")
    public ResponseEntity<EntryPageResponse> entries(@PathVariable String ownerType,
                                                     @PathVariable String ownerId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(EntryPageResponse.from(
                accountQueryUseCase.entries(parseOwnerType(ownerType), ownerId, page, size)));
    }

    @GetMapping("/aggregates/loans")
    public ResponseEntity<LoanAggregate> loans() {
        return ResponseEntity.ok(accountQueryUseCase.loanAggregates());
    }

    @GetMapping("/aggregates/investments")
    public ResponseEntity<InvestmentAggregate> investments() {
        return ResponseEntity.ok(accountQueryUseCase.investmentAggregates());
    }

    @GetMapping("/aggregates/settlements")
    public ResponseEntity<SettlementAggregate> settlements() {
        return ResponseEntity.ok(accountQueryUseCase.settlementAggregates());
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<TrialBalanceResponse> trialBalance() {
        return ResponseEntity.ok(TrialBalanceResponse.from(accountQueryUseCase.trialBalance()));
    }

    /** ownerType 경로 파싱 — 알 수 없는 값이면 IllegalArgumentException(→ 공통 핸들러 400). */
    private static OwnerType parseOwnerType(String raw) {
        try {
            return OwnerType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 ownerType: " + raw + " (SELLER|CORPORATE)");
        }
    }
}
