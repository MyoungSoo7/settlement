package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.in.AttachDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.GetDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.in.ReviewDepositProofUseCase;
import github.lms.lemuel.deposit.application.port.out.ExtractTransferProofPort;
import github.lms.lemuel.deposit.application.port.out.LoadDepositProofPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositProofPort;
import github.lms.lemuel.deposit.config.ProofOcrProperties;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofMatchDecision;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotFoundException;
import github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;
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
 * 예치금 증빙 첨부·리뷰 유스케이스 구현 (ADR 0036 확산 — 지연 대사 변형).
 *
 * <h3>첨부 흐름 (한 트랜잭션)</h3>
 * <ol>
 *   <li>(앵커, SHA-256) 멱등 선조회 — 같은 파일 재업로드는 기존 증빙 반환(OCR 재호출 없음)</li>
 *   <li>OCR 추출 — 미구성·실패는 503 (무폴백)</li>
 *   <li>신뢰도 미달만 즉시 NEEDS_REVIEW — <b>값 대사는 여기서 하지 않는다</b>(대조할 정본이 아직 없다).
 *       기표 시점의 지연 대사는 {@link DepositProofGate} 담당</li>
 * </ol>
 */
@Service
public class DepositProofService
        implements AttachDepositProofUseCase, GetDepositProofUseCase, ReviewDepositProofUseCase {

    private static final Logger log = LoggerFactory.getLogger(DepositProofService.class);

    private final ExtractTransferProofPort extractTransferProofPort;
    private final SaveDepositProofPort saveDepositProofPort;
    private final LoadDepositProofPort loadDepositProofPort;
    private final ProofOcrProperties properties;
    private final Clock clock;

    public DepositProofService(ExtractTransferProofPort extractTransferProofPort,
                               SaveDepositProofPort saveDepositProofPort,
                               LoadDepositProofPort loadDepositProofPort,
                               ProofOcrProperties properties,
                               Clock clock) {
        this.extractTransferProofPort = extractTransferProofPort;
        this.saveDepositProofPort = saveDepositProofPort;
        this.loadDepositProofPort = loadDepositProofPort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DepositProof attach(AttachProofCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new InvalidDepositProofException("증빙 파일이 비어 있습니다");
        }

        String fileHash = sha256Hex(command.content());
        Optional<DepositProof> existing = loadDepositProofPort.findByReferenceAndFileHash(
                command.sellerId(), command.referenceType(), command.referenceId(), fileHash);
        if (existing.isPresent()) {
            log.info("[deposit] 증빙 멱등 재업로드 — 기존 반환. sellerId={}, ref={}/{}, fileHash={}",
                    command.sellerId(), command.referenceType(), command.referenceId(), fileHash);
            return existing.get();
        }

        if (!extractTransferProofPort.isConfigured()) {
            throw new DepositProofOcrUnavailableException(
                    "증빙 OCR 이 구성되지 않았습니다 (app.deposit.proof-ocr.api-key)");
        }
        ExtractedTransferProof extracted =
                extractTransferProofPort.extract(command.content(), command.contentType());

        LocalDateTime now = LocalDateTime.now(clock);
        DepositProof proof = DepositProof.extracted(
                command.sellerId(), command.referenceType(), command.referenceId(),
                command.uploadedBy(), command.fileName(), command.contentType(),
                fileHash, (long) command.content().length, extracted,
                extractTransferProofPort.modelName(), now);
        // 리뷰가 필요한 결함(신뢰도 미달·이체일 판독 불가)은 첨부 시점에 즉시 NEEDS_REVIEW 로 보낸다.
        // 기표 시점(게이트)의 판정은 실패 시 트랜잭션과 함께 롤백되어 영속되지 않으므로, 여기서
        // 보내지 않으면 리뷰 경로에 영영 도달하지 못한다. 값 대사(금액·이체일 차이)만 지연 대사 몫이다.
        if (extracted.confidence().compareTo(properties.reviewThreshold()) < 0) {
            proof.applyDecision(DepositProofMatchDecision.needsReview("판독 신뢰도 미달: "
                    + extracted.confidence().toPlainString()
                    + " < " + properties.reviewThreshold().toPlainString()), now);
        } else if (extracted.transferDate() == null) {
            proof.applyDecision(DepositProofMatchDecision.needsReview(
                    "이체일 판독 불가 — 육안 대조 필요"), now);
        }

        DepositProof saved = saveDepositProofPort.saveNew(proof, command.content());
        log.info("[deposit] 증빙 첨부 완료. sellerId={}, ref={}/{}, proofId={}, status={}",
                saved.getSellerId(), saved.getReferenceType(), saved.getReferenceId(),
                saved.getId(), saved.getStatus());
        return saved;
    }

    @Override
    @Transactional
    public DepositProof review(ReviewProofCommand command) {
        DepositProof proof = loadDepositProofPort.findById(command.proofId())
                .orElseThrow(() -> new DepositProofNotFoundException(command.proofId()));
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.matched()) {
            proof.reviewMatch(command.reviewerId(), command.note(), now);
        } else {
            proof.reviewMismatch(command.reviewerId(), command.note(), now);
        }
        DepositProof saved = saveDepositProofPort.update(proof);
        log.info("[deposit] 증빙 리뷰 종결. proofId={}, status={}, reviewerId={}",
                saved.getId(), saved.getStatus(), command.reviewerId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DepositProof> latestForReference(Long sellerId, String referenceType,
                                                     String referenceId) {
        return loadDepositProofPort.findLatestByReference(sellerId, referenceType, referenceId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<DepositProof> byStatus(github.lms.lemuel.deposit.domain.DepositProofStatus status,
                                                 int limit) {
        return loadDepositProofPort.findByStatus(status, limit);
    }

    /** (앵커, fileHash) 멱등 키의 해시 축 — 같은 파일이면 항상 같은 값. */
    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
