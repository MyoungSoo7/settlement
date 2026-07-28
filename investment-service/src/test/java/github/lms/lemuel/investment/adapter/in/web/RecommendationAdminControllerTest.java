package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase.DailyScreeningReport;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.domain.ScreeningTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RecommendationAdminController — 수동 스크리닝 트리거.
 *
 * <p>date 를 주면 그 날짜로 강제 실행(백필), 안 주면 크론과 동일한 판정 경로(시세 기준일 앵커)로 실행한다.
 * 실행일(today)로 임의 세트를 만들지 않는 것이 핵심 — 그러면 크론이 그 날짜를 최신으로 오인해 스킵한다.
 */
class RecommendationAdminControllerTest {

    private static final LocalDate TUE = LocalDate.of(2026, 7, 28);

    private final ScreenRecommendationsUseCase screen = mock(ScreenRecommendationsUseCase.class);
    private final RunDailyScreeningUseCase runDaily = mock(RunDailyScreeningUseCase.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RecommendationAdminController(screen, runDaily))
            .setControllerAdvice(new InvestmentExceptionHandler())
            .build();

    private static Authentication admin() {
        AuthPrincipal principal = new AuthPrincipal(1L, "admin@lemuel.io", "ADMIN");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication manager() {
        AuthPrincipal principal = new AuthPrincipal(2L, "manager@lemuel.io", "MANAGER");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    @Test
    @DisplayName("date 미지정 — 크론과 같은 판정 경로로 실행하고 시세 기준일을 추천일로 응답한다")
    void 날짜_미지정이면_판정_경로로_실행() throws Exception {
        when(runDaily.run()).thenReturn(DailyScreeningReport.screened(ScreeningTrigger.screen(TUE), 3));

        mvc.perform(post("/api/investment/recommendations/screen").principal(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedDate").value("2026-07-28"))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.decision").value("SCREEN"));

        verify(screen, never()).screen(any());
    }

    @Test
    @DisplayName("date 미지정 + 이미 최신 — 스킵 사유를 그대로 응답한다(세트를 갈아엎지 않음)")
    void 이미_최신이면_스킵_응답() throws Exception {
        when(runDaily.run()).thenReturn(DailyScreeningReport.skipped(ScreeningTrigger.skipUpToDate(TUE)));

        mvc.perform(post("/api/investment/recommendations/screen").principal(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("SKIP_UP_TO_DATE"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("date 지정 — 그 날짜로 강제 스크리닝한다(백필 경로)")
    void 날짜_지정이면_강제_실행() throws Exception {
        when(screen.screen(TUE)).thenReturn(2);

        mvc.perform(post("/api/investment/recommendations/screen")
                        .param("date", "2026-07-28").principal(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedDate").value("2026-07-28"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.decision").value("SCREEN"));

        verify(screen).screen(TUE);
        verify(runDaily, never()).run();
    }

    @Test
    @DisplayName("ADMIN 이 아니면 403 — 상태를 바꾸는 운영 액션")
    void 관리자가_아니면_거부() throws Exception {
        mvc.perform(post("/api/investment/recommendations/screen").principal(manager()))
                .andExpect(status().isForbidden());

        verify(screen, never()).screen(any());
        verify(runDaily, never()).run();
    }
}
