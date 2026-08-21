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
 *
 * <p><b>신뢰도는 판정에 쓰이는 축마다 따로 갖는다.</b> 하나로 합쳐 두면 또렷하게 읽힌 필드의 확신이
 * 뭉개진 필드의 불확실성을 덮는다(card 의 영수증 OCR 에서 실제로 그 일이 났다 — 총액을 잘 읽었다는
 * 이유로 반사광에 덮인 거래일까지 0.98 을 주장했다). 여기서 갈리는 축은 둘이다:
 *
 * <ul>
 *   <li><b>금액 3종</b> — 서로 산술로 교차검증된다({@link #totalConsistent()}·{@link #vatConsistent()}).
 *       셋이 함께 맞아떨어지면 개별 신뢰도보다 강한 근거라 하나로 묶어 본다.</li>
 *   <li><b>승인번호</b> — 대사의 <i>탐색 키</i>인데 교차검증할 상대가 없다. 이게 뭉개지면 왕복 검증이
 *       실패해 "발행분을 못 찾았다"(UNMATCHED)는 <b>틀린 결론이 기록된다</b>. 금액을 아무리 잘 읽어도
 *       그 사실이 승인번호에 대해 보장하는 것은 없다.</li>
 * </ul>
 *
 * <p>작성일자는 대사 판정에 쓰이지 않으므로 신뢰도를 따로 두지 않는다 — 판정하지 않는 필드에
 * 신뢰도를 붙이면 임계 비교만 늘고 의미는 없다.
 */
public record ExtractedTaxInvoice(BusinessRegistrationNumber supplier,
                                  BusinessRegistrationNumber buyer,
                                  LocalDate writtenDate,
                                  BigDecimal supplyAmount,
                                  BigDecimal taxAmount,
                                  BigDecimal totalAmount,
                                  String approvalNumber,
                                  BigDecimal amountConfidence,
                                  BigDecimal approvalNumberConfidence) {

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
        requireConfidence(amountConfidence, "금액 판독 신뢰도");
        requireConfidence(approvalNumberConfidence, "승인번호 판독 신뢰도");
    }

    private static void requireConfidence(BigDecimal value, String label) {
        if (value == null) {
            throw new TaxInvariantViolationException(label + "는 필수입니다");
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new TaxInvariantViolationException(
                    label + "는 0~1 범위여야 합니다: " + value.toPlainString());
        }
    }

    /**
     * 가장 못 믿는 축의 신뢰도 — <b>화면 표시용이다. 판정에 쓰지 말 것.</b>
     *
     * <p>판정에 쓰는 순간 이 타입이 없애려던 결함(한 축이 다른 축을 덮는 것)이 되돌아온다.
     */
    public BigDecimal weakestConfidence() {
        return amountConfidence.min(approvalNumberConfidence);
    }

    /** OCR 원문 문자열에서 만든다 — 사업자번호는 값 객체로 정규화된다. */
    public static ExtractedTaxInvoice of(String supplierRaw, String buyerRaw, LocalDate writtenDate,
                                         BigDecimal supplyAmount, BigDecimal taxAmount,
                                         BigDecimal totalAmount, String approvalNumber,
                                         BigDecimal amountConfidence,
                                         BigDecimal approvalNumberConfidence) {
        return new ExtractedTaxInvoice(BusinessRegistrationNumber.of(supplierRaw),
                BusinessRegistrationNumber.of(buyerRaw), writtenDate,
                supplyAmount, taxAmount, totalAmount, trimToNull(approvalNumber),
                amountConfidence, approvalNumberConfidence);
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
     * 사람이 봐야 하는가 — <b>어느 한 축이라도</b> 신뢰도 미달이거나, 산술 불일치(합계/부가세),
     * 공급자 사업자번호 체크섬 실패 중 하나라도 해당하면 참이다.
     *
     * <p>금액을 또렷하게 읽었다는 사실은 승인번호에 대해 아무것도 보장하지 않는다. 그래서 두 축을
     * 각각 임계와 비교한다 — 합쳐 놓으면 한쪽의 확신이 다른 쪽의 불확실성을 덮는다.
     *
     * @param confidenceThreshold 이 값 <b>미만</b>이면 리뷰 대상(임계값 자체는 통과)
     */
    public boolean needsReview(BigDecimal confidenceThreshold) {
        if (confidenceThreshold == null) {
            throw new TaxInvariantViolationException("신뢰도 임계값은 필수입니다");
        }
        return amountConfidence.compareTo(confidenceThreshold) < 0
                || approvalNumberConfidence.compareTo(confidenceThreshold) < 0
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
