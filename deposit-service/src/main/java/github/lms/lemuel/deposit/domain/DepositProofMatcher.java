package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 수기 기표 요청 ↔ 예치금 증빙 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만).
 *
 * <p><b>지연 대사</b>: deposit 수기 기표는 선행 애그리거트가 없어 첨부 시점에는 대조할 정본이 없다.
 * 이 매처는 기표({@code credit}/{@code debit}) 시점에 <b>기표 요청 값</b>과 대조한다.
 *
 * <p>판정 순서가 곧 정책이다:
 * <ol>
 *   <li><b>신뢰도 미달 → NEEDS_REVIEW</b> — 믿을 수 없는 값으로 불일치를 선고하지 않는다.</li>
 *   <li><b>이체금액 → compareTo 정확 일치</b> — 잔고는 단일 진실원이고 payout·offset 이 즉시 재원을
 *       소비하므로, 1원의 허용 오차도 그만큼의 증빙 없는 잔고가 된다.</li>
 *   <li><b>이체일 → 기표일 ±허용일수</b> — 은행 이체 후 운영자가 며칠 뒤 수기 기표하는 리드타임을
 *       흡수한다(기본 3일, {@code app.deposit.proof-ocr.date-tolerance-days}). 다른 확산처(±1일)보다
 *       넓은 이유: 저쪽은 시스템 사건 시각 대 서류 일자였고 여기는 사람 손 기표다. 판독 불가(null)는 리뷰.</li>
 * </ol>
 *
 * <p>입금자명은 판정에 쓰지 않는다 — deposit 은 셀러명 정본을 갖지 않는다(sellerId 뿐). 참고 정보로
 * 증빙 행에 보존만 한다.
 */
public final class DepositProofMatcher {

    private DepositProofMatcher() {
    }

    /**
     * 증빙 추출값과 기표 요청을 대조해 도달할 상태를 정한다.
     *
     * @param extracted         OCR 추출 결과
     * @param entryAmount       기표 요청 금액 ({@code DepositEntryRequest.amount})
     * @param entryDate         기표일 (KST — deposit ClockConfig 기준)
     * @param dateToleranceDays 이체일 허용 리드타임(일)
     * @param reviewThreshold   신뢰도 리뷰 임계 (미만이면 NEEDS_REVIEW)
     */
    public static DepositProofMatchDecision decide(ExtractedTransferProof extracted,
                                                   BigDecimal entryAmount, LocalDate entryDate,
                                                   int dateToleranceDays, BigDecimal reviewThreshold) {
        if (extracted == null || entryAmount == null || entryDate == null || reviewThreshold == null) {
            throw new InvalidDepositProofException("대사 입력은 전부 필수입니다");
        }
        if (dateToleranceDays < 0) {
            throw new InvalidDepositProofException("이체일 허용일수는 0 이상이어야 합니다: " + dateToleranceDays);
        }
        if (extracted.confidence().compareTo(reviewThreshold) < 0) {
            return DepositProofMatchDecision.needsReview("판독 신뢰도 미달: "
                    + extracted.confidence().toPlainString() + " < " + reviewThreshold.toPlainString());
        }
        if (extracted.transferAmount().compareTo(entryAmount) != 0) {
            return DepositProofMatchDecision.mismatched("이체금액 불일치: 증빙 "
                    + extracted.transferAmount().toPlainString() + " vs 기표 " + entryAmount.toPlainString());
        }
        if (extracted.transferDate() == null) {
            return DepositProofMatchDecision.needsReview("이체일 판독 불가 — 육안 대조 필요");
        }
        long dayDiff = Math.abs(ChronoUnit.DAYS.between(extracted.transferDate(), entryDate));
        if (dayDiff > dateToleranceDays) {
            return DepositProofMatchDecision.mismatched("이체일 불일치: 증빙 " + extracted.transferDate()
                    + " vs 기표일 " + entryDate + " (허용 ±" + dateToleranceDays + "일)");
        }
        return DepositProofMatchDecision.matched();
    }
}
