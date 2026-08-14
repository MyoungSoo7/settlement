package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 청약 ↔ 청약서류 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만. card {@code ExpenseReceiptMatcher} 와 동형).
 *
 * <p>판정 순서가 곧 정책이다:
 * <ol>
 *   <li><b>신뢰도 미달 → NEEDS_REVIEW</b> — 믿을 수 없는 값으로 불일치를 선고하면 멀쩡한 서류가
 *       종결(MISMATCHED)로 떨어진다. 사람 리뷰가 먼저다.</li>
 *   <li><b>연 보험료 → compareTo 정확 일치</b> — 1원 차이도 불일치. 보험료는 수수료 12회 스케줄의
 *       원천이라 허용 오차를 두는 순간 그 오차가 수수료 오계산으로 번진다.</li>
 *   <li><b>보장금액 → 정확 일치</b> — 판독 불가(null)는 불일치가 아니라 리뷰.</li>
 *   <li><b>청약일 → 접수일(KST) ±1일</b> — 지면 작성일과 시스템 접수일의 하루 차를 흡수한다.
 *       판독 불가(null)는 리뷰.</li>
 * </ol>
 *
 * <p>성명·상품명은 판정에 쓰지 않는다 — OCR 표기(한자·괄호 병기 등)는 등록값과 상시 불일치한다
 * (card 상호명과 같은 판단). 판정 근거로는 남기지 않되 참고 정보로 서류 행에 보존된다.
 */
public final class ApplicationDocumentMatcher {

    /** 접수 시각의 청약일 환산 기준 — 국내 청약은 KST 로 접수된다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long DATE_TOLERANCE_DAYS = 1;

    private ApplicationDocumentMatcher() {
    }

    /**
     * 서류 추출값과 청약을 대조해 도달할 상태를 정한다.
     *
     * @param extracted       OCR 추출 결과
     * @param desiredPremium  청약의 연 보험료 ({@code InsuranceApplication.desiredPremium})
     * @param desiredCoverage 청약의 보장금액
     * @param submittedAt     청약 접수 시각 (DB {@code submitted_at})
     * @param reviewThreshold 신뢰도 리뷰 임계 (미만이면 NEEDS_REVIEW)
     */
    public static DocumentMatchDecision decide(ExtractedApplicationForm extracted,
                                               BigDecimal desiredPremium, BigDecimal desiredCoverage,
                                               Instant submittedAt, BigDecimal reviewThreshold) {
        if (extracted == null || desiredPremium == null || desiredCoverage == null
                || submittedAt == null || reviewThreshold == null) {
            throw new InvalidApplicationDocumentException("대사 입력은 전부 필수입니다");
        }
        if (extracted.confidence().compareTo(reviewThreshold) < 0) {
            return DocumentMatchDecision.needsReview("판독 신뢰도 미달: "
                    + extracted.confidence().toPlainString() + " < " + reviewThreshold.toPlainString());
        }
        if (extracted.annualPremium().compareTo(desiredPremium) != 0) {
            return DocumentMatchDecision.mismatched("연 보험료 불일치: 서류 "
                    + extracted.annualPremium().toPlainString() + " vs 청약 " + desiredPremium.toPlainString());
        }
        if (extracted.coverageAmount() == null) {
            return DocumentMatchDecision.needsReview("보장금액 판독 불가 — 육안 대조 필요");
        }
        if (extracted.coverageAmount().compareTo(desiredCoverage) != 0) {
            return DocumentMatchDecision.mismatched("보장금액 불일치: 서류 "
                    + extracted.coverageAmount().toPlainString() + " vs 청약 " + desiredCoverage.toPlainString());
        }
        if (extracted.applicationDate() == null) {
            return DocumentMatchDecision.needsReview("청약일 판독 불가 — 육안 대조 필요");
        }
        LocalDate submittedDate = submittedAt.atZone(KST).toLocalDate();
        long dayDiff = Math.abs(ChronoUnit.DAYS.between(submittedDate, extracted.applicationDate()));
        if (dayDiff > DATE_TOLERANCE_DAYS) {
            return DocumentMatchDecision.mismatched("청약일 불일치: 서류 "
                    + extracted.applicationDate() + " vs 접수일(KST) " + submittedDate);
        }
        return DocumentMatchDecision.matched();
    }
}
