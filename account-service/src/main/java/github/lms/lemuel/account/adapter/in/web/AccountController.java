package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.adapter.in.web.dto.AccountSummaryResponse;
import github.lms.lemuel.account.adapter.in.web.dto.EntryPageResponse;
import github.lms.lemuel.account.adapter.in.web.dto.TrialBalanceResponse;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.InvestmentAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.LoanAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.SettlementAggregate;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    /**
     * 시산표 조회. from/to(ISO date) 둘 다 주면 occurred_at 반개구간 [from 00:00, to+1일 00:00) 로
     * 기간 확정 시산표를, 없으면 전체 기간 시산표를 반환한다. (from·to 중 하나만 오면 400)
     */
    @GetMapping("/trial-balance")
    public ResponseEntity<TrialBalanceResponse> trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null && to == null) {
            return ResponseEntity.ok(TrialBalanceResponse.from(accountQueryUseCase.trialBalance()));
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from·to 는 함께 지정해야 합니다 (기간 확정 시산표)");
        }
        // 반개구간 [from 00:00, to 익일 00:00) — to 당일을 포함(inclusive)하도록 익일 자정을 배타 상한으로 사용
        return ResponseEntity.ok(TrialBalanceResponse.from(
                accountQueryUseCase.trialBalance(from.atStartOfDay(), to.plusDays(1).atStartOfDay())));
    }

    /**
     * 통제계정 대사(ADR 0026 Option ①) — GL 측 세 통제계정의 정상방향 순잔액
     * (SELLER_PAYABLE·HOLDBACK_PAYABLE·SELLER_RECOVERY_RECEIVABLE)을 반환한다. 각각 서브원장 Σ 와 일치해야
     * 하며, 전역 완전정산이면 {@code balanced=true}(세 순잔액 0).
     */
    @GetMapping("/control-recon")
    public ResponseEntity<TrialBalanceQuery.ControlRecon> controlRecon() {
        return ResponseEntity.ok(accountQueryUseCase.controlRecon());
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
