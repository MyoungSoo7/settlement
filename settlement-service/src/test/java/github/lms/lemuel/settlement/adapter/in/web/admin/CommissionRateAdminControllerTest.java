package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase;
import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase.RateSimulation;
import github.lms.lemuel.settlement.application.port.out.SaveCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.RateScope;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.exception.RetroactiveRatePolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수수료율 정책 운영 콘솔 (ADR 0032).
 *
 * <p>요율은 정산 금액을 직접 바꾸므로, 잘못된 요청이 조용히 통과하거나 오류가 500 으로 뭉개지면 안 된다.
 * 요청 바인딩·기본값·오류 상태코드를 여기서 못박는다.
 */
class CommissionRateAdminControllerTest {

    private RegisterCommissionRatePolicyUseCase registerUseCase;
    private SaveCommissionRatePolicyPort savePort;
    private SimulateCommissionRateUseCase simulateUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registerUseCase = mock(RegisterCommissionRatePolicyUseCase.class);
        savePort = mock(SaveCommissionRatePolicyPort.class);
        simulateUseCase = mock(SimulateCommissionRateUseCase.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CommissionRateAdminController(registerUseCase, savePort, simulateUseCase))
                // LocalDate 를 담은 응답·에러가 직렬화되어야 상태코드 검증이 의미를 갖는다.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                // 프로덕션과 같은 매핑을 본다 — BusinessException → 400 은 전역 advice 의 책임이다.
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CommissionRatePolicy saved() {
        return CommissionRatePolicy.rehydrate(1L, RateScope.SELLER, "77",
                new BigDecimal("0.01800"), LocalDate.of(2026, 9, 1), null);
    }

    private String body() {
        return """
                {"scope":"SELLER","scopeKey":"77","rate":0.018,
                 "effectiveFrom":"2026-09-01","reason":"계약 갱신"}
                """;
    }

    @Test @DisplayName("등록: 요청을 커맨드로 옮겨 유스케이스에 넘긴다")
    void register_mapsRequestToCommand() throws Exception {
        when(registerUseCase.register(any(), any())).thenReturn(saved());

        mockMvc.perform(post("/admin/commission-rates")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("77"));

        ArgumentCaptor<RegisterPolicyCommand> cmd = ArgumentCaptor.forClass(RegisterPolicyCommand.class);
        verify(registerUseCase).register(cmd.capture(), any());
        assertThat(cmd.getValue().scope()).isEqualTo(RateScope.SELLER);
        assertThat(cmd.getValue().rate()).isEqualByComparingTo("0.018");
        assertThat(cmd.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(cmd.getValue().reason()).isEqualTo("계약 갱신");
    }

    @Test @DisplayName("등록: createdBy 를 안 주면 admin 으로 기록한다 — 작성자 미상으로 남지 않게")
    void register_defaultsCreatedBy() throws Exception {
        when(registerUseCase.register(any(), any())).thenReturn(saved());

        mockMvc.perform(post("/admin/commission-rates")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk());

        ArgumentCaptor<RegisterPolicyCommand> cmd = ArgumentCaptor.forClass(RegisterPolicyCommand.class);
        verify(registerUseCase).register(cmd.capture(), any());
        assertThat(cmd.getValue().createdBy()).isEqualTo("admin");
    }

    @Test @DisplayName("등록: 소급 거부는 400 으로 나가고 정식 경로를 알려준다")
    void register_retroactiveRejectedAs400() throws Exception {
        doThrow(new RetroactiveRatePolicyException(
                "소급 구간에 이미 생성된 정산이 3건 있습니다. SettlementAdjustment(역정산)를 사용하세요."))
                .when(registerUseCase).register(any(), any());

        mockMvc.perform(post("/admin/commission-rates")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("SettlementAdjustment")));
    }

    @Test @DisplayName("등록: 필수 필드가 빠지면 400 — 요율이 미상인 채 등록되면 안 된다")
    void register_missingRequiredFieldIsRejected() throws Exception {
        mockMvc.perform(post("/admin/commission-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"SELLER","scopeKey":"77","effectiveFrom":"2026-09-01"}
                                """))
                .andExpect(status().isBadRequest());

        verify(registerUseCase, never()).register(any(), any());
    }

    @Test @DisplayName("종료: 204 로 응답하고 해당 정책만 닫는다")
    void close_returnsNoContent() throws Exception {
        mockMvc.perform(post("/admin/commission-rates/9/close"))
                .andExpect(status().isNoContent());

        verify(savePort).close(9L);
    }

    @Test @DisplayName("simulate: 셀러·등급·일자를 그대로 전달한다")
    void simulate_passesParameters() throws Exception {
        when(simulateUseCase.simulate(any(), any(), any())).thenReturn(new RateSimulation(
                77L, "VIP", LocalDate.of(2026, 9, 1), new BigDecimal("0.01800"), "SELLER:77"));

        mockMvc.perform(get("/admin/commission-rates/simulate")
                        .param("sellerId", "77").param("tier", "VIP").param("at", "2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.018))
                .andExpect(jsonPath("$.source").value("SELLER:77"));

        verify(simulateUseCase).simulate(eq(77L), eq(SellerTier.VIP), eq(LocalDate.of(2026, 9, 1)));
    }

    @Test @DisplayName("simulate: 파라미터가 없으면 null 로 넘겨 서비스 기본값(오늘·NORMAL)에 맡긴다")
    void simulate_withoutParameters() throws Exception {
        when(simulateUseCase.simulate(any(), any(), any())).thenReturn(new RateSimulation(
                null, "NORMAL", LocalDate.now(), SellerTier.NORMAL.rate(), "DEFAULT_TIER"));

        mockMvc.perform(get("/admin/commission-rates/simulate")).andExpect(status().isOk());

        verify(simulateUseCase).simulate(null, null, null);
    }
}
