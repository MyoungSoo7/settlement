package github.lms.lemuel.pgreconciliation.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * 대사 승인 전 회수(clawback) 영향 미리보기.
 *
 * <p>승인 버튼은 셀러에게서 돈을 도로 가져오는 후속 역정산을 일으킨다. 눌러 봐야 규모를 알 수 있으면
 * 운영자는 얼마가 회수되는지 모른 채 확정하게 된다. 아무 상태도 바꾸지 않는다.
 */
public interface PreviewClawbackImpactUseCase {

    ClawbackImpact previewRun(Long runId);

    /**
     * @param clawbackCount 실제로 돈이 회수될 차이 건수
     * @param noImpactCount 승인해도 회수가 없는 차이 건수(수수료 불일치 등) — 승인 판단에 필요하다
     */
    record ClawbackImpact(Long runId, int clawbackCount, BigDecimal totalClawbackAmount,
                          int noImpactCount, List<ClawbackLine> lines) { }

    /** 회수 예정 1건. */
    record ClawbackLine(Long discrepancyId, Long paymentId, String type, BigDecimal clawbackAmount) { }
}
