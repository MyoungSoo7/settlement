package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConversionResult;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;
import github.lms.lemuel.insurance.application.port.in.GetProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProposalSheetUseCase;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;
import github.lms.lemuel.insurance.domain.exception.ProposalNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException;
import github.lms.lemuel.insurance.domain.exception.RateNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가입설계 API 상태코드 계약 테스트 — standalone MockMvc (컨텍스트 미기동).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProposalController — API 상태코드 계약")
class ProposalControllerTest {

    private static final String PROPOSAL_ID = "11111111-2222-3333-4444-555555555555";

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

    private static ProposalSummary summary() {
        return new ProposalSummary(PROPOSAL_ID, "PROD-LIFE-01", "홍길동", 37,
                new BigDecimal("100000000"), 20, new BigDecimal("2.5"), new BigDecimal("250000"),
                "QUOTED", LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 6), null);
    }

    @Test
    @DisplayName("산출 성공은 201 + 산출 스냅샷이다")
    void createReturns201() throws Exception {
        when(createUseCase.create(any())).thenReturn(summary());

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"PROD-LIFE-01","fcId":"fc-100","insuredName":"홍길동",
                                 "insuredBirthDate":"1990-01-15","insuredGender":"M",
                                 "coverageAmount":100000000,"paymentTermYears":20,"salesChannel":"FC"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(PROPOSAL_ID))
                .andExpect(jsonPath("$.annualPremium").value(250000))
                .andExpect(jsonPath("$.insuranceAge").value(37));
    }

    @Test
    @DisplayName("요율 부재는 422 다")
    void rateNotFoundReturns422() throws Exception {
        when(createUseCase.create(any()))
                .thenThrow(new RateNotFoundException("PROD-LIFE-01", Gender.M, 90, 20));

        mockMvc.perform(post("/api/insurance/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"PROD-LIFE-01","fcId":"fc-100","insuredName":"홍길동",
                                 "insuredBirthDate":"1936-01-15","insuredGender":"M",
                                 "coverageAmount":100000000,"paymentTermYears":20,"salesChannel":"FC"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("없는 설계 조회는 404 다")
    void getUnknownReturns404() throws Exception {
        when(getUseCase.get(PROPOSAL_ID)).thenThrow(new ProposalNotFoundException(PROPOSAL_ID));

        mockMvc.perform(get("/api/insurance/proposals/{id}", PROPOSAL_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("타인 설계 전환은 403 다")
    void foreignConvertReturns403() throws Exception {
        when(convertUseCase.convert(any())).thenThrow(new ProposalOwnershipException(PROPOSAL_ID));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcId\":\"fc-999\",\"contractorName\":\"김계약\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("만기 설계 전환은 409 다")
    void expiredConvertReturns409() throws Exception {
        when(convertUseCase.convert(any()))
                .thenThrow(new ProposalExpiredException(PROPOSAL_ID, LocalDate.of(2026, 7, 1)));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcId\":\"fc-100\",\"contractorName\":\"김계약\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("전환 성공은 200 + 전환 결과다")
    void convertReturns200() throws Exception {
        when(convertUseCase.convert(any())).thenReturn(new ConversionResult(
                PROPOSAL_ID, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", new BigDecimal("250000")));

        mockMvc.perform(post("/api/insurance/proposals/{id}/convert", PROPOSAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcId\":\"fc-100\",\"contractorName\":\"김계약\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    }

    @Test
    @DisplayName("설계서 PDF 는 application/pdf 로 내려간다")
    void sheetReturnsPdf() throws Exception {
        when(renderUseCase.render(PROPOSAL_ID)).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mockMvc.perform(get("/api/insurance/proposals/{id}/sheet", PROPOSAL_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }
}
