package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidDisclosureException;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 대면 상품설명서 교부 이력 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p>완전판매 증빙의 도메인 표현이다: 누가(deliveredBy)·어느 채널로(salesChannel)·
 * 누구에게(contractorName)·어떤 문서를(documentSha256) 교부했는가.
 * append-only — 상태 전이가 없고 전 필드 불변이다.
 *
 * <p>채널 불변식은 V7 의 {@code chk_disclosure_banca_bank} 와 동형:
 * BANCA 교부 ↔ 판매 은행 존재.
 */
public final class DisclosureDelivery {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final Long id;
    private final String deliveryId;         // UUID — 멱등성 L1
    private final String applicationId;      // 청약 경유 교부 시 (nullable)
    private final String productCode;
    private final SalesChannel salesChannel;
    private final String deliveredBy;        // FC id 또는 은행 창구 직원 id
    private final String partnerBankCode;    // BANCA 교부 시 판매 은행 (nullable)
    private final String contractorName;
    private final String documentSha256;     // 교부 PDF 의 SHA-256 (hex 64자)

    private DisclosureDelivery(Long id, String deliveryId, String applicationId, String productCode,
                               SalesChannel salesChannel, String deliveredBy, String partnerBankCode,
                               String contractorName, String documentSha256) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.applicationId = applicationId;
        this.productCode = productCode;
        this.salesChannel = salesChannel;
        this.deliveredBy = deliveredBy;
        this.partnerBankCode = partnerBankCode;
        this.contractorName = contractorName;
        this.documentSha256 = documentSha256;
    }

    /**
     * 신규 교부 기록 — deliveryId 자동 채번.
     *
     * @throws InvalidDisclosureException 채널 불변식 위반·해시 형식 오류 시
     */
    public static DisclosureDelivery record(String applicationId, String productCode,
                                            SalesChannel salesChannel, String deliveredBy,
                                            String partnerBankCode, String contractorName,
                                            String documentSha256) {
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(salesChannel, "salesChannel");
        Objects.requireNonNull(deliveredBy, "deliveredBy");
        Objects.requireNonNull(contractorName, "contractorName");
        Objects.requireNonNull(documentSha256, "documentSha256");

        if ((salesChannel == SalesChannel.BANCA) != (partnerBankCode != null)) {
            throw new InvalidDisclosureException(
                    "BANCA 교부는 판매 은행이 필수이고, FC 교부는 판매 은행을 가질 수 없습니다: "
                            + "channel=" + salesChannel + ", partnerBankCode=" + partnerBankCode);
        }
        if (!SHA256_HEX.matcher(documentSha256).matches()) {
            throw new InvalidDisclosureException(
                    "documentSha256 은 소문자 hex 64자여야 합니다: " + documentSha256);
        }
        if (contractorName.isBlank()) {
            throw new InvalidDisclosureException("contractorName 은 빈 값일 수 없습니다");
        }
        return new DisclosureDelivery(null, UUID.randomUUID().toString(), applicationId, productCode,
                salesChannel, deliveredBy, partnerBankCode, contractorName, documentSha256);
    }

    /** DB 행 → 도메인 재구성 — 영속성 어댑터 전용. */
    public static DisclosureDelivery rehydrate(Long id, String deliveryId, String applicationId,
                                               String productCode, SalesChannel salesChannel,
                                               String deliveredBy, String partnerBankCode,
                                               String contractorName, String documentSha256) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deliveryId, "deliveryId");
        return new DisclosureDelivery(id, deliveryId, applicationId, productCode, salesChannel,
                deliveredBy, partnerBankCode, contractorName, documentSha256);
    }

    public Long getId() {
        return id;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getProductCode() {
        return productCode;
    }

    public SalesChannel getSalesChannel() {
        return salesChannel;
    }

    public String getDeliveredBy() {
        return deliveredBy;
    }

    public String getPartnerBankCode() {
        return partnerBankCode;
    }

    public String getContractorName() {
        return contractorName;
    }

    public String getDocumentSha256() {
        return documentSha256;
    }
}
