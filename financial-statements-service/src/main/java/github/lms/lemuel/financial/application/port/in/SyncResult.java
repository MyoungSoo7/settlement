package github.lms.lemuel.financial.application.port.in;

/** DART 수집 배치 결과 집계. */
public record SyncResult(int scanned, int upserted, int skipped, int failed) {
}
