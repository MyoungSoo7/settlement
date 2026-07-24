package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 셀러 세무 프로필 레지스트리 애그리거트 — 셀러의 세무유형(개인/사업자)·사업자등록번호의 정본.
 *
 * <p>원천징수 판단 입력이 정산·프로젝션에 없으므로, {@code SellerBankAccountRegistration}(payout 계좌
 * 레지스트리)과 <b>동형</b>으로 관리자 CRUD(upsert) 원천을 둔다. 신규 cross-service 프로젝션을 만들지 않고
 * settlement 자체에서 세무유형을 확보한다(ADR 0029 자족형).
 *
 * <p>사업자등록번호는 도메인에선 평문으로 다루고, 영속 어댑터({@code PayoutFieldEncryptionConverter})가
 * 저장 시 암호화한다(도메인 무오염). public setter 없음 — 생성·정정은 팩토리/도메인 메서드 전용.
 *
 * <p>불변식: 사업자(BUSINESS)는 사업자등록번호(10자리 숫자)가 필수, 개인(INDIVIDUAL)은 미보유(null).
 */
public class SellerTaxProfile {

    private final Long sellerId;
    private TaxType taxType;
    private String businessRegNo;   // 사업자등록번호(BUSINESS 만), 평문 — 어댑터가 암호화
    private LocalDateTime updatedAt;

    private SellerTaxProfile(Long sellerId, TaxType taxType, String businessRegNo, LocalDateTime updatedAt) {
        if (sellerId == null || sellerId <= 0) {
            throw new TaxInvariantViolationException("sellerId 는 양수여야 합니다: " + sellerId);
        }
        validate(taxType, businessRegNo);
        this.sellerId = sellerId;
        this.taxType = taxType;
        this.businessRegNo = normalizeRegNo(taxType, businessRegNo);
        this.updatedAt = updatedAt;
    }

    /** 신규 등록 — 지금 시각을 갱신 시각으로 찍는다. */
    public static SellerTaxProfile register(Long sellerId, TaxType taxType, String businessRegNo) {
        return new SellerTaxProfile(sellerId, taxType, businessRegNo, LocalDateTime.now());
    }

    /** 영속 복원 전용 — 저장된 갱신 시각을 그대로 보존한다. */
    public static SellerTaxProfile rehydrate(Long sellerId, TaxType taxType, String businessRegNo,
                                             LocalDateTime updatedAt) {
        return new SellerTaxProfile(sellerId, taxType, businessRegNo, updatedAt);
    }

    /** 세무유형·사업자등록번호 정정 — 유일한 변경 경로. */
    public void changeProfile(TaxType taxType, String businessRegNo) {
        validate(taxType, businessRegNo);
        this.taxType = taxType;
        this.businessRegNo = normalizeRegNo(taxType, businessRegNo);
        this.updatedAt = LocalDateTime.now();
    }

    private static void validate(TaxType taxType, String businessRegNo) {
        if (taxType == null) {
            throw new TaxInvariantViolationException("taxType 은 필수입니다");
        }
        if (taxType == TaxType.BUSINESS) {
            if (businessRegNo == null || !businessRegNo.replaceAll("-", "").matches("\\d{10}")) {
                throw new TaxInvariantViolationException("사업자 셀러는 10자리 사업자등록번호가 필수입니다");
            }
        }
    }

    /** BUSINESS 는 하이픈 제거한 10자리로 정규화, INDIVIDUAL 은 등록번호 미보유(null). */
    private static String normalizeRegNo(TaxType taxType, String businessRegNo) {
        if (taxType == TaxType.BUSINESS) {
            return businessRegNo.replaceAll("-", "");
        }
        return null;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public TaxType getTaxType() {
        return taxType;
    }

    public String getBusinessRegNo() {
        return businessRegNo;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** 로그·응답 노출용 마스킹 — 앞 3자리만 남기고 가린다(예: 123****** ). null 이면 빈 문자열. */
    public String maskedBusinessRegNo() {
        if (businessRegNo == null || businessRegNo.isBlank()) {
            return "";
        }
        int keep = Math.min(3, businessRegNo.length());
        return businessRegNo.substring(0, keep) + "*".repeat(businessRegNo.length() - keep);
    }
}
