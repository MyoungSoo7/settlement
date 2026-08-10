package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConversionResult;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConvertProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.CreateProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;
import github.lms.lemuel.insurance.application.port.in.GetProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProposalSheetUseCase;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;
import github.lms.lemuel.insurance.domain.exception.ProposalNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException;
import github.lms.lemuel.insurance.domain.exception.RateNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가입설계 API 계약 테스트 — 상태코드 + <b>FC 식별자가 JWT 주체에서만 온다</b>는 불변식.
 *
 * <p>standaloneSetup 은 시큐리티 필터를 태우지 않으므로 {@link SecurityContextHolder} 를 직접 세팅한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProposalController — API 계약 + JWT 소유권")
class ProposalControllerTest {

    private static final String PROPOSAL_ID = "11111111-2222-3333-4444-555555555555";
    /** JWT userId 100 → FC 식별자 "100" (FcIdentity 파생 규칙). */
    private static final long JWT_USER_ID = 100L;
    private static final String DERIVED_FC_ID = "100";

    @Mock CreateProposalUseCase createUseCase;
    @Mock GetProposalUseCase getUseCase;
    @Mock ConvertProposalUseCase convertUseCase;
    @Mock RenderProposalSheetUseCase renderUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ProposalController(createUseCase, getUseCase, convertUseCase, renderUseCase)).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** JWT 인증 주체를 SecurityContext 에 세팅한다 (userId 없는 구 토큰은 uid=null). */
    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, "fc@example.com", "USER"), null, List.of()));
    }

    private static ProposalSummary summary() {
        return new ProposalSummary(PROPOSAL_ID, "PROD-LIFE-01", "홍길동", 37,
                new BigDecimal("100000000"), 20, new BigDecimal("2.5"), new BigDecimal("250000"),
                "QUOTED", LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 6), null);
    }

    private static final String CREATE_BODY = """
            {"productCode":"PROD-LIFE-01","insuredName":"홍길동",
             "insuredBirthDate":"1990-01-15","insuredGender":"M",
             "coverageAmount":100000000,"paymentTermYears":20,"salesChannel":"FC"}
            """;

    @Test
    @DisplayName("산출 성공은 201 + 산출 스냅샷이다")
    void createReturns201() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(createUseCase.create(any())).thenReturn(summary());

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(PROPOSAL_ID))
                .andExpect(jsonPath("$.annualPremium").value(250000))
                .andExpect(jsonPath("$.insuranceAge").value(37));
    }

    @Test
    @DisplayName("설계자 fcId 는 JWT 주체에서 온다 — 본문에 fcId 를 실어도 무시된다 (IDOR 차단)")
    void createDerivesFcIdFromJwtIgnoringBody() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(createUseCase.create(any())).thenReturn(summary());

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"PROD-LIFE-01","fcId":"fc-999","insuredName":"홍길동",
                                 "insuredBirthDate":"1990-01-15","insuredGender":"M",
                                 "coverageAmount":100000000,"paymentTermYears":20,"salesChannel":"FC"}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateProposalCommand> cmd =
                ArgumentCaptor.forClass(CreateProposalCommand.class);
        verify(createUseCase).create(cmd.capture());
        assertThat(cmd.getValue().fcId()).isEqualTo(DERIVED_FC_ID);
    }

    @Test
    @DisplayName("userId 없는 구 토큰의 산출은 403 이고 유스케이스를 부르지 않는다")
    void createWithoutUserIdReturns403() throws Exception {
        authenticateAs(null);

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        verify(createUseCase, never()).create(any());
    }

    @Test
    @DisplayName("요율 부재는 422 다")
    void rateNotFoundReturns422() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(createUseCase.create(any()))
                .thenThrow(new RateNotFoundException("PROD-LIFE-01", Gender.M, 90, 20));

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"PROD-LIFE-01","insuredName":"홍길동",
                                 "insuredBirthDate":"1936-01-15","insuredGender":"M",
                                 "coverageAmount":100000000,"paymentTermYears":20,"salesChannel":"FC"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("없는 설계 조회는 404 다")
    void getUnknownReturns404() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(getUseCase.get(PROPOSAL_ID, DERIVED_FC_ID))
                .thenThrow(new ProposalNotFoundException(PROPOSAL_ID));

        mockMvc.perform(get("/api/insurance/proposals/{id}", PROPOSAL_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("조회도 JWT 주체를 요청자로 넘긴다 — 타인 설계면 403")
    void getPassesJwtRequesterAndForbidsForeign() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(getUseCase.get(PROPOSAL_ID, DERIVED_FC_ID))
                .thenThrow(new ProposalOwnershipException(PROPOSAL_ID));

        mockMvc.perform(get("/api/insurance/proposals/{id}", PROPOSAL_ID))
                .andExpect(status().isForbidden());
        verify(getUseCase).get(PROPOSAL_ID, DERIVED_FC_ID);
    }

    @Test
    @DisplayName("타인 설계 전환은 403 이고, 요청자는 JWT 에서 온다")
    void foreignConvertReturns403() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(convertUseCase.convert(any())).thenThrow(new ProposalOwnershipException(PROPOSAL_ID));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractorName\":\"김계약\"}"))
                .andExpect(status().isForbidden());

        ArgumentCaptor<ConvertProposalCommand> cmd =
                ArgumentCaptor.forClass(ConvertProposalCommand.class);
        verify(convertUseCase).convert(cmd.capture());
        assertThat(cmd.getValue().requesterFcId()).isEqualTo(DERIVED_FC_ID);
    }

    @Test
    @DisplayName("만기 설계 전환은 409 다")
    void expiredConvertReturns409() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(convertUseCase.convert(any()))
                .thenThrow(new ProposalExpiredException(PROPOSAL_ID, LocalDate.of(2026, 7, 1)));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractorName\":\"김계약\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("전환 성공은 200 + 전환 결과다")
    void convertReturns200() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(convertUseCase.convert(any())).thenReturn(new ConversionResult(
                PROPOSAL_ID, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", new BigDecimal("250000")));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractorName\":\"김계약\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    }

    @Test
    @DisplayName("설계서 PDF 는 application/pdf 로 내려가고 요청자를 함께 넘긴다")
    void sheetReturnsPdf() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(renderUseCase.render(PROPOSAL_ID, DERIVED_FC_ID)).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mockMvc.perform(get("/api/insurance/proposals/{id}/sheet", PROPOSAL_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        verify(renderUseCase).render(eq(PROPOSAL_ID), eq(DERIVED_FC_ID));
    }

    @Test
    @DisplayName("미인증 설계서 요청은 403 이고 렌더링하지 않는다")
    void sheetWithoutAuthReturns403() throws Exception {
        mockMvc.perform(get("/api/insurance/proposals/{id}/sheet", PROPOSAL_ID))
                .andExpect(status().isForbidden());
        verify(renderUseCase, never()).render(any(), any());
    }
}
