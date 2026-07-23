package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.in.RecordPayoutBounceUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutBouncePort;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutBouncePort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutBounce;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import github.lms.lemuel.payout.domain.exception.PayoutBounceNotAllowedException;
import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 송금 반송(bounce) 기록 + 정정계좌 재지급 서비스.
 *
 * <p><b>불변식/설계 (Seed D1):</b>
 * <ol>
 *   <li>반송은 COMPLETED 송금에만 성립 — 비COMPLETED 는 {@link PayoutBounceNotAllowedException}.</li>
 *   <li>원 COMPLETED payout 은 <b>미변경</b>(상태전이·필드수정 없음). 반송 사실은 {@code payout_bounces}
 *       레코드로만 표현한다(P0-6 선례).</li>
 *   <li>멱등: 같은 payout 을 두 번 반송해도 {@code payout_id} UNIQUE 로 bounce 는 1건, 재지급도 1건.
 *       재호출은 기존 bounce·재발행 payout 을 그대로 돌려준다(재생성 없음).</li>
 *   <li><b>계좌 정정 선행 강제(독립 코드리뷰 HIGH 시정)</b>: 재지급 계좌는 <b>폴백 없는</b>
 *       {@link LoadSellerBankAccountRegistrationPort} 로만 해석한다 — {@code @Primary
 *       LoadSellerBankAccountPort}(={@code RegistrySellerBankAccountAdapter})는 절대 쓰지 않는다.
 *       그 어댑터는 미등록 셀러를 {@code PlaceholderSellerBankAccountAdapter} 로 폴백해 non-null
 *       sellerId 에 대해 항상 계좌를 돌려주므로, 그걸 재지급 계좌 해석에 쓰면 "계좌 정정 선행" 가드가
 *       죽은 코드가 되어 정정 없이도 재지급이 성립해 버린다(반송 → 동일/더미 계좌 재송금 → 재반송 루프).
 *       등록 계좌가 없으면 재지급 자체를 거부한다.</li>
 *   <li><b>동일 계좌 재지급 거부</b>: 등록된 계좌가 원 payout 의 계좌 스냅샷(bankCode+accountNumber+holder)
 *       과 동일하면 거부한다 — 반송은 계좌 문제로 자금이 되돌아온 사건이므로, 정정되지 않은 동일 계좌로
 *       재지급하면 같은 사유로 재반송될 뿐이다. (동일 계좌로의 <i>전이성</i> 재시도(예: 은행 측 일시 오류)가
 *       필요하다면 이 bounce-reissue 경로가 아니라 별도 흐름에서 다뤄야 한다 — 이 경로는 "계좌 문제로
 *       인한 반송"을 전제로 설계됐다.)</li>
 *   <li>재지급 payout 은 <b>{@code settlementId=null}</b> 로 생성한다: 원 COMPLETED 가
 *       {@code (settlement_id, payout_type)} 부분 UNIQUE 슬롯을 점유하고 있어 같은 정산·유형으로는
 *       재발행이 물리적으로 불가하고, 그 UNIQUE 는 이중지급 방어의 핵심이라 약화할 수 없다
 *       (V20260721120000). 정산 링크는 {@code payout_bounces}(원 payout → resolved payout) 체인으로
 *       역추적한다. 재지급 멱등은 payout_bounces 가 전담한다.</li>
 * </ol>
 *
 * <p><b>GL 원장은 손대지 않는다</b>: payout 의 cash-outflow GL 인식이 ADR 0026 대기라 아직 payout→원장
 * 전표 자체가 없다. 따라서 반송 역분개도 범위 밖이다 — <b>ADR 0026 후속</b>에서 payout GL 인식과 함께
 * 반송 역분개를 배선한다.
 *
 * <p><b>replay 의 트랜잭션 경계 주의</b>: {@link #replay} 는 이 클래스 전체가 단일 {@code @Transactional}
 * 메서드({@link #recordBounce}) 안에서만 호출되므로, resolvedPayoutId 가 채워진 시점과 조회 시점 사이에
 * 커밋되지 않은 재발행 payout 이 안 보이는 창은 지금 존재하지 않는다. <b>주의</b>: 만약 향후 재발행
 * INSERT(현재 {@code recordBounce} 내부)를 별도 트랜잭션(예: {@code REQUIRES_NEW})으로 분리한다면,
 * bounce 커밋 이후~재발행 커밋 이전 사이에 이 메서드를 호출하는 경합이 {@code resolvedPayoutId != null}
 * 인데 {@code loadPayoutPort.findById} 가 아직 못 찾는 사일런트 홀(재지급 없이 null 반환)을 만들 수 있다
 * — 그런 리팩토링을 하려면 이 replay 경로도 함께 재검토해야 한다.
 */
@Service
public class RecordPayoutBounceService implements RecordPayoutBounceUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordPayoutBounceService.class);

    private final LoadPayoutPort loadPayoutPort;
    private final SavePayoutPort savePayoutPort;
    private final LoadPayoutBouncePort loadBouncePort;
    private final SavePayoutBouncePort saveBouncePort;
    private final LoadSellerBankAccountRegistrationPort registrationPort;
    private final AuditLogger auditLogger;

    public RecordPayoutBounceService(LoadPayoutPort loadPayoutPort,
                                     SavePayoutPort savePayoutPort,
                                     LoadPayoutBouncePort loadBouncePort,
                                     SavePayoutBouncePort saveBouncePort,
                                     LoadSellerBankAccountRegistrationPort registrationPort,
                                     AuditLogger auditLogger) {
        this.loadPayoutPort = loadPayoutPort;
        this.savePayoutPort = savePayoutPort;
        this.loadBouncePort = loadBouncePort;
        this.saveBouncePort = saveBouncePort;
        this.registrationPort = registrationPort;
        this.auditLogger = auditLogger;
    }

    @Override
    @Transactional
    public BounceOutcome recordBounce(Long payoutId, String reason, String operatorId) {
        Payout original = loadPayoutPort.findById(payoutId)
                .orElseThrow(() -> new PayoutInvariantViolationException("반송 대상 payout 미존재: " + payoutId));

        // 1차 멱등(순차 재호출): 이미 반송 기록이 있으면 재지급 재생성 없이 기존 결과를 돌려준다.
        Optional<PayoutBounce> existing = loadBouncePort.findByPayoutId(payoutId);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        // 반송은 COMPLETED(자금 이동 성공 후 은행 되돌림)에만 성립.
        if (original.getStatus() != PayoutStatus.COMPLETED) {
            throw new PayoutBounceNotAllowedException(payoutId, original.getStatus());
        }

        // 정정 계좌 검증을 bounce 기록보다 먼저 수행 — fail-fast(실패 시 DB 쓰기 자체가 없다).
        // 폴백 없는 레지스트리 전용 포트로만 해석한다: @Primary LoadSellerBankAccountPort 는 미등록 셀러를
        // 플레이스홀더로 폴백해 "계좌 정정 선행" 가드를 무력화하므로 여기서는 절대 쓰지 않는다.
        SellerBankAccountRegistration registration = registrationPort.findBySellerId(original.getSellerId())
                .orElseThrow(() -> new PayoutBounceNotAllowedException(payoutId,
                        "셀러 계좌 정정 선행 필요 — 등록된 지급계좌 없음"));
        SellerBankAccount correctedAccount = registration.toBankAccount();
        if (isSameAccount(correctedAccount, original.getAccount())) {
            throw new PayoutBounceNotAllowedException(payoutId,
                    "반송 재지급은 정정된 계좌를 요구 — 동일 계좌면 정정되지 않음");
        }

        // payout_bounces INSERT — payout_id UNIQUE 가 재지급 단일성의 관문.
        PayoutBounce bounce;
        try {
            bounce = saveBouncePort.save(PayoutBounce.record(payoutId, reason, operatorId));
        } catch (DataIntegrityViolationException e) {
            // 동시 이중 반송 경합 — 다른 트랜잭션이 먼저 기록. 진 쪽은 재지급을 만들지 않는다(정확히 1건 보장).
            log.warn("[PayoutBounce] concurrent bounce skip: payoutId={}, reason={}", payoutId, e.toString());
            throw new PayoutConcurrentClaimException(payoutId);
        }

        // 재발행 payout — settlementId=null(이중지급 가드 보존, 위 클래스 주석 참조), 원 금액·유형 승계.
        Payout reissued = savePayoutPort.save(Payout.requestFromSettlement(
                null, original.getSellerId(), original.getAmount(), correctedAccount, original.getPayoutType()));

        bounce.resolveWith(reissued.getId());
        PayoutBounce resolvedBounce = saveBouncePort.save(bounce);

        auditLogger.record(AuditAction.PAYOUT_BOUNCE_RECORDED, "Payout", String.valueOf(payoutId),
                auditJson(payoutId, original, reissued, reason));
        log.warn("[PayoutBounce] recorded + reissued: originalPayoutId={}, reissuedPayoutId={}, "
                        + "sellerId={}, amount={}, reason={}",
                payoutId, reissued.getId(), original.getSellerId(), original.getAmount(), reason);
        return new BounceOutcome(resolvedBounce, reissued);
    }

    private BounceOutcome replay(PayoutBounce bounce) {
        Payout reissued = bounce.getResolvedPayoutId() == null ? null
                : loadPayoutPort.findById(bounce.getResolvedPayoutId()).orElse(null);
        log.info("[PayoutBounce] idempotent replay: payoutId={}, resolvedPayoutId={}",
                bounce.getPayoutId(), bounce.getResolvedPayoutId());
        return new BounceOutcome(bounce, reissued);
    }

    private static boolean isSameAccount(SellerBankAccount a, SellerBankAccount b) {
        return a.bankCode().equals(b.bankCode())
                && a.bankAccountNumber().equals(b.bankAccountNumber())
                && a.accountHolderName().equals(b.accountHolderName());
    }

    private String auditJson(Long payoutId, Payout original, Payout reissued, String reason) {
        return String.format(
                "{\"originalPayoutId\":%d,\"reissuedPayoutId\":%d,\"sellerId\":%s,\"amount\":\"%s\",\"reason\":\"%s\"}",
                payoutId, reissued.getId(), original.getSellerId(),
                original.getAmount().toPlainString(), escape(reason));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
