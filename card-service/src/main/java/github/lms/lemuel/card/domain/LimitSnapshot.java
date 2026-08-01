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
        // ★ 음수는 허용한다(null 만 거부) — sellerPayable 이 과지급으로 음수(-500000 등)일 수 있고,
        // 근거 보존이 이 타입의 존재 이유라 원본 부호값을 클램프 없이 그대로 남겨야 시산표로
        // "왜 이 한도였나"를 사후 재현할 수 있다. 0 바닥치기(클램프)는 CardLimitPolicy 가
        // funding() 의 *결과*에만 적용한다 — 값을 여기서 미리 죽이면 재현 불가능해진다.
    }

    /**
     * 재원 F = 확정·미지급 정산금 + 홀드백 유보분(부호 있는 원본 합계).
     *
     * <p><b>이 값은 음수일 수 있다</b>(예: sellerPayable=-500000, holdbackPayable=0 →
     * funding()=-500000). 이는 의도된 것이다 — 이 스냅샷은 "산정에 실제로 쓰인 재원"이 아니라
     * "원장을 그대로 반영한 근거"를 보존하기 위함이다. 실제 한도 산식에 쓰이는 재원은
     * {@code CardLimitPolicy.screen()} 이 이 값에 {@code max(0, ·)} 클램프를 적용한 뒤의 값이며,
     * 그 클램프된 값은 이 스냅샷에 별도로 저장되지 않는다(재현 시 이 메서드 호출 후 클램프하면 된다) —
     * 정책이 쓰는 값과 스냅샷이 보고하는 값을 분리해, 스냅샷은 항상 "원장 그대로"를 담는다.
     */
    public BigDecimal funding() {
        return sellerPayable.add(holdbackPayable);
    }
}
