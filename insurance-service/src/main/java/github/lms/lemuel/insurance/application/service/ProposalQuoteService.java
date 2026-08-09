package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.ConvertProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;
import github.lms.lemuel.insurance.application.port.in.ExpireProposalsUseCase;
import github.lms.lemuel.insurance.application.port.in.GetProposalUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProposalSheetUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.LoadProposalPort;
import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort;
import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort.RateSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderProposalSheetPdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort.ApplicationPii;
import github.lms.lemuel.insurance.application.port.out.SaveProposalPort;
import github.lms.lemuel.insurance.domain.AppliedRate;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.PremiumRater;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException;
import github.lms.lemuel.insurance.domain.exception.RateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 가입설계 오케스트레이션 — 산출·조회·청약 전환·만기 스윕·설계서 렌더링.
 *
 * <p><b>전환(convert)이 이 서비스의 돈 경로 입구다</b> — 한 트랜잭션에서:
 * <ol>
 *   <li>소유권 대조: 요청자 fcId ≠ 설계 fcId 면 거부 (IDOR 차단)</li>
 *   <li>청약 생성: 보장금액·보험료를 <b>설계 스냅샷에서 서버가 주입</b> (D-P3 — 클라이언트
 *       입력 금액을 신뢰하지 않는다)</li>
 *   <li>설계 CONVERTED 전이 (유효기간·1설계 1청약은 도메인+DB UNIQUE 가 강제)</li>
 * </ol>
 * 어느 단계가 실패해도 전체 롤백 — 청약만 생기고 설계가 QUOTED 로 남는 반쪽 전환은 없다.
 */
@Service
@Transactional
public class ProposalQuoteService implements CreateProposalUseCase, GetProposalUseCase,
        ConvertProposalUseCase, ExpireProposalsUseCase, RenderProposalSheetUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProposalQuoteService.class);

    private final LoadInsuranceProductPort loadProductPort;
    private final LoadRateTablePort loadRateTablePort;
    private final LoadProposalPort loadProposalPort;
    private final SaveProposalPort saveProposalPort;
    private final SaveApplicationPort saveApplicationPort;
    private final RenderProposalSheetPdfPort renderPdfPort;
    private final Clock clock;

    public ProposalQuoteService(
            LoadInsuranceProductPort loadProductPort,
            LoadRateTablePort loadRateTablePort,
            LoadProposalPort loadProposalPort,
            SaveProposalPort saveProposalPort,
            SaveApplicationPort saveApplicationPort,
            RenderProposalSheetPdfPort renderPdfPort,
            Clock clock) {
        this.loadProductPort = loadProductPort;
        this.loadRateTablePort = loadRateTablePort;
        this.loadProposalPort = loadProposalPort;
        this.saveProposalPort = saveProposalPort;
        this.saveApplicationPort = saveApplicationPort;
        this.renderPdfPort = renderPdfPort;
        this.clock = clock;
    }

    @Override
    public ProposalSummary create(CreateProposalCommand cmd) {
        // 판매 종료 상품 설계는 입구에서 거부 (청약 접수와 동일 원칙)
        requireActiveProduct(cmd.productCode());

        LocalDate today = LocalDate.now(clock);
        int insuranceAge = PremiumRater.insuranceAge(cmd.insuredBirthDate(), today);
        RateSnapshot rate = loadRateTablePort
                .findApplicableRate(cmd.productCode(), cmd.insuredGender(),
                        insuranceAge, cmd.paymentTermYears(), today)
                .orElseThrow(() -> new RateNotFoundException(
                        cmd.productCode(), cmd.insuredGender(), insuranceAge, cmd.paymentTermYears()));

        ProposalQuote proposal = ProposalQuote.quote(
                cmd.consultationId(), cmd.productCode(), cmd.fcId(),
                cmd.insuredName(), cmd.insuredGender(), insuranceAge,
                cmd.coverageAmount(), cmd.paymentTermYears(),
                new AppliedRate(rate.rateTableId(), rate.ratePerMille()),
                cmd.salesChannel(), cmd.partnerBankCode(), today);

        ProposalQuote saved = saveProposalPort.insertNew(proposal);
        log.info("[Proposal] 산출: proposal={} product={} age={} rateTableId={} premium={}",
                saved.getProposalId(), saved.getProductCode(), saved.getInsuranceAge(),
                saved.getRateTableId(), saved.getAnnualPremium().toPlainString());
        return ProposalSummary.from(saved);
    }

    @Override
    public ProposalSummary get(String proposalId) {
        return ProposalSummary.from(loadRequired(proposalId));
    }

    @Override
    public ConversionResult convert(ConvertProposalCommand cmd) {
        ProposalQuote proposal = loadRequired(cmd.proposalId());

        // 소유권 대조 — 설계 작성자 본인만 전환할 수 있다 (IDOR 차단, 403)
        if (!proposal.getFcId().equals(cmd.requesterFcId())) {
            throw new ProposalOwnershipException(proposal.getProposalId());
        }
        requireActiveProduct(proposal.getProductCode());

        // D-P3: 보장금액·보험료는 설계 스냅샷의 산출값을 서버가 주입 — 요청 금액은 받지 않는다
        InsuranceApplication application = InsuranceApplication.submit(
                proposal.getConsultationId(), proposal.getProductCode(), proposal.getFcId(),
                proposal.getInsuredName(), cmd.contractorName(),
                proposal.getCoverageAmount(), proposal.getAnnualPremium(),
                proposal.getSalesChannel(), proposal.getPartnerBankCode());

        // 유효기간·중복 전환은 도메인이 강제 — 실패 시 청약 저장 전에 끊긴다
        proposal.convert(application.getApplicationId(), LocalDate.now(clock));

        InsuranceApplication savedApplication = saveApplicationPort.saveNew(
                application, new ApplicationPii(cmd.insuredRrn(), cmd.contractorPhone()));
        saveProposalPort.update(proposal);

        log.info("[Proposal] 청약 전환: proposal={} application={} premium={}",
                proposal.getProposalId(), savedApplication.getApplicationId(),
                proposal.getAnnualPremium().toPlainString());
        return new ConversionResult(proposal.getProposalId(),
                savedApplication.getApplicationId(), proposal.getAnnualPremium());
    }

    @Override
    public int expireOn(LocalDate today) {
        int expired = 0;
        for (ProposalQuote proposal : loadProposalPort.findQuotedValidUntilBefore(today)) {
            proposal.expire(today);
            saveProposalPort.update(proposal);
            expired++;
        }
        if (expired > 0) {
            log.info("[Proposal] 만기 스윕: today={} expired={}", today, expired);
        }
        return expired;
    }

    @Override
    public byte[] render(String proposalId) {
        ProposalQuote proposal = loadRequired(proposalId);
        ProductSnapshot product = loadProductPort.findByCode(proposal.getProductCode())
                .orElseThrow(() -> new ProductNotFoundException(proposal.getProductCode()));
        return renderPdfPort.render(proposal, product);
    }

    private ProposalQuote loadRequired(String proposalId) {
        return loadProposalPort.findByProposalId(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
    }

    private void requireActiveProduct(String productCode) {
        ProductSnapshot product = loadProductPort.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException(productCode));
        if (!product.active()) {
            throw new ProductNotFoundException(productCode);
        }
    }
}
