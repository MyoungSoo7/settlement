package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.TaxRounding;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 세금계산서 스캔본에서 OCR 이 뽑아낸 필드 묶음(불변 VO).
 *
 * <p><b>구조는 강제하고 내용은 판정한다</b> — 필수값 누락·음수 금액·범위 밖 신뢰도처럼 어떤 계산서도
 * 가질 수 없는 <i>구조적</i> 파손은 {@link TaxInvariantViolationException} 으로 막지만, 세액이 공급가액의
 * 10% 와 어긋나는 것은 스캔본이 실제로 그럴 수도, OCR 이 잘못 읽었을 수도 있으므로 예외가 아니라
 * {@link #needsReview(BigDecimal)} 사유다. 신뢰할 수 없는 입력을 예외로 처리하면 업로드 자체가 죽는다.
 *
 * <p>세액 기대값은 <b>외부과세</b>(공급가액 × 10%, 원단위 절사)다. 플랫폼 수수료 계산서를 다루는
 * {@code TaxCalculation} 의 <b>포함과세</b>(10/110)와 과세 방향이 반대이므로 상수를 공유하지 않는다.
 *
 * <p>신뢰도가 {@link BigDecimal} 인 이유: 금액 스코프에서 double/float 은 금지(MONEY-PRIMITIVE 가드)이며,
 * 임계값 비교를 부동소수 오차 없이 하려는 의도도 겸한다.
 */
public record ExtractedTaxInvoice(BusinessRegistrationNumber supplier,
                                  BusinessRegistrationNumber buyer,
                                  LocalDate writtenDate,
                                  BigDecimal supplyAmount,
                                  BigDecimal taxAmount,
                                  BigDecimal totalAmount,
                                  String approvalNumber,
                                  BigDecimal confidence) {

    /** 부가가치세율 — 외부과세(공급가액 기준). */
    private static final BigDecimal VAT_RATE = new BigDecimal("0.1");

    public ExtractedTaxInvoice {
        if (supplier == null || buyer == null) {
            throw new TaxInvariantViolationException("사업자등록번호 값 객체는 null 일 수 없습니다(미인식은 of(null))");
        }
        if (writtenDate == null) {
            throw new TaxInvariantViolationException("작성일자는 필수입니다");
        }
        requireNonNegative(supplyAmount, "공급가액");
        requireNonNegative(taxAmount, "세액");
        requireNonNegative(totalAmount, "합계금액");
        if (confidence == null) {
            throw new TaxInvariantViolationException("신뢰도는 필수입니다");
        }
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new TaxInvariantViolationException("신뢰도는 0~1 범위여야 합니다: " + confidence.toPlainString());
        }
    }

    /** OCR 원문 문자열에서 만든다 — 사업자번호는 값 객체로 정규화된다. */
    public static ExtractedTaxInvoice of(String supplierRaw, String buyerRaw, LocalDate writtenDate,
                                         BigDecimal supplyAmount, BigDecimal taxAmount,
                                         BigDecimal totalAmount, String approvalNumber,
                                         BigDecimal confidence) {
        return new ExtractedTaxInvoice(BusinessRegistrationNumber.of(supplierRaw),
                BusinessRegistrationNumber.of(buyerRaw), writtenDate,
                supplyAmount, taxAmount, totalAmount, trimToNull(approvalNumber), confidence);
    }

    /** 합계 = 공급가액 + 세액 인가 (scale 무시, 값 비교). */
    public boolean totalConsistent() {
        return totalAmount.compareTo(supplyAmount.add(taxAmount)) == 0;
    }

    /** 공급가액 기준 부가세 기대값 — 원단위 절사. */
    public BigDecimal expectedTaxAmount() {
        return TaxRounding.floorToWon(supplyAmount.multiply(VAT_RATE));
    }

    /** 읽어낸 세액이 기대 부가세와 같은가. */
    public boolean vatConsistent() {
        return taxAmount.compareTo(expectedTaxAmount()) == 0;
    }

    /**
     * 사람이 봐야 하는가 — 신뢰도 미달, 산술 불일치(합계/부가세), 공급자 사업자번호 체크섬 실패 중 하나라도.
     *
     * @param confidenceThreshold 이 값 <b>미만</b>이면 리뷰 대상(임계값 자체는 통과)
     */
    public boolean needsReview(BigDecimal confidenceThreshold) {
        if (confidenceThreshold == null) {
            throw new TaxInvariantViolationException("신뢰도 임계값은 필수입니다");
        }
        return confidence.compareTo(confidenceThreshold) < 0
                || !totalConsistent()
                || !vatConsistent()
                || !supplier.isValid();
    }

    private static void requireNonNegative(BigDecimal amount, String label) {
        if (amount == null) {
            throw new TaxInvariantViolationException(label + "은(는) 필수입니다");
        }
        if (amount.signum() < 0) {
            throw new TaxInvariantViolationException(label + "은(는) 음수일 수 없습니다: " + amount.toPlainString());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
