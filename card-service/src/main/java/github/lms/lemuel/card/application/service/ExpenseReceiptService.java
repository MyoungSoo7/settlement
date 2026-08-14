package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.GetExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.out.ExtractReceiptFieldsPort;
import github.lms.lemuel.card.application.port.out.LoadCardCapturePort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReceiptPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReportPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReceiptPort;
import github.lms.lemuel.card.config.ReceiptOcrProperties;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptMatcher;
import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.card.domain.ReceiptMatchDecision;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 영수증 첨부·대사·리뷰 유스케이스 구현 (ADR 0036).
 *
 * <h3>첨부 흐름 (한 트랜잭션)</h3>
 * <ol>
 *   <li>보고서 소유권 대조 — 업로더가 보고서 소지자(holderUserId)와 다르면 403</li>
 *   <li>(reportId, SHA-256) 멱등 선조회 — 같은 파일 재업로드는 기존 영수증 반환(OCR 재호출 없음)</li>
 *   <li>OCR 추출 — 미구성·실패는 503 (무폴백)</li>
 *   <li>매입({@code CardCapture}) 대조 자동 대사 — MATCHED/MISMATCHED/NEEDS_REVIEW 판정 저장</li>
 * </ol>
 */
@Service
public class ExpenseReceiptService
        implements AttachExpenseReceiptUseCase, GetExpenseReceiptUseCase, ReviewExpenseReceiptUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpenseReceiptService.class);

    private final LoadExpenseReportPort loadExpenseReportPort;
    private final LoadCardCapturePort loadCardCapturePort;
    private final ExtractReceiptFieldsPort extractReceiptFieldsPort;
    private final SaveExpenseReceiptPort saveExpenseReceiptPort;
    private final LoadExpenseReceiptPort loadExpenseReceiptPort;
    private final ReceiptOcrProperties properties;

    public ExpenseReceiptService(LoadExpenseReportPort loadExpenseReportPort,
                                 LoadCardCapturePort loadCardCapturePort,
                                 ExtractReceiptFieldsPort extractReceiptFieldsPort,
                                 SaveExpenseReceiptPort saveExpenseReceiptPort,
                                 LoadExpenseReceiptPort loadExpenseReceiptPort,
                                 ReceiptOcrProperties properties) {
        this.loadExpenseReportPort = loadExpenseReportPort;
        this.loadCardCapturePort = loadCardCapturePort;
        this.extractReceiptFieldsPort = extractReceiptFieldsPort;
        this.saveExpenseReceiptPort = saveExpenseReceiptPort;
        this.loadExpenseReceiptPort = loadExpenseReceiptPort;
        this.properties = properties;
    }

    @Override
    @Transactional
    public ExpenseReceipt attach(AttachReceiptCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new IllegalArgumentException("영수증 파일이 비어 있습니다");
        }
        ExpenseReport report = loadExpenseReportPort.findByReportId(command.reportId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND,
                        "지출보고서를 찾을 수 없습니다: " + command.reportId()));
        if (!report.getHolderUserId().equals(command.uploaderUserId())) {
            throw new BusinessException(ErrorCode.CARD_FORBIDDEN,
                    "본인 지출보고서에만 영수증을 첨부할 수 있습니다");
        }

        String fileHash = sha256Hex(command.content());
        Optional<ExpenseReceipt> existing =
                loadExpenseReceiptPort.findByReportIdAndFileHash(report.getReportId(), fileHash);
        if (existing.isPresent()) {
            log.info("영수증 멱등 재업로드 — 기존 반환. reportId={}, fileHash={}",
                    report.getReportId(), fileHash);
            return existing.get();
        }

        if (!extractReceiptFieldsPort.isConfigured()) {
            throw new BusinessException(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE,
                    "영수증 OCR 이 구성되지 않았습니다 (app.card.receipt-ocr.api-key)");
        }
        ExtractedReceipt extracted =
                extractReceiptFieldsPort.extract(command.content(), command.contentType());

        CardCapture capture = loadCardCapturePort.findByCaptureId(report.getCaptureId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND,
                        "보고서의 매입을 찾을 수 없습니다: " + report.getCaptureId()));

        Instant now = Instant.now();
        ExpenseReceipt receipt = ExpenseReceipt.extracted(
                report.getReportId(), report.getCaptureId(), report.getOrganizationId(),
                command.uploaderUserId(), command.fileName(), command.contentType(),
                fileHash, (long) command.content().length, extracted,
                extractReceiptFieldsPort.modelName(), now);
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                extracted, capture.getCapturedAmount(), capture.getCapturedAt(),
                properties.reviewThreshold());
        receipt.applyDecision(decision, now);

        ExpenseReceipt saved = saveExpenseReceiptPort.saveNew(receipt, command.content());
        log.info("영수증 첨부·대사 완료. reportId={}, receiptId={}, status={}, note={}",
                saved.getReportId(), saved.getId(), saved.getStatus(), saved.getMatchNote());
        return saved;
    }

    @Override
    @Transactional
    public ExpenseReceipt review(ReviewReceiptCommand command) {
        ExpenseReceipt receipt = loadExpenseReceiptPort.findById(command.receiptId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_RECEIPT_NOT_FOUND,
                        "영수증을 찾을 수 없습니다: " + command.receiptId()));
        Instant now = Instant.now();
        if (command.matched()) {
            receipt.reviewMatch(command.reviewerId(), command.note(), now);
        } else {
            receipt.reviewMismatch(command.reviewerId(), command.note(), now);
        }
        ExpenseReceipt saved = saveExpenseReceiptPort.update(receipt);
        log.info("영수증 리뷰 종결. receiptId={}, status={}, reviewerId={}",
                saved.getId(), saved.getStatus(), command.reviewerId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExpenseReceipt> byId(Long receiptId) {
        return loadExpenseReceiptPort.findById(receiptId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExpenseReceipt> latestForReport(String reportId) {
        return loadExpenseReceiptPort.findLatestByReportId(reportId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ExpenseReceipt> byStatus(
            github.lms.lemuel.card.domain.ExpenseReceiptStatus status, int limit) {
        return loadExpenseReceiptPort.findByStatus(status, limit);
    }

    /** (reportId, fileHash) 멱등 키의 해시 축 — 같은 파일이면 항상 같은 값. */
    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
