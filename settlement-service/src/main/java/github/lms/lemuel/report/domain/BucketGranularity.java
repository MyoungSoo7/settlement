package github.lms.lemuel.report.domain;

public enum BucketGranularity {
    DAY,
    WEEK,
    MONTH;

    public static BucketGranularity from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DAY;
        }
        try {
            return BucketGranularity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported groupBy: " + raw + " (expected: day|week|month)");
        }
    }
}
