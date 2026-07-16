package github.lms.lemuel.integrity.application.port.out;

import java.math.BigDecimal;

/**
 * INV-12 프로젝션 diff 1차 스크리닝용 키셋 체크섬 — order 원천/settlement 프로젝션 양측이
 * 자기 DB 에서 계산해 교환하는 3-스칼라 요약 (설계서 §5 데이터량 회피).
 *
 * @param count      대상 결제 건수
 * @param amountSum  대상 결제 금액 합 (BigDecimal, 반올림 없이 원본 스케일 유지)
 * @param idChecksum 정렬된 결제 id 집합의 md5 hex (순서 무관·집합 동일성만 본다). 빈 집합은 "".
 */
public record KeyChecksum(long count, BigDecimal amountSum, String idChecksum) {

    /** count·금액합(부호 무시 비교)·id md5 가 모두 같으면 두 집합은 동일하다고 본다. */
    public boolean matches(KeyChecksum other) {
        return other != null
                && count == other.count
                && amountSum.compareTo(other.amountSum) == 0
                && idChecksum.equals(other.idChecksum);
    }
}
