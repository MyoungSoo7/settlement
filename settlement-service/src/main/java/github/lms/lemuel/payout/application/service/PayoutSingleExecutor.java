package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.domain.Payout;
import org.springframework.stereotype.Service;

/**
 * 단일 Payout 집행 오케스트레이터 — <b>2-phase 커밋</b> 구조로 펌뱅킹 왕복 동안 DB 커넥션을 점유하지 않는다.
 *
 * <p>이 클래스 자체는 <b>비트랜잭션</b>이다. 트랜잭션 경계는 {@link PayoutTxSteps} 의 {@code REQUIRES_NEW}
 * 단계들이 열고, 이 오케스트레이터는 그 사이(트랜잭션 밖)에서 외부 송금을 호출한다. 상위
 * {@code PayoutService.executeAllPending()} 은 {@code NOT_SUPPORTED} 라 ambient 트랜잭션도 없다.
 *
 * <p><b>3-phase 흐름:</b>
 * <ol>
 *   <li>phase1 {@link PayoutTxSteps#claim} — REQUESTED → SENDING 원자 선점을 짧은 tx 로 <i>커밋</i>. 커넥션 즉시 반납.</li>
 *   <li>phase2 {@code firmBanking.send} — 트랜잭션 밖에서 펌뱅킹 호출. DB 커넥션·행 잠금 미점유.</li>
 *   <li>phase3 {@link PayoutTxSteps#markCompleted}/{@link PayoutTxSteps#markFailed} — 결과를 별도 짧은 tx 로 확정.</li>
 * </ol>
 *
 * <p><b>보장(기존 대비 회귀 없음):</b>
 * <ul>
 *   <li><b>이중 송금 차단</b> — claim 이 {@code WHERE status=REQUESTED} 로 원자 선점. 진 쪽은
 *       {@link PayoutConcurrentClaimException} 으로 빠져 send 미호출.</li>
 *   <li><b>send 후 크래시</b> — phase3 커밋 전에 프로세스가 죽어도 행은 SENDING 으로 남는다(REQUESTED 아님).
 *       REQUESTED 만 조회하는 배치가 다시 집지 않으므로 자동 재송금이 원천 차단된다. SENDING 잔류는 기존
 *       stuck 감시(integrity {@code StuckStateReport} — 이중지급 위험 1순위)가 잡아 운영자 수동 조치로 넘긴다.
 *       (예전 구조는 send 후 커밋 실패 시 SENDING 선점이 REQUESTED 로 롤백돼 다음 배치가 재송금하는 창이 있었다.)</li>
 *   <li><b>실패 기록 durable</b> — 예전 {@code noRollbackFor} 의미를 phase3 markFailed 의 독립 커밋이 대체.
 *       FAILED 를 커밋한 뒤 이 오케스트레이터가 예외를 재던져 배치가 실패로 집계한다.</li>
 *   <li><b>referenceId 멱등</b> — {@code PAYOUT-<id>} 불변, 펌뱅킹 측 멱등 추적 유지.</li>
 * </ul>
 */
@Service
public class PayoutSingleExecutor {

    private final PayoutTxSteps txSteps;
    private final FirmBankingPort firmBanking;
    private final AuditLogger auditLogger;

    public PayoutSingleExecutor(PayoutTxSteps txSteps, FirmBankingPort firmBanking, AuditLogger auditLogger) {
        this.txSteps = txSteps;
        this.firmBanking = firmBanking;
        this.auditLogger = auditLogger;
    }

    /**
     * 개별 Payout 집행. {@link PayoutConcurrentClaimException}(선점 경합) 또는 펌뱅킹 예외를 던질 수 있으며,
     * 상위 {@code PayoutService} 배치 루프가 각각 conflict/failed 로 집계한다.
     */
    public void execute(Payout payout) {
        Payout sending = txSteps.claim(payout.getId());   // phase1: 커밋된 SENDING 선점(경합 시 여기서 throw)

        String referenceId = "PAYOUT-" + sending.getId();
        String txnId;
        try {
            txnId = firmBanking.send(sending.getAccount(), sending.getAmount(), referenceId);  // phase2: 트랜잭션 밖
        } catch (FirmBankingPort.FirmBankingException e) {
            txSteps.markFailed(sending, e);               // phase3: FAILED 커밋 + ops 실패 신호
            recordExecuted(sending, "FAILED", e.getErrorCode() + " " + e.getMessage());
            throw e;                                       // 커밋 이후 재던짐 → 배치 failed 집계, 자동 재송금 없음
        }
        txSteps.markCompleted(sending, txnId);            // phase3: COMPLETED 커밋
        recordExecuted(sending, "COMPLETED", txnId);
    }

    /**
     * 배치 payout 집행을 audit_logs 에 건별로 남긴다 — 실자금 이동이라 감사 추적 필수.
     *
     * <p>배치/컨슈머 경로라 actor 는 system(AuditContext 미설정 시 자동 system). 값은 전부 id/금액/코드라
     * 주입 위험이 없어 컴팩트 JSON 을 직접 조립한다(펌뱅킹 txnId·에러메시지만 escape). AuditLogger 가
     * 자체 {@code REQUIRES_NEW} + 예외 흡수라 감사 실패가 집행 흐름을 깨지 않는다.
     */
    private void recordExecuted(Payout p, String outcome, String detail) {
        String json = String.format(
                "{\"outcome\":\"%s\",\"payoutId\":%d,\"settlementId\":%s,\"sellerId\":%s,\"amount\":\"%s\",\"detail\":\"%s\"}",
                outcome, p.getId(), p.getSettlementId(), p.getSellerId(), p.getAmount().toPlainString(), escape(detail));
        auditLogger.record(AuditAction.PAYOUT_EXECUTED, "Payout", String.valueOf(p.getId()), json);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
