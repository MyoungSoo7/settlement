package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.RenderDisclosurePdfPort;
import github.lms.lemuel.insurance.application.port.out.SaveDisclosureDeliveryPort;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;
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
    private final RenderDisclosurePdfPort renderPdfPort;
    private final SaveDisclosureDeliveryPort saveDeliveryPort;
    private final AuditLogger auditLogger;

    public ProductDisclosureService(
            LoadInsuranceProductPort loadProductPort,
            RenderDisclosurePdfPort renderPdfPort,
            SaveDisclosureDeliveryPort saveDeliveryPort,
            AuditLogger auditLogger) {
        this.loadProductPort = loadProductPort;
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
