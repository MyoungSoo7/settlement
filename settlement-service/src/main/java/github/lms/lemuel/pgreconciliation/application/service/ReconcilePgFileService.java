package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.in.ReconcilePgFileUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadInternalPaymentsForReconciliationPort;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.ParsePgFilePort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * PG 정산 파일 대사 서비스.
 *
 * <p>흐름:
 * <ol>
 *   <li>운영자가 업로드한 파일 InputStream 을 {@link ParsePgFilePort} 로 파싱</li>
 *   <li>같은 영업일의 내부 결제 원장을 {@link LoadInternalPaymentsForReconciliationPort} 로 조회</li>
 *   <li>{@link PgReconciliationMatcher} 로 1:1 비교 → ROUNDING_DIFF 는 자동 보정,
 *       그 외는 PENDING 큐에 적재</li>
 *   <li>Run + 모든 Discrepancy 를 단일 트랜잭션으로 저장</li>
 *   <li>차이 종류별 Prometheus 카운터 증가 — Grafana 알람 연계</li>
 * </ol>
 *
 * <p>운영 가치: 매일 1~2건 발생하는 차액을 사람이 엑셀로 비교하던 작업을 시스템이
 * 사전 정렬해 사람은 결정만 하면 되는 형태로 단축.
 */
@Service
@Transactional
public class ReconcilePgFileService implements ReconcilePgFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcilePgFileService.class);

    private final ParsePgFilePort parsePgFilePort;
    private final LoadInternalPaymentsForReconciliationPort loadInternalPort;
    private final SaveReconciliationRunPort saveRunPort;
    private final LoadReconciliationRunPort loadRunPort;
    private final MeterRegistry meterRegistry;

    public ReconcilePgFileService(ParsePgFilePort parsePgFilePort,
                                  LoadInternalPaymentsForReconciliationPort loadInternalPort,
                                  SaveReconciliationRunPort saveRunPort,
                                  LoadReconciliationRunPort loadRunPort,
                                  MeterRegistry meterRegistry) {
        this.parsePgFilePort = parsePgFilePort;
        this.loadInternalPort = loadInternalPort;
        this.saveRunPort = saveRunPort;
        this.loadRunPort = loadRunPort;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ReconciliationRun reconcile(String pgProvider, LocalDate targetDate, String fileName,
                                        InputStream input, String operatorId) {
        log.info("[PgRecon] start. provider={}, date={}, file={}, operator={}",
                pgProvider, targetDate, fileName, operatorId);

        // 파일 내용 해시 — 같은 파일 재업로드 멱등 판정 키. 중복 run 이 각각 승인되면 같은 결제에
        // 이중 clawback 이 가능하므로(discrepancyId 1:1 UNIQUE 로는 차단 불가) 여기서 차단한다.
        byte[] fileBytes = readAllBytes(input);
        String fileSha256 = sha256Hex(fileBytes);

        var duplicate = loadRunPort.findCompletedByFileSha256(fileSha256);
        if (duplicate.isPresent()) {
            meterRegistry.counter("pg.reconciliation.duplicate_file.hit", "provider", pgProvider).increment();
            log.warn("[PgRecon] 같은 파일 재업로드 — 기존 완료 run 반환(멱등). runId={}, sha256={}, file={}",
                    duplicate.get().getId(), fileSha256, fileName);
            return duplicate.get();
        }

        ReconciliationRun run = ReconciliationRun.start(pgProvider, targetDate, fileName, operatorId, fileSha256);

        try {
            List<PgTransactionRow> pgRows = parsePgFilePort.parse(new java.io.ByteArrayInputStream(fileBytes));
            List<InternalPaymentRow> internalRows = loadInternalPort.loadByCapturedDate(targetDate);

            // 매칭 단계에서 runId 가 필요하므로 임시 sentinel 사용 — saveAll 시점에 자식들의 run 참조가 갱신됨
            PgReconciliationMatcher.MatchResult result =
                    PgReconciliationMatcher.match(0L, pgRows, internalRows);

            run.complete(pgRows.size(), internalRows.size(), result.matchedCount(), result.discrepancies());

            recordMetrics(pgProvider, result.discrepancies());

            ReconciliationRun saved = saveRunPort.saveAll(run);
            log.info("[PgRecon] done. runId={}, pg={}, internal={}, matched={}, autoCorrected={}, pending={}",
                    saved.getId(), saved.getTotalPgRows(), saved.getTotalInternalRows(),
                    saved.getMatchedCount(), saved.getAutoCorrectedCount(), saved.getDiscrepancyCount());
            return saved;

        } catch (RuntimeException e) {
            log.error("[PgRecon] failed. provider={}, date={}, file={}", pgProvider, targetDate, fileName, e);
            run.fail(e.getMessage());
            return saveRunPort.saveAll(run);
        }
    }

    /** 업로드 스트림 전체 적재 — 해시 계산과 파싱이 같은 바이트를 보도록 한 번만 읽는다. */
    private static byte[] readAllBytes(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("PG 파일 읽기 실패", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e); // 표준 알고리즘 — 발생 불가
        }
    }

    private void recordMetrics(String pgProvider, List<ReconciliationDiscrepancy> discrepancies) {
        for (DiscrepancyType type : DiscrepancyType.values()) {
            long count = discrepancies.stream().filter(d -> d.getType() == type).count();
            if (count > 0) {
                Counter.builder("pg.reconciliation.discrepancies")
                        .description("PG 정산파일 대사에서 발견된 불일치 누적 수")
                        .tag("provider", pgProvider)
                        .tag("type", type.name())
                        .register(meterRegistry)
                        .increment(count);
            }
        }
    }
}
