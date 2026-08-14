package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.out.LoadDepositProofPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositProofPort;
import github.lms.lemuel.deposit.config.ProofOcrProperties;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofMatchDecision;
import github.lms.lemuel.deposit.domain.DepositProofMatcher;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotMatchedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 수기 기표의 증빙 대사 게이트 (ADR 0036 확산 — 지연 대사 변형).
 *
 * <p>{@code DepositService.credit}/{@code debit} 의 최상단에서 호출된다. 해당 참조
 * {@code (sellerId, referenceType, referenceId)} 에 증빙이 <b>있으면</b>:
 * <ul>
 *   <li>EXTRACTED — 지금이 대사 시점이다: 기표 요청 값(금액·기표일)과 대조해 판정을 영속하고,
 *       MATCHED 가 아니면 422 로 끊는다 (기표 트랜잭션 전체 롤백)</li>
 *   <li>NEEDS_REVIEW·MISMATCHED — 리뷰 종결·재첨부가 먼저다: 422</li>
 *   <li>MATCHED — 통과 (운영자 육안 확정 또는 선행 대사 통과)</li>
 * </ul>
 * 증빙이 <b>없으면</b> 기존 경로 그대로 통과(점진 도입) — Kafka 자동 기표(SETTLEMENT/PAYOUT)는
 * 증빙이 붙을 일이 없어 자동으로 무영향이다.
 *
 * <p><b>판정 영속은 통과(MATCHED)일 때만 남는다</b> — 대사 실패로 422 를 던지면 기표 트랜잭션 전체가
 * 롤백되어 판정도 함께 사라지고 증빙은 EXTRACTED 로 남는다. 이것은 의도된 동작이다: 운영자가 기표
 * 금액을 잘못 친 경우(증빙이 맞음) 요청 값만 정정해 재시도할 수 있어야 하고, 증빙 자체가 틀린 경우엔
 * 매 시도가 같은 사유로 422 나므로 재첨부로 유도된다. 리뷰가 필요한 결함(신뢰도·이체일 판독 불가)은
 * 첨부 시점에 이미 NEEDS_REVIEW 로 영속돼 있어 리뷰 경로가 막히지 않는다.
 */
@Component
public class DepositProofGate {

    private static final Logger log = LoggerFactory.getLogger(DepositProofGate.class);

    private final LoadDepositProofPort loadDepositProofPort;
    private final SaveDepositProofPort saveDepositProofPort;
    private final ProofOcrProperties properties;
    private final Clock clock;

    public DepositProofGate(LoadDepositProofPort loadDepositProofPort,
                            SaveDepositProofPort saveDepositProofPort,
                            ProofOcrProperties properties,
                            Clock clock) {
        this.loadDepositProofPort = loadDepositProofPort;
        this.saveDepositProofPort = saveDepositProofPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 참조에 증빙이 첨부돼 있으면 대사 통과를 요구한다.
     *
     * @throws DepositProofNotMatchedException 최신 증빙이 MATCHED 에 도달하지 못했다 (422)
     */
    public void assertMatchedIfProofExists(Long sellerId, String referenceType, String referenceId,
                                           BigDecimal entryAmount) {
        Optional<DepositProof> latest =
                loadDepositProofPort.findLatestByReference(sellerId, referenceType, referenceId);
        if (latest.isEmpty()) {
            return;   // 증빙 없음 — 점진 도입, 기존 경로 그대로
        }
        DepositProof proof = latest.get();
        switch (proof.getStatus()) {
            case MATCHED -> { /* 운영자 육안 확정 또는 선행 대사 통과 */ }
            case MISMATCHED, NEEDS_REVIEW -> throw new DepositProofNotMatchedException(
                    referenceId, proof.getStatus(), proof.getMatchNote());
            case EXTRACTED -> {
                LocalDateTime now = LocalDateTime.now(clock);
                DepositProofMatchDecision decision = DepositProofMatcher.decide(
                        proof.getExtracted(), entryAmount, LocalDate.now(clock),
                        properties.dateToleranceDays(), properties.reviewThreshold());
                proof.applyDecision(decision, now);
                saveDepositProofPort.update(proof);
                log.info("[deposit] 증빙 지연 대사. proofId={}, ref={}/{}, decision={}",
                        proof.getId(), referenceType, referenceId, decision.status());
                if (decision.status() != DepositProofStatus.MATCHED) {
                    throw new DepositProofNotMatchedException(
                            referenceId, decision.status(), decision.note());
                }
            }
        }
    }
}
