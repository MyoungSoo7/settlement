package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.ExpiringLotView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountDetail;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointConsoleSummary;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEarnPolicyView;
import github.lms.lemuel.point.domain.PointLedgerHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 포인트 콘솔의 <b>조회</b> 표면.
 *
 * <p>지급·소멸(쓰기)은 각 유스케이스 테스트가 지키고, 여기서는 "운영자가 조작 전에 보는 것"이
 * 화면까지 제대로 나가는지를 본다. 특히 <b>계정 없음이 404 로 구분되는지</b>가 중요하다 —
 * 잔액 0 인 계정과 계정 자체가 없는 사용자를 200/0 으로 뭉뚱그리면 조사 단서를 잃는다.
 */
@WebMvcTest(controllers = AdminPointController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminPointControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GrantPointUseCase grantPointUseCase;
    @MockitoBean ExpirePointLotsUseCase expirePointLotsUseCase;
    @MockitoBean QueryPointConsoleUseCase queryPointConsoleUseCase;

    @Test
    @DisplayName("GET /admin/points/summary — 3자 대조와 소멸 예정 규모를 돌려준다")
    void summary() throws Exception {
        when(queryPointConsoleUseCase.summary(anyInt())).thenReturn(new PointConsoleSummary(
                5L, new BigDecimal("5000"), new BigDecimal("4800"), new BigDecimal("5000"),
                2L, 30, new BigDecimal("250")));

        mockMvc.perform(get("/admin/points/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCount").value(5))
                .andExpect(jsonPath("$.totalAvailable").value(5000))
                .andExpect(jsonPath("$.totalActiveLotRemaining").value(4800))
                .andExpect(jsonPath("$.driftedAccountCount").value(2))
                .andExpect(jsonPath("$.expiringAmount").value(250));
    }

    @Test
    @DisplayName("소멸 예정 창은 기본 30일이다")
    void summaryDefaultWindow() throws Exception {
        when(queryPointConsoleUseCase.summary(anyInt())).thenReturn(new PointConsoleSummary(
                0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 30, BigDecimal.ZERO));

        mockMvc.perform(get("/admin/points/summary")).andExpect(status().isOk());

        verify(queryPointConsoleUseCase).summary(30);
    }

    @Test
    @DisplayName("GET /admin/points/accounts/{userId} — 계정과 3자 대조를 함께 돌려준다")
    void account() throws Exception {
        PointLedgerHealth health = PointLedgerHealth.of(
                new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("1000"));
        when(queryPointConsoleUseCase.account(7L)).thenReturn(Optional.of(new PointAccountDetail(
                7L, 70L, "ACTIVE", new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                health, List.of(), List.of())));

        mockMvc.perform(get("/admin/points/accounts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(70))
                .andExpect(jsonPath("$.available").value(1000))
                .andExpect(jsonPath("$.health.activeLotRemaining").value(700));
    }

    @Test
    @DisplayName("포인트를 쓴 적 없는 사용자는 404 — 잔액 0 인 계정과 구분된다")
    void accountNotFound() throws Exception {
        when(queryPointConsoleUseCase.account(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/points/accounts/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /admin/points/policies — 종료된 정책도 함께 나온다")
    void policies() throws Exception {
        when(queryPointConsoleUseCase.policies()).thenReturn(List.of(
                new PointEarnPolicyView(1L, "GLOBAL", "-", new BigDecimal("0.01000"), 365,
                        LocalDate.of(2026, 1, 1), null, "기본 적립률", "admin", true),
                new PointEarnPolicyView(2L, "GLOBAL", "-", new BigDecimal("0.00500"), 365,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), "구 요율", "admin", false)));

        mockMvc.perform(get("/admin/points/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(false))
                .andExpect(jsonPath("$[1].effectiveTo").value("2026-01-01"));
    }

    @Test
    @DisplayName("GET /admin/points/expiring — 소멸 예정 로트를 돌려주고 파라미터를 그대로 전달한다")
    void expiring() throws Exception {
        when(queryPointConsoleUseCase.expiringLots(anyInt(), anyInt())).thenReturn(List.of(
                new ExpiringLotView(3L, 1L, "MANUAL_GRANT", new BigDecimal("5000"),
                        OffsetDateTime.parse("2027-08-18T14:31:50Z"))));

        mockMvc.perform(get("/admin/points/expiring").param("withinDays", "7").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(3))
                .andExpect(jsonPath("$[0].remainingAmount").value(5000));

        verify(queryPointConsoleUseCase).expiringLots(eq(7), eq(20));
    }
}
