package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 담보 설정값 ↔ 담보서류 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만).
 *
 * <p>판정 순서가 곧 정책이다:
 * <ol>
 *   <li><b>신뢰도 미달 → NEEDS_REVIEW</b> — 믿을 수 없는 값으로 불일치를 선고하지 않는다.</li>
 *   <li><b>감정평가액 → compareTo 정확 일치</b> — 평가액은 한도 산정({@code SecuredLoanPolicy})의
 *       원천이라 1원의 허용 오차도 그만큼의 근거 없는 여신이 된다.</li>
 *   <li><b>선순위 채권최고액</b> — 신청자 자기신고값이라 대사 가치가 가장 높다(현재 검증 수단 0).
 *       서류에서 판독 불가(null)면: 담보의 선순위가 0 이면 확인할 대상이 없어 통과, 0 이 아니면
 *       NEEDS_REVIEW(선순위가 있다고 신고했는데 서류에서 못 읽었다 — 육안 확인 필수).</li>
 *   <li><b>평가기준일 → 설정 시각(appraisedAt) ±1일</b> — 감정과 시스템 설정의 하루 차 흡수.
 *       판독 불가(null)는 리뷰.</li>
 * </ol>
 *
 * <p>소유자·소재지는 판정에 쓰지 않는다 — 제3자 담보(담보제공자≠차주)를 도메인이 표현하지 못하고,
 * 소재지 표기는 자유 텍스트라 자동 대조가 성립하지 않는다. 참고 정보로 서류 행에 보존만 한다.
 */
public final class CollateralDocumentMatcher {

    private static final long DATE_TOLERANCE_DAYS = 1;

    private CollateralDocumentMatcher() {
    }

    /**
     * 서류 추출값과 담보 설정값을 대조해 도달할 상태를 정한다.
     *
     * @param extracted         OCR 추출 결과
     * @param appraisedValue    담보 감정평가액 ({@code Collateral.appraisedValue} — 설정 시점 스냅샷)
     * @param seniorClaimAmount 담보 선순위 채권액 ({@code Collateral.seniorClaimAmount})
     * @param appraisedAt       담보 평가 시각 (응용 계층 KST Clock 스냅샷)
     * @param reviewThreshold   신뢰도 리뷰 임계 (미만이면 NEEDS_REVIEW)
     */
    public static CollateralDocumentMatchDecision decide(ExtractedCollateralDocument extracted,
                                                         BigDecimal appraisedValue,
                                                         BigDecimal seniorClaimAmount,
                                                         LocalDateTime appraisedAt,
                                                         BigDecimal reviewThreshold) {
        if (extracted == null || appraisedValue == null || seniorClaimAmount == null
                || appraisedAt == null || reviewThreshold == null) {
            throw new LoanInvariantViolationException("대사 입력은 전부 필수입니다");
        }
        if (extracted.confidence().compareTo(reviewThreshold) < 0) {
            return CollateralDocumentMatchDecision.needsReview("판독 신뢰도 미달: "
                    + extracted.confidence().toPlainString() + " < " + reviewThreshold.toPlainString());
        }
        if (extracted.appraisedValue().compareTo(appraisedValue) != 0) {
            return CollateralDocumentMatchDecision.mismatched("감정평가액 불일치: 서류 "
                    + extracted.appraisedValue().toPlainString() + " vs 설정 " + appraisedValue.toPlainString());
        }
        if (extracted.seniorClaimAmount() == null) {
            if (seniorClaimAmount.signum() != 0) {
                return CollateralDocumentMatchDecision.needsReview(
                        "선순위 채권최고액 판독 불가 — 신고값 " + seniorClaimAmount.toPlainString()
                                + " 의 육안 대조 필요");
            }
        } else if (extracted.seniorClaimAmount().compareTo(seniorClaimAmount) != 0) {
            return CollateralDocumentMatchDecision.mismatched("선순위 채권최고액 불일치: 서류 "
                    + extracted.seniorClaimAmount().toPlainString()
                    + " vs 신고 " + seniorClaimAmount.toPlainString());
        }
        if (extracted.appraisalDate() == null) {
            return CollateralDocumentMatchDecision.needsReview("평가기준일 판독 불가 — 육안 대조 필요");
        }
        LocalDate appraisedDate = appraisedAt.toLocalDate();
        long dayDiff = Math.abs(ChronoUnit.DAYS.between(appraisedDate, extracted.appraisalDate()));
        if (dayDiff > DATE_TOLERANCE_DAYS) {
            return CollateralDocumentMatchDecision.mismatched("평가기준일 불일치: 서류 "
                    + extracted.appraisalDate() + " vs 설정 " + appraisedDate);
        }
        return CollateralDocumentMatchDecision.matched();
    }
}
