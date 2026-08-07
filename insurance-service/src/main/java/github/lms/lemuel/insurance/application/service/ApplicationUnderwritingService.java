package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadDisclosureDeliveryPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort.ApplicationPii;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort.PolicyIssuanceAttributes;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionScheduleFactory;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.DisclosureNotDeliveredException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 청약 접수·언더라이팅 오케스트레이션.
 *
 * <p><b>승인(approve)이 이 서비스의 돈 경로다</b> — 한 트랜잭션에서:
 * <ol>
 *   <li>완전판매 게이트: 교부 증빙 없으면 승인 자체를 거부 (상태 불변)</li>
 *   <li>청약 APPROVED 전이 (도메인 상태머신)</li>
 *   <li>계약 발행: {@code Policy.issue} — 효력일 = 승인일(KST)</li>
 *   <li>초년도 수수료 총액 = 연 보험료 × 상품 초년도 수수료율 (통화 최소단위 절사) →
 *       D4 12회 스케줄 확정 (1회차 due = 효력일)</li>
 *   <li>policy_issued + commission_confirmed Outbox 발행</li>
 * </ol>
 * 어느 단계가 실패해도 전체 롤백 — 계약만 생기고 수수료가 없는 반쪽 승인은 존재하지 않는다.
 */
@Service
@Transactional
public class ApplicationUnderwritingService
        implements SubmitApplicationUseCase, UnderwriteApplicationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplicationUnderwritingService.class);

    /** 통화 최소단위 스케일 — 수수료 총액 절사 기준 (CommissionConstants.AMOUNT_SCALE 와 동일 값 의미). */
    private static final int AMOUNT_SCALE = 2;

    private final LoadApplicationPort loadApplicationPort;
    private final SaveApplicationPort saveApplicationPort;
    private final LoadInsuranceProductPort loadProductPort;
    private final LoadDisclosureDeliveryPort loadDisclosurePort;
    private final SavePolicyPort savePolicyPort;
    private final SaveCommissionSchedulePort saveSchedulePort;
    private final PublishInsuranceEventPort publishPort;
    private final Clock clock;
    private final AuditLogger auditLogger;

    public ApplicationUnderwritingService(
            LoadApplicationPort loadApplicationPort,
            SaveApplicationPort saveApplicationPort,
            LoadInsuranceProductPort loadProductPort,
            LoadDisclosureDeliveryPort loadDisclosurePort,
            SavePolicyPort savePolicyPort,
            SaveCommissionSchedulePort saveSchedulePort,
            PublishInsuranceEventPort publishPort,
            Clock clock,
            AuditLogger auditLogger) {
        this.loadApplicationPort = loadApplicationPort;
        this.saveApplicationPort = saveApplicationPort;
        this.loadProductPort = loadProductPort;
        this.loadDisclosurePort = loadDisclosurePort;
        this.savePolicyPort = savePolicyPort;
        this.saveSchedulePort = saveSchedulePort;
        this.publishPort = publishPort;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    @Override
    public String submit(SubmitApplicationCommand cmd) {
        // 판매 종료 상품 청약은 접수 자체를 거부한다 — 승인 시점이 아니라 입구에서.
        requireActiveProduct(cmd.productCode());

        InsuranceApplication application = InsuranceApplication.submit(
                cmd.consultationId(), cmd.productCode(), cmd.fcId(),
                cmd.insuredName(), cmd.contractorName(),
                cmd.desiredCoverage(), cmd.desiredPremium(),
                cmd.salesChannel(), cmd.partnerBankCode());

        InsuranceApplication saved = saveApplicationPort.saveNew(
                application, new ApplicationPii(cmd.insuredRrn(), cmd.contractorPhone()));
        return saved.getApplicationId();
    }

    @Override
    public void startReview(String applicationId) {
        InsuranceApplication application = loadRequired(applicationId);
        application.startReview();
        saveApplicationPort.update(application);
    }

    @Override
    public IssuedPolicySummary approve(String applicationId) {
        InsuranceApplication application = loadRequired(applicationId);

        // 완전판매 게이트 — 상태 전이 전에 검사해 실패 시 청약이 UNDER_REVIEW 로 남는다.
        if (!loadDisclosurePort.existsForApplication(applicationId)) {
            throw new DisclosureNotDeliveredException(applicationId);
        }
        ProductSnapshot product = requireActiveProduct(application.getProductCode());

        application.approve();
        saveApplicationPort.update(application);

        LocalDate effectiveDate = LocalDate.now(clock);
        Policy policy = Policy.issue(
                effectiveDate,
                null,  // 만기 미지정(종신형 취급) — 상품 보험기간 도입 시 여기서 산출
                application.getDesiredPremium(),
                application.getFcId(),
                application.getSalesChannel(),
                application.getPartnerBankCode());
        Policy issued = savePolicyPort.insertIssued(policy, new PolicyIssuanceAttributes(
                application.getApplicationId(),
                application.getProductCode(),
                application.getDesiredCoverage(),
                1));

        // 초년도 수수료 총액 — 연 보험료 × 초년도 수수료율, 통화 최소단위 절사(DOWN).
        BigDecimal firstYearTotal = application.getDesiredPremium()
                .multiply(product.firstYearCommissionRate())
                .setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                issued.getPolicyId(),
                issued.commissionRecipientId(),
                firstYearTotal,
                product.firstYearCommissionRate(),
                issued.getSalesChannel(),
                effectiveDate);
        List<CommissionSchedule> savedSchedules = saveSchedulePort.saveAll(schedules);

        publishPort.publishPolicyIssued(issued, application.getDesiredCoverage());
        publishPort.publishCommissionConfirmed(issued.getPolicyNumber(), savedSchedules);

        // 계약 발행 = 보장 개시 + 수수료 확정의 금전적 사건 — 건 단위 감사.
        auditLogger.record(AuditAction.INSURANCE_POLICY_ISSUED,
                "Policy", issued.getPolicyNumber(),
                String.format("{\"applicationId\":\"%s\",\"channel\":\"%s\",\"firstYearTotal\":\"%s\"}",
                        application.getApplicationId(), issued.getSalesChannel(),
                        firstYearTotal.toPlainString()));

        log.info("[Underwriting] 승인·발행: application={} policy={} firstYearTotal={}",
                application.getApplicationId(), issued.getPolicyNumber(), firstYearTotal.toPlainString());
        return new IssuedPolicySummary(
                application.getApplicationId(), issued.getPolicyId(), issued.getPolicyNumber(),
                firstYearTotal, savedSchedules.size());
    }

    @Override
    public void reject(String applicationId, String reason) {
        InsuranceApplication application = loadRequired(applicationId);
        application.reject(reason);
        saveApplicationPort.update(application);
    }

    private InsuranceApplication loadRequired(String applicationId) {
        return loadApplicationPort.findByApplicationId(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    private ProductSnapshot requireActiveProduct(String productCode) {
        ProductSnapshot product = loadProductPort.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException(productCode));
        if (!product.active()) {
            throw new ProductNotFoundException(productCode);
        }
        return product;
    }
}
