package github.lms.lemuel.commondata.application.port.in;

public record SyncResult(int scanned, int upserted, int skipped, int failed) { }
