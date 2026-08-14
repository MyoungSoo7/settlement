package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 예치금 증빙(이체확인증·입금확인증) OCR 추출 결과 VO (순수 도메인, 불변 — ADR 0036 확산).
 *
 * <p>이체금액·신뢰도는 필수 — 금액을 못 읽은 추출은 대사 근거가 될 수 없어 어댑터가 503 으로
 * 끊는다(무폴백). 입금자명·이체일은 판독 실패를 {@code null} 로 표현한다 — 지어내지 않고,
 * 이체일 null 은 대사에서 NEEDS_REVIEW 로 흐른다.
 *
 * <p>수취계좌번호는 <b>추출 대상이 아니다</b> — deposit 에 대조할 정본이 없고(계좌 정보는 타 서비스
 * 소관), 대조 못 할 민감값은 아예 다루지 않는다(PII 최소화).
 *
 * @param senderName     입금자명 — 참고 정보(셀러명 정본이 deposit 에 없어 판정에 쓰지 않는다)
 * @param transferDate   이체일 (판독 실패 null)
 * @param transferAmount 이체금액 (필수·양수, BigDecimal 강제)
 * @param confidence     판독 신뢰도 0~1 (필수)
 */
public record ExtractedTransferProof(String senderName, LocalDate transferDate,
                                     BigDecimal transferAmount, BigDecimal confidence) {

    public ExtractedTransferProof {
        if (transferAmount == null || transferAmount.signum() <= 0) {
            throw new InvalidDepositProofException("이체금액은 양수여야 합니다: " + transferAmount);
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidDepositProofException("판독 신뢰도는 0~1 이어야 합니다: " + confidence);
        }
        senderName = normalize(senderName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
