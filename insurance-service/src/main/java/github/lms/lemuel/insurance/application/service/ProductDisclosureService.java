package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderDisclosurePdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveDisclosureDeliveryPort;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationOwnershipException;
import github.lms.lemuel.insurance.domain.exception.InvalidDisclosureException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 대면 상품설명서 — 렌더링 + 교부 기록 (완전판매 증빙).
 *
 * <p><b>해시는 서버가 계산한다</b>: 교부 기록의 document_sha256 은 서버가 방금 렌더링한
 * PDF 바이트에서 직접 계산한다. 클라이언트 입력을 신뢰하면 증빙이 위조 가능해진다.
 */
@Service
@Transactional
public class ProductDisclosureService
        implements RenderProductDisclosureUseCase, RecordDisclosureDeliveryUseCase {

    private final LoadInsuranceProductPort loadProductPort;
    private final LoadApplicationPort loadApplicationPort;
    private final RenderDisclosurePdfPort renderPdfPort;
    private final SaveDisclosureDeliveryPort saveDeliveryPort;
    private final AuditLogger auditLogger;

    public ProductDisclosureService(
            LoadInsuranceProductPort loadProductPort,
            LoadApplicationPort loadApplicationPort,
            RenderDisclosurePdfPort renderPdfPort,
            SaveDisclosureDeliveryPort saveDeliveryPort,
            AuditLogger auditLogger) {
        this.loadProductPort = loadProductPort;
        this.loadApplicationPort = loadApplicationPort;
        this.renderPdfPort = renderPdfPort;
        this.saveDeliveryPort = saveDeliveryPort;
        this.auditLogger = auditLogger;
    }

    @Override
    @Transactional(readOnly = true)
    public RenderedDisclosure render(String productCode) {
        ProductSnapshot product = loadActiveProduct(productCode);
        byte[] pdf = renderPdfPort.render(product);
        return new RenderedDisclosure(product.productCode(), product.productName(), pdf, sha256(pdf));
    }

    @Override
    public DeliveredDisclosure record(RecordDeliveryCommand cmd) {
        verifyAgainstApplication(cmd);
        ProductSnapshot product = loadActiveProduct(cmd.productCode());
        byte[] pdf = renderPdfPort.render(product);

        DisclosureDelivery delivery = DisclosureDelivery.record(
                cmd.applicationId(), product.productCode(), cmd.salesChannel(),
                cmd.deliveredBy(), cmd.partnerBankCode(), cmd.contractorName(), sha256(pdf));
        DisclosureDelivery saved = saveDeliveryPort.save(delivery, product);

        // 완전판매 증빙 생성은 규제 관점 감사 대상 — 교부 1건당 1건.
        auditLogger.record(AuditAction.INSURANCE_DISCLOSURE_DELIVERED,
                "DisclosureDelivery", saved.getDeliveryId(),
                String.format("{\"productCode\":\"%s\",\"channel\":\"%s\",\"deliveredBy\":\"%s\"}",
                        saved.getProductCode(), saved.getSalesChannel(), saved.getDeliveredBy()));

        // 교부 = 문서 발급 + 기록의 단일 행위 — 이 바이트의 해시가 방금 저장한 증빙이다.
        return new DeliveredDisclosure(saved, pdf);
    }

    /**
     * 청약을 참조한 교부라면 <b>그 청약과 일치하는지</b> 대조한다.
     *
     * <p>대조가 없으면 교부 증빙은 "청약과 무관한 아무 문서 1건"이 되고, 승인의 완전판매 게이트는
     * 그 1건으로 열린다 — 실제로는 계약자에게 아무것도 교부하지 않은 계약이 발행될 수 있다.
     *
     * <p>순서에 유의: <b>소유권(403)을 상품·채널 불일치(400)보다 먼저</b> 본다. 반대로 하면
     * 남의 청약이라도 필드를 맞춰 보며 존재 여부와 내용을 추측할 수 있다.
     *
     * <p>{@code applicationId} 가 없는 교부(청약 전 상담 단계 설명서)는 대조 대상이 아니다 —
     * 이때는 승인 게이트도 이 증빙을 쓰지 않는다.
     */
    private void verifyAgainstApplication(RecordDeliveryCommand cmd) {
        if (cmd.applicationId() == null || cmd.applicationId().isBlank()) {
            return;
        }
        InsuranceApplication application = loadApplicationPort.findByApplicationId(cmd.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(cmd.applicationId()));

        if (!application.getFcId().equals(cmd.deliveredBy())) {
            throw new ApplicationOwnershipException(cmd.applicationId());
        }
        if (!application.getProductCode().equals(cmd.productCode())) {
            throw new InvalidDisclosureException(
                    "청약 상품과 다른 상품설명서는 교부 증빙이 될 수 없습니다: 청약="
                            + application.getProductCode() + ", 교부=" + cmd.productCode());
        }
        if (application.getSalesChannel() != cmd.salesChannel()) {
            throw new InvalidDisclosureException(
                    "청약 판매채널과 다른 채널로는 교부할 수 없습니다: 청약="
                            + application.getSalesChannel() + ", 교부=" + cmd.salesChannel());
        }
        if (!application.getContractorName().equals(cmd.contractorName())) {
            // 계약자가 다르면 "그 사람에게 설명했다"는 증빙이 아니다. 이름은 응답에 싣지 않는다(PII).
            throw new InvalidDisclosureException("청약 계약자와 다른 이름으로는 교부할 수 없습니다");
        }
    }

    private ProductSnapshot loadActiveProduct(String productCode) {
        ProductSnapshot product = loadProductPort.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException(productCode));
        if (!product.active()) {
            // 판매 종료 상품의 설명서는 신규 교부 대상이 아니다 — 존재 자체를 숨긴다(404 동형).
            throw new ProductNotFoundException(productCode);
        }
        return product;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // JVM 필수 알고리즘 — 도달 불가
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
