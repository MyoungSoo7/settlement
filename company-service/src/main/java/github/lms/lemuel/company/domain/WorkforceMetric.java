package github.lms.lemuel.company.domain;

/**
 * 비교 지표. 두 지표는 같은 집단을 공유하지만 스케일 규칙이 다르다 — 금액 지표만 금액 규칙
 * (원 단위, JSON 소수 문자열 직렬화)의 대상이고, 인원수는 금액이 아니다.
 */
public enum WorkforceMetric {

    /**
     * 사업장 가입자 수. 중앙값은 percentile_cont(0.5) 라 짝수 표본에서 소수가 나오므로 정수로 강제하지
     * 않고 소수 한 자리까지 허용한다.
     */
    HEADCOUNT(1, false),

    /** 당월고지금액으로 역산한 1인당 추정연봉(원 단위). */
    ESTIMATED_ANNUAL_SALARY(0, true);

    private final int scale;
    private final boolean money;

    WorkforceMetric(int scale, boolean money) {
        this.scale = scale;
        this.money = money;
    }

    /** 중앙값·차이에 적용할 소수 자릿수. */
    public int scale() {
        return scale;
    }

    public boolean isMoney() {
        return money;
    }
}
