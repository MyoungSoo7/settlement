package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.in.PreviewClawbackImpactUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyStatus;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;
import github.lms.lemuel.settlement.domain.ClawbackPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 대사 승인 전 회수 영향 미리보기 — 아무 상태도 바꾸지 않는다.
 *
 * <p>회수액 산정은 {@link ClawbackPolicy} 단일 출처를 쓴다. 미리보기가 자체 계산을 두면 실제 적용
 * (승인 → 이벤트 → 컨슈머)과 갈라져 "미리보기엔 300원이라더니 다르게 회수됐다"가 된다.
 *
 * <p>아직 처리되지 않은(PENDING) 차이만 센다 — 승인으로 <b>새로 발생할</b> 영향이 알고 싶은 것이지
 * 과거 처리분의 합계가 아니다.
 *
 * <p>회수가 없는 유형(수수료 불일치 등)도 건수로 보여준다. "승인해도 돈은 안 움직인다"는 사실 자체가
 * 승인 판단에 필요하기 때문이다 — 목록에서 빼버리면 운영자는 그 건들이 어디 갔는지 알 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class PreviewClawbackImpactService implements PreviewClawbackImpactUseCase {

    private final LoadReconciliationRunPort loadPort;

    public PreviewClawbackImpactService(LoadReconciliationRunPort loadPort) {
        this.loadPort = loadPort;
    }

    @Override
    public ClawbackImpact previewRun(Long runId) {
        ReconciliationRun run = loadPort.findById(runId)
                .orElseThrow(() -> new PgReconciliationInvariantViolationException(
                        "대사 실행을 찾을 수 없습니다: runId=" + runId));

        List<ClawbackLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int noImpact = 0;

        for (ReconciliationDiscrepancy d : run.getDiscrepancies()) {
            if (d.getStatus() != DiscrepancyStatus.PENDING) {
                continue;
            }
            String type = d.getType() == null ? null : d.getType().name();
            BigDecimal clawback = ClawbackPolicy.computeFor(type, d.getInternalAmount(), d.getDifference());
            if (clawback == null) {
                noImpact++;
                continue;
            }
            total = total.add(clawback);
            lines.add(new ClawbackLine(d.getId(), d.getPaymentId(), type, clawback));
        }
        return new ClawbackImpact(runId, lines.size(), total, noImpact, List.copyOf(lines));
    }
}
