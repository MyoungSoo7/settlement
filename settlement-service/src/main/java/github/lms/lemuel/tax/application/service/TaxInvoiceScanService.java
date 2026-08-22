package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.tax.application.exception.TaxInvoiceScanNotFoundException;
import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.exception.UnsupportedScanFileException;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ReviewTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.out.ExtractTaxInvoiceFieldsPort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoiceScanPort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoiceScanPort;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.scan.ExtractedTaxInvoice;
import github.lms.lemuel.tax.domain.scan.ScanMatchDecision;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanMatcher;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 세금계산서 스캔 유스케이스 조립 — 업로드 검증 → OCR 추출 → 도메인 검증 → 자동 대사 → 저장.
 *
 * <p>세 가지가 이 서비스의 존재 이유다:
 * <ol>
 *   <li><b>멱등</b>: 멱등 키는 {@code (sellerId, sha256(파일))} 이다. 재업로드는 AI 를 다시 부르지 않는다
 *       — 비용이자, 같은 파일이 다른 결과로 두 번 저장되는 것을 막는 장치다.</li>
 *   <li><b>신뢰 경계</b>: 벤더 응답({@link OcrExtraction})은 도메인 VO({@link ExtractedTaxInvoice})를
 *       통과하며 검증된다. 산술 불일치·저신뢰는 예외가 아니라 리뷰 플래그로 남는다.</li>
 *   <li><b>IDOR</b>: 대사 상대 셀러는 요청이 아니라 스캔 소유자(JWT 파생)와 대조된다
 *       ({@link TaxInvoiceScanMatcher#decide}).</li>
 * </ol>
 */
@Service
public class TaxInvoiceScanService implements ExtractTaxInvoiceScanUseCase, GetTaxInvoiceScanUseCase,
        ReviewTaxInvoiceScanUseCase {

    private static final Logger log = LoggerFactory.getLogger(TaxInvoiceScanService.class);

    /** 서버가 정본인 허용 목록 — 클라이언트가 선언한 Content-Type 을 그대로 믿지 않는다. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "application/pdf", "text/plain");

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_QUEUE_LIMIT = 200;

    private final ExtractTaxInvoiceFieldsPort ocrPort;
    private final SaveTaxInvoiceScanPort saveScanPort;
    private final LoadTaxInvoiceScanPort loadScanPort;
    private final LoadTaxInvoicePort loadTaxInvoicePort;
    private final Clock clock;
    private final BigDecimal confidenceThreshold;

    public TaxInvoiceScanService(ExtractTaxInvoiceFieldsPort ocrPort,
                                 SaveTaxInvoiceScanPort saveScanPort,
                                 LoadTaxInvoiceScanPort loadScanPort,
                                 LoadTaxInvoicePort loadTaxInvoicePort,
                                 Clock clock,
                                 @Value("${app.tax.ocr.confidence-threshold:0.80}") BigDecimal confidenceThreshold) {
        this.ocrPort = ocrPort;
        this.saveScanPort = saveScanPort;
        this.loadScanPort = loadScanPort;
        this.loadTaxInvoicePort = loadTaxInvoicePort;
        this.clock = clock;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    @Transactional
    public TaxInvoiceScan extract(UploadTaxInvoiceScanCommand command) {
        validate(command);
        String fileHash = sha256Hex(command.content());

        Optional<TaxInvoiceScan> existing =
                loadScanPort.findBySellerIdAndFileHash(command.sellerId(), fileHash);
        if (existing.isPresent()) {
            log.debug("세금계산서 스캔 멱등 히트 sellerId={} hash={}", command.sellerId(), fileHash);
            return existing.get();
        }

        if (!ocrPort.isConfigured()) {
            throw new TaxOcrUnavailableException("세금계산서 OCR 이 구성되지 않았습니다(app.tax.ocr.*).");
        }
        OcrExtraction raw = ocrPort.extract(command.content(), command.contentType());
        if (raw == null) {
            throw new TaxOcrUnavailableException("OCR 이 빈 결과를 반환했습니다.");
        }

        ExtractedTaxInvoice fields = ExtractedTaxInvoice.of(raw.supplierBusinessNo(), raw.buyerBusinessNo(),
                raw.writtenDate(), raw.supplyAmount(), raw.taxAmount(), raw.totalAmount(),
                raw.approvalNumber(), raw.amountConfidence(), raw.approvalNumberConfidence());

        OffsetDateTime now = OffsetDateTime.now(clock);
        TaxInvoiceScan scan = TaxInvoiceScan.extracted(command.sellerId(), command.fileName(),
                command.contentType(), fileHash, (long) command.content().length, fields,
                ocrPort.modelName(), now);

        if (fields.needsReview(confidenceThreshold)) {
            // 믿을 수 없는 판독으로는 결론을 기록하지 않는다. EXTRACTED("아직 대사 전") 로 남겨
            // 사람이 보게 하고, 확정은 관리자가 rematch 를 눌러야 일어난다.
            //
            // 종전에는 여기서도 그대로 대사했다. 그 결과 (가) 승인번호를 못 읽었을 뿐인데
            // UNMATCHED("발행분을 못 찾았다") 라는 틀린 결론이 조사 이력에 남았고, (나) 금액이
            // 우연히 맞으면 MATCHED 로 갔다 — MATCHED 는 종결이라 관리자가 반려조차 할 수 없다.
            log.info("세금계산서 스캔 리뷰 필요 — 자동 대사를 건너뜁니다 "
                            + "sellerId={} 금액신뢰도={} 승인번호신뢰도={} total={} vat={}",
                    command.sellerId(), fields.amountConfidence(), fields.approvalNumberConfidence(),
                    fields.totalConsistent(), fields.vatConsistent());
            return saveScanPort.save(scan);
        }
        applyMatch(scan, now);
        return saveScanPort.save(scan);
    }

    @Override
    public Optional<TaxInvoiceScan> byId(Long scanId) {
        return scanId == null ? Optional.empty() : loadScanPort.findById(scanId);
    }

    @Override
    public List<TaxInvoiceScan> byStatuses(Collection<TaxInvoiceScanStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("조회할 상태를 하나 이상 지정해야 합니다");
        }
        int bounded = Math.clamp(limit, 1, MAX_QUEUE_LIMIT);
        return loadScanPort.findByStatusIn(statuses, bounded);
    }

    @Override
    @Transactional
    public TaxInvoiceScan reject(Long scanId, String note) {
        TaxInvoiceScan scan = require(scanId);
        scan.reject(note, OffsetDateTime.now(clock));
        return saveScanPort.save(scan);
    }

    @Override
    @Transactional
    /**
     * 관리자가 누르는 재대사 — <b>신뢰도 게이트를 타지 않는다.</b>
     *
     * <p>자동 경로만 보수적이어야 한다. 사람이 눈으로 확인하고 누른 재대사까지 막으면 저신뢰
     * 스캔은 영영 EXTRACTED 에 갇혀 반려 외에 길이 없다.
     */
    public TaxInvoiceScan rematch(Long scanId) {
        TaxInvoiceScan scan = require(scanId);
        applyMatch(scan, OffsetDateTime.now(clock));
        return saveScanPort.save(scan);
    }

    /**
     * 승인번호로 발행분을 찾아 대사 결과를 스캔에 반영한다.
     *
     * <p>판정이 현재 상태와 같으면 전이하지 않는다 — 상태머신이 자기 자신으로의 전이를 금지하기 때문이며
     * (UNMATCHED 재대사가 여전히 UNMATCHED 인 경우), 무의미한 updatedAt 갱신도 피한다.
     */
    private void applyMatch(TaxInvoiceScan scan, OffsetDateTime now) {
        Long settlementId = TaxInvoiceScanMatcher.settlementIdFrom(scan.getExtracted().approvalNumber());
        TaxInvoice candidate = settlementId == null
                ? null
                : loadTaxInvoicePort.findBySettlementId(settlementId).orElse(null);

        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scan.getExtracted(), scan.getSellerId(), candidate);
        if (decision.status() == scan.getStatus()) {
            return;
        }
        switch (decision.status()) {
            case MATCHED -> scan.matchTo(decision.taxInvoiceId(), now);
            case MISMATCHED -> scan.markMismatched(decision.taxInvoiceId(), decision.reason(), now);
            default -> scan.markUnmatched(decision.reason(), now);
        }
    }

    private TaxInvoiceScan require(Long scanId) {
        return byId(scanId).orElseThrow(() -> new TaxInvoiceScanNotFoundException(scanId));
    }

    private static void validate(UploadTaxInvoiceScanCommand command) {
        if (command == null || command.sellerId() == null || command.sellerId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "업로더 식별자가 유효하지 않습니다.");
        }
        if (command.content() == null || command.content().length == 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "빈 파일은 업로드할 수 없습니다.");
        }
        if (command.content().length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "스캔 파일은 " + (MAX_BYTES / 1024 / 1024) + "MB 를 넘을 수 없습니다.");
        }
        String contentType = command.contentType() == null
                ? ""
                : command.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedScanFileException(command.contentType());
        }
    }

    /** 멱등 키의 원천 — 같은 바이트열은 같은 스캔이다. */
    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
