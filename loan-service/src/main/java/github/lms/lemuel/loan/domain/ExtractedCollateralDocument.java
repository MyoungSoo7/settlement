package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 담보서류(감정평가서·등기부) OCR 추출 결과 VO (순수 도메인, 불변 — ADR 0036 확산).
 *
 * <p>감정평가액·신뢰도는 필수 — 평가액을 못 읽은 추출은 대사 근거가 될 수 없어 어댑터가 503 으로
 * 끊는다(무폴백). 선순위 채권최고액·평가기준일·소유자·소재지는 판독 실패를 {@code null} 로 표현한다.
 *
 * @param ownerName         소유자 성명(등기부 갑구) — 참고 정보. 담보제공자≠차주(제3자 담보)를 도메인이
 *                          표현하지 못하므로 판정 축으로 쓰지 않는다
 * @param locationText      소재지 표시 — 참고 정보 (담보 description 은 자유 텍스트라 자동 대사 불가)
 * @param appraisedValue    감정평가액 (필수·양수, BigDecimal 강제)
 * @param seniorClaimAmount 선순위 채권최고액(등기부 을구) — 판독 실패 null, 존재 시 0 이상
 * @param appraisalDate     평가기준일 (판독 실패 null)
 * @param confidence        판독 신뢰도 0~1 (필수)
 */
public record ExtractedCollateralDocument(String ownerName, String locationText,
                                          BigDecimal appraisedValue, BigDecimal seniorClaimAmount,
                                          LocalDate appraisalDate, BigDecimal confidence) {

    public ExtractedCollateralDocument {
        if (appraisedValue == null || appraisedValue.signum() <= 0) {
            throw new LoanInvariantViolationException("감정평가액은 양수여야 합니다: " + appraisedValue);
        }
        if (seniorClaimAmount != null && seniorClaimAmount.signum() < 0) {
            throw new LoanInvariantViolationException(
                    "선순위 채권최고액은 0 이상이어야 합니다: " + seniorClaimAmount);
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new LoanInvariantViolationException("판독 신뢰도는 0~1 이어야 합니다: " + confidence);
        }
        ownerName = normalize(ownerName);
        locationText = normalize(locationText);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
