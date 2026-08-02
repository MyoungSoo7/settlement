package github.lms.lemuel.card.domain.exception;

import java.math.BigDecimal;

/**
 * 불변식 위반 — masterLimit &lt; Σ subLimit. 카드 발급/서브한도 변경이 이 불변식을 깨려 할 때
 * {@link github.lms.lemuel.card.domain.CardAccount#assertCanIssue} 가 던진다.
 *
 * <p>도메인은 {@code BusinessException} 을 쓰지 않는다 — ErrorCode 매핑은 응용 계층 책임이다.
 */
public class SubLimitExceededException extends RuntimeException {

    public SubLimitExceededException(BigDecimal masterLimit, BigDecimal currentSubLimitSum, BigDecimal newSubLimit) {
        super("서브한도 합계가 마스터 한도를 초과합니다: masterLimit=" + masterLimit
                + ", currentSubLimitSum=" + currentSubLimitSum + ", newSubLimit=" + newSubLimit);
    }
}
