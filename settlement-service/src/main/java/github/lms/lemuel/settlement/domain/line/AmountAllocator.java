package github.lms.lemuel.settlement.domain.line;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 단위 금액(배송비·할인)을 라인에 비례 배분한다.
 *
 * <p><b>절대 불변식: 배분 합 == 원본 총액.</b> 라인마다 나눠 반올림하면 잔차가 생겨 합계가
 * 원본과 어긋나는데, 정산에서 그 1원이 곧 원장 불일치다. 그래서 <b>최대잔여법</b>(largest
 * remainder)을 쓴다 — 정확 지분을 내림해 정수 단위까지 배분하고, 남은 단위를 소수부가 큰
 * 라인부터 1원씩 얹는다. 합이 항상 보존되면서 배분 왜곡도 최소가 된다.
 *
 * <p>흔한 대안 둘은 쓰지 않는다:
 * <ul>
 *   <li>라인별 {@code HALF_UP} 후 합산 — 합이 원본과 어긋난다(반올림이 한쪽으로 몰림).</li>
 *   <li>첫 라인에 전액 몰아주기(ssg 방식) — 합은 맞지만 라인 마진이 왜곡된다.</li>
 * </ul>
 *
 * <p>배분 단위는 <b>원(scale 0)</b>이다. KRW 는 최소 단위가 1원이라 소수 배분은 의미가 없고,
 * 소수를 남기면 그 자체가 다음 계산의 잔차원이 된다.
 */
public final class AmountAllocator {

    /** KRW 최소 단위 — 원 단위 정수로만 배분한다. */
    private static final int SCALE = 0;

    private AmountAllocator() { }

    /**
     * {@code total} 을 {@code weights} 비율로 배분한다.
     *
     * @param total   배분할 총액 (음수 허용 — 할인 환원 등 역방향 배분)
     * @param weights 라인별 가중치 (통상 상품 금액). 음수 불가, 비어 있을 수 없다.
     * @return 입력 순서에 대응하는 배분액. 합은 항상 {@code total} 과 정확히 같다.
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        if (total == null) {
            throw new SettlementInvariantViolationException("배분 총액은 필수입니다 (0 과 '모름'을 구분한다)");
        }
        if (weights == null || weights.isEmpty()) {
            throw new SettlementInvariantViolationException("배분 대상 라인이 없습니다");
        }
        for (BigDecimal weight : weights) {
            if (weight == null) {
                throw new SettlementInvariantViolationException("가중치는 null 일 수 없습니다");
            }
            if (weight.signum() < 0) {
                throw new SettlementInvariantViolationException("가중치는 음수일 수 없습니다: " + weight);
            }
        }

        int size = weights.size();
        BigDecimal target = total.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // 가중치가 전부 0(무료 상품만 있는 주문)이면 비율을 정의할 수 없다 — 균등 배분으로 되돌리되
        // 합 보존은 동일하게 지킨다. 여기서 예외를 던지면 정상 주문이 정산되지 않는다.
        List<BigDecimal> effective = weightSum.signum() == 0
                ? java.util.Collections.nCopies(size, BigDecimal.ONE)
                : weights;
        BigDecimal effectiveSum = weightSum.signum() == 0 ? new BigDecimal(size) : weightSum;

        // 1) 정확 지분을 0 자리로 내림(음수면 0 을 향해) — 잔여 단위를 항상 양수로 만든다.
        List<BigDecimal> base = new ArrayList<>(size);
        List<Share> shares = new ArrayList<>(size);
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < size; i++) {
            BigDecimal exact = target.multiply(effective.get(i))
                    .divide(effectiveSum, 10, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(SCALE, RoundingMode.DOWN);
            base.add(floor);
            assigned = assigned.add(floor);
            shares.add(new Share(i, exact.subtract(floor).abs()));
        }

        // 2) 남은 단위를 소수부가 큰 라인부터 1원씩 — 동률이면 앞 라인 우선(결정적 순서).
        BigDecimal remainder = target.subtract(assigned);
        int units = remainder.abs().intValueExact();
        BigDecimal step = remainder.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        shares.sort(Comparator
                .comparing((Share s) -> s.fraction).reversed()
                .thenComparingInt(s -> s.index));
        for (int k = 0; k < units; k++) {
            int idx = shares.get(k % size).index;
            base.set(idx, base.get(idx).add(step));
        }
        return List.copyOf(base);
    }

    /** 라인의 배분 소수부 — 잔여 1원을 누구에게 줄지 정하는 정렬 키. */
    private record Share(int index, BigDecimal fraction) { }
}
