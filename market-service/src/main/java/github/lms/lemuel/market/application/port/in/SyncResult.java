package github.lms.lemuel.market.application.port.in;

/** KRX 시세 수집 배치 결과 집계. */
public record SyncResult(int scanned, int upserted, int skipped, int failed) {
}
