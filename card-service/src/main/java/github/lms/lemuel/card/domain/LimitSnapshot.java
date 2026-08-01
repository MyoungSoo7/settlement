package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 한도 산정 근거 스냅샷 — 사후에 "왜 이 한도였나"를 재현하기 위해 보존한다
 * (loan-service 가 신청 시점 신용점수·등급을 보존하는 것과 같은 이유).
 *
 * <p>심사 통과({@code activate})뿐 아니라 심사 탈락({@code reject})에도 만들어질 수 있다 —
 * 근거 없는 거절을 남기지 않기 위해서다.
 */
public record LimitSnapshot(BigDecimal sellerPayable,
                            BigDecimal holdbackPayable,
                            BigDecimal appliedRatio,
                            ReputationGrade reputationGrade,
                            String formula) {

    public LimitSnapshot {
        if (sellerPayable == null || holdbackPayable == null || appliedRatio == null
                || reputationGrade == null) {
            throw new IllegalArgumentException("한도 산정 근거는 전부 필수다 — 근거 없는 한도를 남기지 않는다");
        }
    }

    /** 재원 F = 확정·미지급 정산금 + 홀드백 유보분. */
    public BigDecimal funding() {
        return sellerPayable.add(holdbackPayable);
    }
}
