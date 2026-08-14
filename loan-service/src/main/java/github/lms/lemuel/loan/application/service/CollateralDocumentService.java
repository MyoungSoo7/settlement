package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.AttachCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.GetCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.in.ReviewCollateralDocumentUseCase;
import github.lms.lemuel.loan.application.port.out.ExtractCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveCollateralDocumentPort;
import github.lms.lemuel.loan.config.CollateralOcrProperties;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentMatchDecision;
import github.lms.lemuel.loan.domain.CollateralDocumentMatcher;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentNotFoundException;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentOcrUnavailableException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 담보서류 첨부·대사·리뷰 유스케이스 구현 (ADR 0036 확산 — card·insurance 와 동형).
 *
 * <h3>첨부 흐름 (한 트랜잭션)</h3>
 * <ol>
 *   <li>담보대출·담보 존재 확인 — 무담보 상품(개인신용)에는 첨부 불가</li>
 *   <li>(loanId, SHA-256) 멱등 선조회 — 같은 파일 재업로드는 기존 서류 반환(OCR 재호출 없음)</li>
 *   <li>OCR 추출 — 미구성·실패는 503 (무폴백)</li>
 *   <li>담보 설정값(감정평가액·선순위·평가 시각) 대조 자동 대사 — 판정 저장</li>
 * </ol>
 */
@Service
public class CollateralDocumentService
        implements AttachCollateralDocumentUseCase, GetCollateralDocumentUseCase,
                   ReviewCollateralDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CollateralDocumentService.class);

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final ExtractCollateralDocumentPort extractCollateralDocumentPort;
    private final SaveCollateralDocumentPort saveCollateralDocumentPort;
    private final LoadCollateralDocumentPort loadCollateralDocumentPort;
    private final CollateralOcrProperties properties;
    private final Clock clock;

    public CollateralDocumentService(LoadSecuredLoanPort loadSecuredLoanPort,
                                     ExtractCollateralDocumentPort extractCollateralDocumentPort,
                                     SaveCollateralDocumentPort saveCollateralDocumentPort,
                                     LoadCollateralDocumentPort loadCollateralDocumentPort,
                                     CollateralOcrProperties properties,
                                     Clock clock) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.extractCollateralDocumentPort = extractCollateralDocumentPort;
        this.saveCollateralDocumentPort = saveCollateralDocumentPort;
        this.loadCollateralDocumentPort = loadCollateralDocumentPort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CollateralDocument attach(AttachCollateralDocumentCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new LoanInvariantViolationException("담보서류 파일이 비어 있습니다");
        }
        SecuredLoan loan = loadSecuredLoanPort.findById(command.loanId())
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + command.loanId()));
        Collateral collateral = loan.getCollateral();
        if (collateral == null) {
            throw new LoanInvariantViolationException(
                    "무담보 상품에는 담보서류를 첨부할 수 없습니다. loanId=" + command.loanId());
        }

        String fileHash = sha256Hex(command.content());
        Optional<CollateralDocument> existing =
                loadCollateralDocumentPort.findByLoanIdAndFileHash(loan.getId(), fileHash);
        if (existing.isPresent()) {
            log.info("담보서류 멱등 재업로드 — 기존 반환. loanId={}, fileHash={}", loan.getId(), fileHash);
            return existing.get();
        }

        if (!extractCollateralDocumentPort.isConfigured()) {
            throw new CollateralDocumentOcrUnavailableException(
                    "담보서류 OCR 이 구성되지 않았습니다 (app.loan.collateral-ocr.api-key)");
        }
        ExtractedCollateralDocument extracted =
                extractCollateralDocumentPort.extract(command.content(), command.contentType());

        LocalDateTime now = LocalDateTime.now(clock);
        CollateralDocument document = CollateralDocument.extracted(
                loan.getId(), collateral.getId(), command.uploaderUserId(),
                command.fileName(), command.contentType(), fileHash,
                (long) command.content().length, extracted,
                extractCollateralDocumentPort.modelName(), now);
        CollateralDocumentMatchDecision decision = CollateralDocumentMatcher.decide(
                extracted, collateral.getAppraisedValue(), collateral.getSeniorClaimAmount(),
                collateral.getAppraisedAt(), properties.reviewThreshold());
        document.applyDecision(decision, now);

        CollateralDocument saved = saveCollateralDocumentPort.saveNew(document, command.content());
        log.info("담보서류 첨부·대사 완료. loanId={}, documentId={}, status={}, note={}",
                saved.getSecuredLoanId(), saved.getId(), saved.getStatus(), saved.getMatchNote());
        return saved;
    }

    @Override
    @Transactional
    public CollateralDocument review(ReviewCollateralDocumentCommand command) {
        CollateralDocument document = loadCollateralDocumentPort.findById(command.documentId())
                .orElseThrow(() -> new CollateralDocumentNotFoundException(command.documentId()));
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.matched()) {
            document.reviewMatch(command.reviewerId(), command.note(), now);
        } else {
            document.reviewMismatch(command.reviewerId(), command.note(), now);
        }
        CollateralDocument saved = saveCollateralDocumentPort.update(document);
        log.info("담보서류 리뷰 종결. documentId={}, status={}, reviewerId={}",
                saved.getId(), saved.getStatus(), command.reviewerId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollateralDocument> latestForLoan(Long loanId) {
        return loadCollateralDocumentPort.findLatestByLoanId(loanId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<CollateralDocument> byStatus(
            github.lms.lemuel.loan.domain.CollateralDocumentStatus status, int limit) {
        return loadCollateralDocumentPort.findByStatus(status, limit);
    }

    /** (securedLoanId, fileHash) 멱등 키의 해시 축 — 같은 파일이면 항상 같은 값. */
    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
