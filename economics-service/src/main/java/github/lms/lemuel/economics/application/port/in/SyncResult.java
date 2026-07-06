package github.lms.lemuel.economics.application.port.in;

/** ECOS 수집 배치 결과 집계. */
public record SyncResult(int scanned, int upserted, int skipped, int failed) {
}
