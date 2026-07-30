package github.lms.lemuel.company.domain;

/**
 * 실제로 적용된 비교 집단 단계. 표본이 최소 기준에 못 미치면 한 단계만 넓힌다
 * (업종: 6자리 코드 → 앞 3자리 / 지역: 시도+시군구 → 시도).
 *
 * <p>폴백은 집단 단위로 결정되므로 지표별로 달라지지 않는다 — 비교축마다 단계는 하나뿐이다.
 */
public enum ComparisonLevel {

    EXACT,
    BROADENED
}
