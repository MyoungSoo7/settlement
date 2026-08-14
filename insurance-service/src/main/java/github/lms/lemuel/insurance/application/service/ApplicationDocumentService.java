package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.GetApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.in.ReviewApplicationDocumentUseCase;
import github.lms.lemuel.insurance.application.port.out.ExtractApplicationFormPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationDocumentPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationSubmissionPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationDocumentPort;
import github.lms.lemuel.insurance.config.ApplicationOcrProperties;
import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentMatcher;
import github.lms.lemuel.insurance.domain.DocumentMatchDecision;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotFoundException;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 청약서류 첨부·대사·리뷰 유스케이스 구현 (ADR 0036 확산 — card {@code ExpenseReceiptService} 와 동형).
 *
 * <h3>첨부 흐름 (한 트랜잭션)</h3>
 * <ol>
 *   <li>청약 존재·비종결 확인 — APPROVED/REJECTED 청약에는 첨부 불가</li>
 *   <li>(applicationId, SHA-256) 멱등 선조회 — 같은 파일 재업로드는 기존 서류 반환(OCR 재호출 없음)</li>
 *   <li>OCR 추출 — 미구성·실패는 503 (무폴백)</li>
 *   <li>청약(연 보험료·보장금액·접수일) 대조 자동 대사 — MATCHED/MISMATCHED/NEEDS_REVIEW 판정 저장</li>
 * </ol>
 */
@Service
public class ApplicationDocumentService
        implements AttachApplicationDocumentUseCase, GetApplicationDocumentUseCase,
                   ReviewApplicationDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDocumentService.class);

    private final LoadApplicationPort loadApplicationPort;
    private final LoadApplicationSubmissionPort loadApplicationSubmissionPort;
    private final ExtractApplicationFormPort extractApplicationFormPort;
    private final SaveApplicationDocumentPort saveApplicationDocumentPort;
    private final LoadApplicationDocumentPort loadApplicationDocumentPort;
    private final ApplicationOcrProperties properties;
    private final Clock clock;

    public ApplicationDocumentService(LoadApplicationPort loadApplicationPort,
                                      LoadApplicationSubmissionPort loadApplicationSubmissionPort,
                                      ExtractApplicationFormPort extractApplicationFormPort,
                                      SaveApplicationDocumentPort saveApplicationDocumentPort,
                                      LoadApplicationDocumentPort loadApplicationDocumentPort,
                                      ApplicationOcrProperties properties,
                                      Clock clock) {
        this.loadApplicationPort = loadApplicationPort;
        this.loadApplicationSubmissionPort = loadApplicationSubmissionPort;
        this.extractApplicationFormPort = extractApplicationFormPort;
        this.saveApplicationDocumentPort = saveApplicationDocumentPort;
        this.loadApplicationDocumentPort = loadApplicationDocumentPort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ApplicationDocument attach(AttachDocumentCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new InvalidApplicationDocumentException("청약서류 파일이 비어 있습니다");
        }
        InsuranceApplication application = loadApplicationPort.findByApplicationId(command.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(command.applicationId()));
        if (application.getStatus().isTerminal()) {
            throw new InvalidApplicationDocumentException(
                    "종결된 청약에는 서류를 첨부할 수 없습니다: 상태=" + application.getStatus());
        }

        String fileHash = sha256Hex(command.content());
        Optional<ApplicationDocument> existing = loadApplicationDocumentPort
                .findByApplicationIdAndFileHash(application.getApplicationId(), fileHash);
        if (existing.isPresent()) {
            log.info("청약서류 멱등 재업로드 — 기존 반환. applicationId={}, fileHash={}",
                    application.getApplicationId(), fileHash);
            return existing.get();
        }

        if (!extractApplicationFormPort.isConfigured()) {
            throw new ApplicationDocumentOcrUnavailableException(
                    "청약서류 OCR 이 구성되지 않았습니다 (app.insurance.application-ocr.api-key)");
        }
        ExtractedApplicationForm extracted =
                extractApplicationFormPort.extract(command.content(), command.contentType());

        Instant submittedAt = loadApplicationSubmissionPort
                .findSubmittedAt(application.getApplicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(application.getApplicationId()));

        Instant now = Instant.now(clock);
        ApplicationDocument document = ApplicationDocument.extracted(
                application.getApplicationId(), command.uploadedBy(), command.fileName(),
                command.contentType(), fileHash, (long) command.content().length, extracted,
                extractApplicationFormPort.modelName(), now);
        DocumentMatchDecision decision = ApplicationDocumentMatcher.decide(
                extracted, application.getDesiredPremium(), application.getDesiredCoverage(),
                submittedAt, properties.reviewThreshold());
        document.applyDecision(decision, now);

        ApplicationDocument saved = saveApplicationDocumentPort.saveNew(document, command.content());
        log.info("청약서류 첨부·대사 완료. applicationId={}, documentId={}, status={}, note={}",
                saved.getApplicationId(), saved.getId(), saved.getStatus(), saved.getMatchNote());
        return saved;
    }

    @Override
    @Transactional
    public ApplicationDocument review(ReviewDocumentCommand command) {
        ApplicationDocument document = loadApplicationDocumentPort.findById(command.documentId())
                .orElseThrow(() -> new ApplicationDocumentNotFoundException(command.documentId()));
        Instant now = Instant.now(clock);
        if (command.matched()) {
            document.reviewMatch(command.reviewerId(), command.note(), now);
        } else {
            document.reviewMismatch(command.reviewerId(), command.note(), now);
        }
        ApplicationDocument saved = saveApplicationDocumentPort.update(document);
        log.info("청약서류 리뷰 종결. documentId={}, status={}, reviewerId={}",
                saved.getId(), saved.getStatus(), command.reviewerId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicationDocument> latestForApplication(String applicationId) {
        return loadApplicationDocumentPort.findLatestByApplicationId(applicationId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ApplicationDocument> byStatus(
            github.lms.lemuel.insurance.domain.ApplicationDocumentStatus status, int limit) {
        return loadApplicationDocumentPort.findByStatus(status, limit);
    }

    /** (applicationId, fileHash) 멱등 키의 해시 축 — 같은 파일이면 항상 같은 값. */
    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
