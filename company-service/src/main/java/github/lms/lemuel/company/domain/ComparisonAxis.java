package github.lms.lemuel.company.domain;

/**
 * 사업장 비교 축. 업종과 지역은 서로 독립적으로 판정한다 — 교차(업종 AND 지역) 집단은 모수가 급감해
 * 한 자릿수 표본이 되는 조합이 많아 제공하지 않는다.
 */
public enum ComparisonAxis {

    INDUSTRY,
    REGION
}
