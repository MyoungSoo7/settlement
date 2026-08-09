package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConversionResult;
import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase.ConvertProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.CreateProposalCommand;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.LoadProposalPort;
import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort;
import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort.RateSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderProposalSheetPdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort;
import github.lms.lemuel.insurance.application.port.out.SaveProposalPort;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import github.lms.lemuel.insurance.domain.ProposalStatus;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;
import github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException;
import github.lms.lemuel.insurance.domain.exception.RateNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가입설계 오케스트레이션 테스트.
 *
 * <p>핵심 어서션은 D-P3: 전환 시 청약의 보장금액·보험료가 <b>설계 스냅샷의 산출값</b>이다 —
 * 클라이언트 입력 금액이 끼어들 자리가 없다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProposalQuoteService — 산출·전환·만기 스윕")
class ProposalQuoteServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final String PROPOSAL_ID = "11111111-2222-3333-4444-555555555555";

    @Mock LoadInsuranceProductPort loadProductPort;
    @Mock LoadRateTablePort loadRateTablePort;
    @Mock LoadProposalPort loadProposalPort;
    @Mock SaveProposalPort saveProposalPort;
    @Mock SaveApplicationPort saveApplicationPort;
    @Mock RenderProposalSheetPdfPort renderPdfPort;

    private ProposalQuoteService service() {
        Clock fixed = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        return new ProposalQuoteService(loadProductPort, loadRateTablePort, loadProposalPort,
                saveProposalPort, saveApplicationPort, renderPdfPort, fixed);
    }

    private static ProductSnapshot activeProduct() {
        return new ProductSnapshot("PROD-LIFE-01", "종신보험", "LIFE",
                new BigDecimal("300000.00"), new BigDecimal("100000000.00"),
                new BigDecimal("0.035000"), "INS-01", true);
    }

    private static ProposalQuote quotedProposal(LocalDate validUntil) {
        return ProposalQuote.builder()
                .id(5L)
                .proposalId(PROPOSAL_ID)
                .productCode("PROD-LIFE-01")
                .fcId("fc-100")
                .insuredName("홍길동")
                .insuredGender(Gender.M)
                .insuranceAge(37)
                .coverageAmount(new BigDecimal("100000000"))
                .paymentTermYears(20)
                .rateTableId(11L)
                .appliedRatePerMille(new BigDecimal("2.5"))
                .annualPremium(new BigDecimal("250000"))
                .salesChannel(SalesChannel.FC)
                .status(ProposalStatus.QUOTED)
                .quotedOn(validUntil.minusDays(ProposalQuote.VALIDITY_DAYS))
                .validUntil(validUntil)
                .build();
    }

    @Test
    @DisplayName("산출: 보험나이를 계산해 요율을 찾고, 산출 스냅샷을 저장한다")
    void createComputesAgeAndQuotes() {
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(activeProduct()));
        // 1990-01-15 생 → 2026-08-07 기준 만 36세 + 6개월 경과 = 보험나이 37
        when(loadRateTablePort.findApplicableRate("PROD-LIFE-01", Gender.M, 37, 20, TODAY))
                .thenReturn(Optional.of(new RateSnapshot(11L, new BigDecimal("2.5"))));
        when(saveProposalPort.insertNew(any())).thenAnswer(inv -> inv.getArgument(0));

        ProposalSummary summary = service().create(new CreateProposalCommand(
                null, "PROD-LIFE-01", "fc-100", "홍길동",
                LocalDate.of(1990, 1, 15), Gender.M,
                new BigDecimal("100000000"), 20, SalesChannel.FC, null));

        assertThat(summary.insuranceAge()).isEqualTo(37);
        assertThat(summary.annualPremium()).isEqualByComparingTo("250000");
        assertThat(summary.appliedRatePerMille()).isEqualByComparingTo("2.5");
        assertThat(summary.status()).isEqualTo("QUOTED");
        assertThat(summary.validUntil()).isEqualTo(TODAY.plusDays(ProposalQuote.VALIDITY_DAYS));
    }

    @Test
    @DisplayName("산출: 판매 종료 상품은 입구에서 거부한다")
    void createRejectsInactiveProduct() {
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(
                new ProductSnapshot("PROD-LIFE-01", "종신보험", "LIFE",
                        new BigDecimal("300000.00"), new BigDecimal("100000000.00"),
                        new BigDecimal("0.035000"), "INS-01", false)));

        assertThatThrownBy(() -> service().create(new CreateProposalCommand(
                null, "PROD-LIFE-01", "fc-100", "홍길동",
                LocalDate.of(1990, 1, 15), Gender.M,
                new BigDecimal("100000000"), 20, SalesChannel.FC, null)))
                .isInstanceOf(ProductNotFoundException.class);
        verify(saveProposalPort, never()).insertNew(any());
    }

    @Test
    @DisplayName("산출: 적용 요율이 없으면 폴백 없이 거부한다")
    void createRejectsWhenNoRate() {
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(activeProduct()));
        when(loadRateTablePort.findApplicableRate(anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(new CreateProposalCommand(
                null, "PROD-LIFE-01", "fc-100", "홍길동",
                LocalDate.of(1990, 1, 15), Gender.M,
                new BigDecimal("100000000"), 20, SalesChannel.FC, null)))
                .isInstanceOf(RateNotFoundException.class);
        verify(saveProposalPort, never()).insertNew(any());
    }

    @Test
    @DisplayName("전환: 청약의 보장금액·보험료는 설계 스냅샷의 산출값이다 (D-P3 서버 주입)")
    void convertInjectsServerAmounts() {
        ProposalQuote proposal = quotedProposal(TODAY.plusDays(10));
        when(loadProposalPort.findByProposalId(PROPOSAL_ID)).thenReturn(Optional.of(proposal));
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(activeProduct()));
        when(saveApplicationPort.saveNew(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        ConversionResult result = service().convert(new ConvertProposalCommand(
                PROPOSAL_ID, "fc-100", "김계약", null, null));

        ArgumentCaptor<InsuranceApplication> appCaptor =
                ArgumentCaptor.forClass(InsuranceApplication.class);
        verify(saveApplicationPort).saveNew(appCaptor.capture(), any());
        InsuranceApplication application = appCaptor.getValue();
        // 서버 주입 — 설계 산출값이 그대로 청약에 실린다
        assertThat(application.getDesiredPremium()).isEqualByComparingTo("250000");
        assertThat(application.getDesiredCoverage()).isEqualByComparingTo("100000000");
        assertThat(application.getFcId()).isEqualTo("fc-100");
        assertThat(application.getContractorName()).isEqualTo("김계약");

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.CONVERTED);
        assertThat(proposal.getConvertedApplicationId()).isEqualTo(application.getApplicationId());
        verify(saveProposalPort).update(proposal);
        assertThat(result.applicationId()).isEqualTo(application.getApplicationId());
        assertThat(result.annualPremium()).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("전환: 다른 설계사의 설계는 거부한다 (소유권 대조)")
    void convertRejectsForeignProposal() {
        when(loadProposalPort.findByProposalId(PROPOSAL_ID))
                .thenReturn(Optional.of(quotedProposal(TODAY.plusDays(10))));

        assertThatThrownBy(() -> service().convert(new ConvertProposalCommand(
                PROPOSAL_ID, "fc-999", "김계약", null, null)))
                .isInstanceOf(ProposalOwnershipException.class);
        verify(saveApplicationPort, never()).saveNew(any(), any());
        verify(saveProposalPort, never()).update(any());
    }

    @Test
    @DisplayName("전환: 유효기간이 지난 설계는 거부한다 — 청약도 만들어지지 않는다")
    void convertRejectsExpiredProposal() {
        ProposalQuote proposal = quotedProposal(TODAY.minusDays(1));
        when(loadProposalPort.findByProposalId(PROPOSAL_ID)).thenReturn(Optional.of(proposal));
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(activeProduct()));

        assertThatThrownBy(() -> service().convert(new ConvertProposalCommand(
                PROPOSAL_ID, "fc-100", "김계약", null, null)))
                .isInstanceOf(ProposalExpiredException.class);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.QUOTED);
        verify(saveApplicationPort, never()).saveNew(any(), any());
        verify(saveProposalPort, never()).update(any());
    }

    @Test
    @DisplayName("만기 스윕: 유효기한 경과 QUOTED 설계를 EXPIRED 로 전이한다")
    void expireSweepsOverdueProposals() {
        ProposalQuote p1 = quotedProposal(TODAY.minusDays(1));
        ProposalQuote p2 = quotedProposal(TODAY.minusDays(5));
        when(loadProposalPort.findQuotedValidUntilBefore(TODAY)).thenReturn(List.of(p1, p2));

        int expired = service().expireOn(TODAY);

        assertThat(expired).isEqualTo(2);
        assertThat(p1.getStatus()).isEqualTo(ProposalStatus.EXPIRED);
        assertThat(p2.getStatus()).isEqualTo(ProposalStatus.EXPIRED);
        verify(saveProposalPort).update(p1);
        verify(saveProposalPort).update(p2);
    }

    @Test
    @DisplayName("만기 스윕: 대상이 없으면 0 건이다")
    void expireReturnsZeroWhenNothing() {
        when(loadProposalPort.findQuotedValidUntilBefore(TODAY)).thenReturn(List.of());

        assertThat(service().expireOn(TODAY)).isZero();
        verify(saveProposalPort, never()).update(any());
    }

    @Test
    @DisplayName("설계서 렌더링: 설계 + 상품 스냅샷을 PDF 포트에 넘긴다")
    void renderDelegatesToPdfPort() {
        ProposalQuote proposal = quotedProposal(TODAY.plusDays(10));
        ProductSnapshot product = activeProduct();
        when(loadProposalPort.findByProposalId(PROPOSAL_ID)).thenReturn(Optional.of(proposal));
        when(loadProductPort.findByCode("PROD-LIFE-01")).thenReturn(Optional.of(product));
        when(renderPdfPort.render(proposal, product)).thenReturn(new byte[]{1, 2, 3});

        assertThat(service().render(PROPOSAL_ID, "fc-100"))
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(renderPdfPort).render(eq(proposal), eq(product));
    }

    @Test
    @DisplayName("조회: 본인 설계는 산출 근거까지 돌려준다")
    void getReturnsOwnProposal() {
        when(loadProposalPort.findByProposalId(PROPOSAL_ID))
                .thenReturn(Optional.of(quotedProposal(TODAY.plusDays(10))));

        ProposalSummary summary = service().get(PROPOSAL_ID, "fc-100");

        assertThat(summary.proposalId()).isEqualTo(PROPOSAL_ID);
        assertThat(summary.annualPremium()).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("조회: 다른 설계사의 설계는 거부한다 (IDOR 차단)")
    void getRejectsForeignProposal() {
        when(loadProposalPort.findByProposalId(PROPOSAL_ID))
                .thenReturn(Optional.of(quotedProposal(TODAY.plusDays(10))));

        assertThatThrownBy(() -> service().get(PROPOSAL_ID, "fc-999"))
                .isInstanceOf(ProposalOwnershipException.class);
    }

    @Test
    @DisplayName("설계서 렌더링: 다른 설계사의 설계는 거부한다 — PDF 를 만들지 않는다")
    void renderRejectsForeignProposal() {
        when(loadProposalPort.findByProposalId(PROPOSAL_ID))
                .thenReturn(Optional.of(quotedProposal(TODAY.plusDays(10))));

        assertThatThrownBy(() -> service().render(PROPOSAL_ID, "fc-999"))
                .isInstanceOf(ProposalOwnershipException.class);
        verify(renderPdfPort, never()).render(any(), any());
    }
}
