package github.lms.lemuel.company.domain;

/**
 * 비교 성립 정책. 표본이 너무 작은 집단의 중앙값·백분위는 해석 가치가 없으므로 하한을 둔다.
 */
public final class ComparisonPolicy {

    /** 비교 집단 최소 표본 수. */
    public static final int MIN_SAMPLE_SIZE = 10;

    /** 증감률·백분위 소수 자릿수. */
    public static final int RATE_SCALE = 2;

    private ComparisonPolicy() {
    }

    public static boolean hasEnoughSample(int sampleSize) {
        return sampleSize >= MIN_SAMPLE_SIZE;
    }
}
