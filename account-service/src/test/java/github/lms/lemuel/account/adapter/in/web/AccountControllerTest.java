package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.OwnerAccountQuery.EntryPage;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.InvestmentAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.LoanAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.SettlementAggregate;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AccountController standalone MockMvc 테스트 — 시큐리티/부팅 우회, UseCase 목킹으로 6개 엔드포인트의
 * 응답 매핑과 ownerType 파싱 실패 400 경로를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock AccountQueryUseCase accountQueryUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountController(accountQueryUseCase))
                .setControllerAdvice(new BadRequestAdvice())
                .build();
    }

    @Test
    void account_는_owner_요약을_반환한다() throws Exception {
        when(accountQueryUseCase.accountSummary(OwnerType.SELLER, "55")).thenReturn(
                AccountSummary.of(OwnerType.SELLER, "55", List.of(
                        AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000")))));

        mockMvc.perform(get("/api/account/accounts/SELLER/55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("SELLER"))
                .andExpect(jsonPath("$.ownerId").value("55"))
                .andExpect(jsonPath("$.entryCount").value(1));
    }

    @Test
    void account_는_알수없는_ownerType이면_400() throws Exception {
        mockMvc.perform(get("/api/account/accounts/UNKNOWN/55"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void entries_는_분개_페이지를_반환한다() throws Exception {
        EntryPage page = new EntryPage(List.of(
                AccountEntry.investmentExecuted("55", "O1", new BigDecimal("250000"))), 1L, 0, 20);
        when(accountQueryUseCase.entries(eq(OwnerType.SELLER), eq("55"), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/account/accounts/SELLER/55/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].refId").value("O1"))
                .andExpect(jsonPath("$.content[0].debitAccount").value(GlAccount.INVESTMENT_ASSET.name()));
    }

    @Test
    void entries_는_page_size_쿼리파라미터를_전달한다() throws Exception {
        when(accountQueryUseCase.entries(eq(OwnerType.SELLER), eq("55"), eq(2), eq(5)))
                .thenReturn(new EntryPage(List.of(), 0L, 2, 5));

        mockMvc.perform(get("/api/account/accounts/SELLER/55/entries").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void loans_는_대출_집계를_반환한다() throws Exception {
        when(accountQueryUseCase.loanAggregates()).thenReturn(new LoanAggregate(
                new BigDecimal("1000000"), new BigDecimal("300000"), new BigDecimal("700000"),
                new BigDecimal("5000000"), new BigDecimal("5000000"), 6L));

        mockMvc.perform(get("/api/account/aggregates/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstanding").value(700000))
                .andExpect(jsonPath("$.corporateDisbursedTotal").value(5000000))
                .andExpect(jsonPath("$.entryCount").value(6));
    }

    @Test
    void investments_는_투자_집계를_반환한다() throws Exception {
        when(accountQueryUseCase.investmentAggregates())
                .thenReturn(new InvestmentAggregate(new BigDecimal("250000"), 4L));

        mockMvc.perform(get("/api/account/aggregates/investments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investedTotal").value(250000))
                .andExpect(jsonPath("$.orderCount").value(4));
    }

    @Test
    void settlements_는_정산_집계를_반환한다() throws Exception {
        when(accountQueryUseCase.settlementAggregates()).thenReturn(new SettlementAggregate(
                new BigDecimal("100000"), new BigDecimal("40000"), new BigDecimal("60000")));

        mockMvc.perform(get("/api/account/aggregates/settlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledTotal").value(100000))
                .andExpect(jsonPath("$.confirmedTotal").value(40000))
                .andExpect(jsonPath("$.pendingScheduled").value(60000));
    }

    @Test
    void trialBalance_는_시산표를_반환한다() throws Exception {
        when(accountQueryUseCase.trialBalance()).thenReturn(TrialBalance.of(List.of(
                AccountEntry.loanDisbursed("1", "L1", new BigDecimal("800000")),
                AccountEntry.investmentExecuted("1", "O1", new BigDecimal("250000")))));

        mockMvc.perform(get("/api/account/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                // 이 표본은 CASH 유출(loan·investment)만 있어 CASH 가 순대변 → 차변성 정상방향 위반(false).
                // 항등식 balanced 와 달리 방향 검증은 이 이상을 잡아낸다(ADR 0026 3a).
                .andExpect(jsonPath("$.normalBalanceRespected").value(false))
                .andExpect(jsonPath("$.totalDebit").value(1050000))
                .andExpect(jsonPath("$.totalCredit").value(1050000));
    }

    @Test
    void trialBalance_는_from_to_기간을_전달한다() throws Exception {
        when(accountQueryUseCase.trialBalance(
                eq(java.time.LocalDate.of(2026, 7, 1).atStartOfDay()),
                eq(java.time.LocalDate.of(2026, 8, 1).atStartOfDay()))) // to(7/31) 익일 자정
                .thenReturn(TrialBalance.of(List.of(
                        AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("43425")),
                        AccountEntry.payoutCompleted("777", "P1", new BigDecimal("43425")))));

        mockMvc.perform(get("/api/account/trial-balance").param("from", "2026-07-01").param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.normalBalanceRespected").value(true));
    }

    @Test
    void trialBalance_는_from만_오면_400() throws Exception {
        mockMvc.perform(get("/api/account/trial-balance").param("from", "2026-07-01"))
                .andExpect(status().isBadRequest());
    }

    /** parseOwnerType 실패(IllegalArgumentException) → 400 매핑용 최소 어드바이스. */
    @RestControllerAdvice
    static class BadRequestAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public String handle(IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
